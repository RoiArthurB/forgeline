package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
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
                else -> return@gitHubCall unsupported
            }
        }
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, method = HttpMethod.Patch, body = body).toResult { body<JsonObject>().toSettings() }
    }

    private fun JsonObject.toSettings() = RepoSettings(
        description = text("description"),
        website = text("homepage"),
        topics = (this["topics"] as? JsonArray)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        defaultBranch = text("default_branch"),
        switches = SWITCHES.mapNotNull { (switch, field) -> (this[field] as? JsonPrimitive)?.booleanOrNull?.let { switch to it } }.toMap(),
    )

    private fun JsonObject.text(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        val unsupported = ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported)

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
