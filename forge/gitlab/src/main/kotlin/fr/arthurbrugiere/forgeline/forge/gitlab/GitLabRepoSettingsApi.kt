package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import io.ktor.http.HttpStatusCode
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
 * A GitLab project's settings: one `PUT` of the project for each. A feature is off, on, or on for the project's
 * members only; a project merges one way; and a project has no website of its own.
 */
class GitLabRepoSettingsApi(private val httpClient: HttpClient, private val forge: ForgeInstance = ForgeInstance.GitLab) : RepoSettingsApi {

    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> = gitlabCall {
        httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName)).toResult { body<JsonObject>().toSettings() }
    }

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> = gitlabCall {
        // Archiving is a call of its own, which answers the project like the others.
        if (change is RepoSettingsChange.Archived) {
            return@gitlabCall httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName), if (change.archived) "archive" else "unarchive", method = HttpMethod.Post)
                .toResult { body<JsonObject>().toSettings() }
        }
        val body = buildJsonObject {
            when (change) {
                is RepoSettingsChange.Description -> put("description", change.text)
                is RepoSettingsChange.Topics -> put("topics", JsonArray(change.topics.map(::JsonPrimitive)))
                is RepoSettingsChange.DefaultBranch -> put("default_branch", change.branch)
                is RepoSettingsChange.Style -> put("merge_method", STYLES.getValue(change.style))
                is RepoSettingsChange.Visibility -> put("visibility", change.visibility.name.lowercase())
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

    /** A project has a name to read and a path to be found at: both become [name], so that its address says what it is called. */
    override suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId> = gitlabCall {
        httpClient.gitlabApi(
            forge, token, "projects", encodePath(repo.fullName), method = HttpMethod.Put,
            body = buildJsonObject {
                put("name", name)
                put("path", name)
            },
        ).toResult {
            val path = body<JsonObject>().textOrNull("path_with_namespace").orEmpty()
            RepoId(path.substringBeforeLast('/'), path.substringAfterLast('/'), repo.forge)
        }
    }

    /**
     * gitlab.com keeps a deleted project a while before it is gone, under another path: it is asked for again by its
     * id, read beforehand, to tell which happened.
     */
    override suspend fun delete(token: String, repo: RepoId): ForgeResult<RepoDeletion> = gitlabCall {
        val project = httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName))
        if (!project.status.isSuccess()) return@gitlabCall project.failure()
        val id = project.body<JsonObject>().textOrNull("id") ?: return@gitlabCall ForgeResult.Failure(ForgeError.Unreadable)
        val deleted = httpClient.gitlabApi(forge, token, "projects", id, method = HttpMethod.Delete)
        if (!deleted.status.isSuccess()) return@gitlabCall deleted.failure()
        val kept = httpClient.gitlabApi(forge, token, "projects", id)
        val scheduled = kept.status.isSuccess() && kept.body<JsonObject>().textOrNull("marked_for_deletion_on") != null
        ForgeResult.Success(if (scheduled) RepoDeletion.SCHEDULED else RepoDeletion.DELETED)
    }

    /**
     * A project's website is published by a job of its CI, not switched on: only what is said of a published one can
     * be set. A project without the feature answers 404, which is no site to set anything about.
     */
    override suspend fun pages(token: String, repo: RepoId): ForgeResult<PagesSite?> = gitlabCall {
        val response = httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName), "pages")
        if (response.status == HttpStatusCode.NotFound) ForgeResult.Success(null) else response.toResult { body<JsonObject>().toSite() }
    }

    override suspend fun changePages(token: String, repo: RepoId, change: PagesChange): ForgeResult<PagesSite> = gitlabCall {
        suspend fun send(method: HttpMethod, body: JsonObject? = null) =
            httpClient.gitlabApi(forge, token, "projects", encodePath(repo.fullName), "pages", method = method, body = body)
        when (change) {
            is PagesChange.Https -> send(HttpMethod.Patch, buildJsonObject { put("pages_https_only", change.enforced) }).toResult { body<JsonObject>().toSite() }
            PagesChange.Unpublish -> {
                val removed = send(HttpMethod.Delete)
                if (!removed.status.isSuccess()) return@gitlabCall removed.failure()
                send(HttpMethod.Get).toResult { body<JsonObject>().toSite() }
            }
            else -> unsupported
        }
    }

    private fun JsonObject.toSite() = PagesSite(
        published = (this["deployments"] as? JsonArray)?.isNotEmpty() == true,
        url = textOrNull("url"),
        httpsEnforced = (this["force_https"] as? JsonPrimitive)?.booleanOrNull,
    )

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
            visibility = RepoVisibility.entries.firstOrNull { it.name.lowercase() == textOrNull("visibility") } ?: RepoVisibility.PRIVATE,
            visibilities = RepoVisibility.entries,
            archived = (this["archived"] as? JsonPrimitive)?.booleanOrNull == true,
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
