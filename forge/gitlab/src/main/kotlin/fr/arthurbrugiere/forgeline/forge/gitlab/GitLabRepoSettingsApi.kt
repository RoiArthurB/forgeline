package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A GitLab project's settings: one `PUT` of the project for each. A feature is off, on, or on for the project's
 * members only; a project merges one way; and a project has no website of its own.
 */
class GitLabRepoSettingsApi(private val httpClient: HttpClient, private val forge: ForgeInstance = ForgeInstance.GitLab) : RepoSettingsApi {

    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> = gitlabCall {
        httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName)).toResult { body<JsonObject>().toSettings() }
    }

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> = gitlabCall {
        val body = buildJsonObject {
            when (change) {
                is RepoSettingsChange.Description -> put("description", change.text)
                is RepoSettingsChange.Topics -> put("topics", JsonArray(change.topics.map(::JsonPrimitive)))
                is RepoSettingsChange.DefaultBranch -> put("default_branch", change.branch)
                is RepoSettingsChange.Style -> put("merge_method", STYLES.getValue(change.style))
                is RepoSettingsChange.Switch -> {
                    val level = LEVELS[change.switch]
                    val flag = FLAGS[change.switch]
                    when {
                        level != null -> put(level, if (change.on) ENABLED else DISABLED)
                        flag != null -> put(flag, change.on)
                        // Allowed, it is left to whoever merges; GitLab can also impose or propose it, on its website.
                        change.switch == RepoSwitch.SQUASH_MERGE -> put(SQUASH, if (change.on) "default_off" else "never")
                        else -> return@gitlabCall unsupported
                    }
                }
                else -> return@gitlabCall unsupported
            }
        }
        httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName), method = HttpMethod.Put, body = body).toResult { body<JsonObject>().toSettings() }
    }

    private fun JsonObject.toSettings(): RepoSettings {
        val levels = LEVELS.mapNotNull { (switch, field) -> textOrNull(field)?.let { switch to it } }
        val flags = FLAGS.mapNotNull { (switch, field) -> (this[field] as? JsonPrimitive)?.booleanOrNull?.let { switch to it } }
        val squash = textOrNull(SQUASH)?.let { RepoSwitch.SQUASH_MERGE to (it != "never") }
        val style = textOrNull("merge_method")
        return RepoSettings(
            description = textOrNull("description").orEmpty(),
            hasWebsite = false,
            topics = (this["topics"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            defaultBranch = textOrNull("default_branch").orEmpty(),
            switches = (levels.map { (switch, level) -> switch to (level != DISABLED) } + listOfNotNull(squash) + flags).toMap(),
            membersOnly = levels.filter { (_, level) -> level == "private" }.map { it.first }.toSet(),
            mergeStyle = STYLES.entries.firstOrNull { it.value == style }?.key,
        )
    }

    private fun JsonObject.textOrNull(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
        const val SQUASH = "squash_option"

        /** Features, each off, on, or on for members only; in the order they are shown. */
        val LEVELS = linkedMapOf(
            RepoSwitch.ISSUES to "issues_access_level",
            RepoSwitch.PULL_REQUESTS to "merge_requests_access_level",
            RepoSwitch.WIKI to "wiki_access_level",
            RepoSwitch.CI to "builds_access_level",
            RepoSwitch.RELEASES to "releases_access_level",
        )

        val FLAGS = linkedMapOf(
            RepoSwitch.DELETE_BRANCH_ON_MERGE to "remove_source_branch_after_merge",
            RepoSwitch.REQUIRE_PIPELINE to "only_allow_merge_if_pipeline_succeeds",
            RepoSwitch.REQUIRE_RESOLVED_THREADS to "only_allow_merge_if_all_discussions_are_resolved",
        )

        val STYLES = mapOf(MergeStyle.MERGE_COMMIT to "merge", MergeStyle.SEMI_LINEAR to "rebase_merge", MergeStyle.FAST_FORWARD to "ff")
    }
}
