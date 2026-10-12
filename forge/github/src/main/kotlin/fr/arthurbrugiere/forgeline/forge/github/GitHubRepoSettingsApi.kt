package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A GitHub repository's settings: one `PATCH` of the repository for each, its topics apart. GitHub only tells the
 * merge settings to those who can change them, and has no single way of merging nor a proposed one.
 */
class GitHubRepoSettingsApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : RepoSettingsApi {

    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name).toResult { body<JsonObject>().toSettings() }
    }

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> = gitHubCall {
        if (change is RepoSettingsChange.Topics) {
            val topics = httpClient.gitHubApi(
                apiBaseUrl, token, "repos", repo.owner, repo.name, "topics", method = HttpMethod.Put,
                body = buildJsonObject { put("names", JsonArray(change.topics.map(::JsonPrimitive))) },
            )
            if (topics.status != HttpStatusCode.OK) return@gitHubCall topics.failure()
            return@gitHubCall settings(token, repo)
        }
        val body = buildJsonObject {
            when (change) {
                is RepoSettingsChange.Description -> put("description", change.text)
                is RepoSettingsChange.Website -> put("homepage", change.url)
                is RepoSettingsChange.DefaultBranch -> put("default_branch", change.branch)
                is RepoSettingsChange.Switch -> put(SWITCHES[change.switch] ?: return@gitHubCall unsupported, change.on)
                is RepoSettingsChange.Visibility -> put("visibility", change.visibility.name.lowercase())
                is RepoSettingsChange.Archived -> put("archived", change.archived)
                else -> return@gitHubCall unsupported
            }
        }
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, method = HttpMethod.Patch, body = body).toResult { body<JsonObject>().toSettings() }
    }

    override suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, method = HttpMethod.Patch, body = buildJsonObject { put("name", name) })
            .toResult { body<JsonObject>().text("full_name").split('/', limit = 2).let { (owner, renamed) -> RepoId(owner, renamed, repo.forge) } }
    }

    override suspend fun delete(token: String, repo: RepoId): ForgeResult<RepoDeletion> = gitHubCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, method = HttpMethod.Delete)
        // GitHub says which scope a refused call wanted: deleting has its own, which older sign-ins weren't given.
        val scopes = response.headers["X-OAuth-Scopes"]?.split(',')?.map { it.trim() }
        val wanted = response.headers["X-Accepted-OAuth-Scopes"]?.split(',')?.map { it.trim() }.orEmpty()
        if (response.status == HttpStatusCode.Forbidden && scopes != null && DELETE_SCOPE in wanted && DELETE_SCOPE !in scopes) {
            return@gitHubCall ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unauthorized)
        }
        response.toResult { RepoDeletion.DELETED }
    }

    private fun JsonObject.toSettings(): RepoSettings {
        val visibility = RepoVisibility.entries.firstOrNull { it.name.lowercase() == text("visibility") }
            ?: if ((this["private"] as? JsonPrimitive)?.booleanOrNull == true) RepoVisibility.PRIVATE else RepoVisibility.PUBLIC
        return toSettings(visibility)
    }

    private fun JsonObject.toSettings(visibility: RepoVisibility) = RepoSettings(
        visibility = visibility,
        // Internal exists only inside an enterprise: it is offered where it is already what the repository is.
        visibilities = RepoVisibility.entries.filter { it != RepoVisibility.INTERNAL || visibility == RepoVisibility.INTERNAL },
        archived = (this["archived"] as? JsonPrimitive)?.booleanOrNull == true,
        description = text("description"),
        website = text("homepage"),
        topics = (this["topics"] as? JsonArray)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        defaultBranch = text("default_branch"),
        switches = SWITCHES.mapNotNull { (switch, field) -> (this[field] as? JsonPrimitive)?.booleanOrNull?.let { switch to it } }.toMap(),
    )

    private fun JsonObject.text(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val unsupported = ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported)

        const val DELETE_SCOPE = "delete_repo"

        /** In the order they are shown. */
        val SWITCHES = linkedMapOf(
            RepoSwitch.ISSUES to "has_issues",
            RepoSwitch.WIKI to "has_wiki",
            RepoSwitch.DISCUSSIONS to "has_discussions",
            RepoSwitch.PROJECTS to "has_projects",
            RepoSwitch.TEMPLATE to "is_template",
            RepoSwitch.MERGE_COMMIT to "allow_merge_commit",
            RepoSwitch.SQUASH_MERGE to "allow_squash_merge",
            RepoSwitch.REBASE_MERGE to "allow_rebase_merge",
            RepoSwitch.AUTO_MERGE to "allow_auto_merge",
            RepoSwitch.DELETE_BRANCH_ON_MERGE to "delete_branch_on_merge",
        )
    }
}
