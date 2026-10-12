package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.model.PagesBuild
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import io.ktor.http.isSuccess
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
        // Deleting has a scope of its own, which older sign-ins weren't given. GitHub doesn't say which scope a refused
        // deletion wanted (its X-Accepted-OAuth-Scopes comes empty, seen 2026-10-12), only which ones the token has;
        // a token that lists none (a fine-grained one) is refused for another reason.
        val scopes = response.headers["X-OAuth-Scopes"]?.split(',')?.map { it.trim() }
        if (response.status == HttpStatusCode.Forbidden && scopes != null && DELETE_SCOPE !in scopes) {
            return@gitHubCall ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unauthorized)
        }
        response.toResult { RepoDeletion.DELETED }
    }

    /** GitHub answers 404 for a repository that publishes nothing: that is a site switched off, not a failure. */
    override suspend fun pages(token: String, repo: RepoId): ForgeResult<PagesSite?> = gitHubCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "pages")
        if (response.status == HttpStatusCode.NotFound) ForgeResult.Success(PagesSite(published = false, canPublish = true)) else response.toResult { body<JsonObject>().toSite() }
    }

    override suspend fun changePages(token: String, repo: RepoId, change: PagesChange): ForgeResult<PagesSite> = gitHubCall {
        suspend fun send(method: HttpMethod, body: JsonObject? = null) = httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "pages", method = method, body = body)
        val response = when (change) {
            is PagesChange.Publish -> {
                val current = send(HttpMethod.Get)
                val body = buildJsonObject {
                    when (val build = change.build) {
                        PagesBuild.Workflow -> put("build_type", "workflow")
                        is PagesBuild.Branch -> {
                            put("build_type", "legacy")
                            put("source", buildJsonObject {
                                put("branch", build.branch)
                                put("path", build.folder)
                            })
                        }
                    }
                }
                // A site is created once, then changed.
                send(if (current.status == HttpStatusCode.NotFound) HttpMethod.Post else HttpMethod.Put, body)
            }
            is PagesChange.Https -> send(HttpMethod.Put, buildJsonObject { put("https_enforced", change.enforced) })
            is PagesChange.Domain -> send(HttpMethod.Put, buildJsonObject { put("cname", change.domain.ifBlank { null }) })
            PagesChange.Unpublish -> send(HttpMethod.Delete)
        }
        if (!response.status.isSuccess()) return@gitHubCall response.failure()
        // Changes answer nothing: the site is read as it now is.
        when (val site = pages(token, repo)) {
            is ForgeResult.Success -> ForgeResult.Success(site.value ?: PagesSite(published = false, canPublish = true))
            is ForgeResult.Failure -> site
        }
    }

    private fun JsonObject.toSite(): PagesSite {
        val source = this["source"] as? JsonObject
        return PagesSite(
            published = true,
            url = text("html_url").ifEmpty { null },
            canPublish = true,
            build = if (text("build_type") == "workflow") PagesBuild.Workflow else source?.let { PagesBuild.Branch(it.text("branch"), it.text("path").ifEmpty { "/" }) },
            httpsEnforced = (this["https_enforced"] as? JsonPrimitive)?.booleanOrNull ?: false,
            customDomain = text("cname"),
        )
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
