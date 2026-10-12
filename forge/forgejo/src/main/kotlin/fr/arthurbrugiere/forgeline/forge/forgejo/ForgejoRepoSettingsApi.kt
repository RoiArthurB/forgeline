package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A Forgejo repository's settings: one `PATCH` of the repository for each, its topics apart. Forgejo allows several
 * ways of merging and proposes one of them; it only tells the merge settings of a repository that takes pull requests.
 */
class ForgejoRepoSettingsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : RepoSettingsApi {

    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name).toResult { body<JsonObject>().toSettings() }
    }

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> = forgejoCall {
        if (change is RepoSettingsChange.Topics) {
            val topics = httpClient.forgejoApi(
                forge, token, "repos", repo.owner, repo.name, "topics", method = HttpMethod.Put,
                body = buildJsonObject { put("topics", JsonArray(change.topics.map(::JsonPrimitive))) },
            )
            if (!topics.status.isSuccess()) return@forgejoCall topics.failure()
            return@forgejoCall settings(token, repo)
        }
        val body = buildJsonObject {
            when (change) {
                is RepoSettingsChange.Description -> put("description", change.text)
                is RepoSettingsChange.Website -> put("website", change.url)
                is RepoSettingsChange.DefaultBranch -> put("default_branch", change.branch)
                is RepoSettingsChange.Switch -> put(SWITCHES[change.switch] ?: return@forgejoCall unsupported, change.on)
                is RepoSettingsChange.DefaultMerge -> put("default_merge_style", STYLES.getValue(change.method))
                is RepoSettingsChange.Visibility -> put("private", change.visibility != RepoVisibility.PUBLIC)
                is RepoSettingsChange.Archived -> put("archived", change.archived)
                else -> return@forgejoCall unsupported
            }
        }
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, method = HttpMethod.Patch, body = body).toResult { body<JsonObject>().toSettings() }
    }

    override suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, method = HttpMethod.Patch, body = buildJsonObject { put("name", name) })
            .toResult { body<JsonObject>().text("full_name").split('/', limit = 2).let { (owner, renamed) -> RepoId(owner, renamed, repo.forge) } }
    }

    override suspend fun delete(token: String, repo: RepoId): ForgeResult<RepoDeletion> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, method = HttpMethod.Delete).toResult { RepoDeletion.DELETED }
    }

    private fun JsonObject.toSettings(): RepoSettings {
        val style = text("default_merge_style")
        return RepoSettings(
            description = text("description"),
            website = text("website"),
            topics = (this["topics"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            defaultBranch = text("default_branch"),
            switches = SWITCHES.mapNotNull { (switch, field) -> (this[field] as? JsonPrimitive)?.booleanOrNull?.let { switch to it } }.toMap(),
            defaultMerge = STYLES.entries.firstOrNull { it.value == style }?.key,
            visibility = if ((this["private"] as? JsonPrimitive)?.booleanOrNull == true) RepoVisibility.PRIVATE else RepoVisibility.PUBLIC,
            archived = (this["archived"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun JsonObject.text(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)

        /** In the order they are shown. */
        val SWITCHES = linkedMapOf(
            RepoSwitch.ISSUES to "has_issues",
            RepoSwitch.PULL_REQUESTS to "has_pull_requests",
            RepoSwitch.WIKI to "has_wiki",
            RepoSwitch.PROJECTS to "has_projects",
            RepoSwitch.CI to "has_actions",
            RepoSwitch.RELEASES to "has_releases",
            RepoSwitch.PACKAGES to "has_packages",
            RepoSwitch.TEMPLATE to "template",
            RepoSwitch.MERGE_COMMIT to "allow_merge_commits",
            RepoSwitch.SQUASH_MERGE to "allow_squash_merge",
            RepoSwitch.REBASE_MERGE to "allow_rebase",
            RepoSwitch.FAST_FORWARD_MERGE to "allow_fast_forward_only_merge",
            RepoSwitch.DELETE_BRANCH_ON_MERGE to "default_delete_branch_after_merge",
        )

        val STYLES = mapOf(MergeMethod.MERGE to "merge", MergeMethod.SQUASH to "squash", MergeMethod.REBASE to "rebase")
    }
}
