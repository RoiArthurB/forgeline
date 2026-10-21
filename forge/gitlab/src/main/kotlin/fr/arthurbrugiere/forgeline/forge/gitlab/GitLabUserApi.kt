package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class GitLabUserApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : UserApi {

    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        // Try looking up a user first
        val userRes = httpClient.gitlabApi(forge, token, "users", query = mapOf("username" to login))
        if (userRes.status.isSuccess()) {
            val users = userRes.body<List<GitLabUserJson>>()
            val user = users.firstOrNull { it.username.equals(login, ignoreCase = true) }
            if (user != null) {
                // A lookup by username answers only the name and avatar: the bio, website and links come with the id.
                return@forgeCall coroutineScope {
                    val full = async {
                        runCatching {
                            httpClient.gitlabApi(forge, token, "users", user.id.toString()).takeIf { it.status.isSuccess() }?.body<GitLabUserJson>()
                        }.getOrNull()
                    }
                    val reposRes = httpClient.gitlabApi(forge, token, "users", user.id.toString(), "projects", query = mapOf("per_page" to "1"))
                    val reposCount = reposRes.totalCount() ?: 0
                    ForgeResult.Success((full.await() ?: user).toProfile(reposCount))
                }
            }
        }

        // Fall back to looking up a group
        val groupRes = httpClient.gitlabApi(forge, token, "groups", login)
        if (groupRes.status.isSuccess()) {
            val group = groupRes.body<GitLabGroupJson>()
            val reposRes = httpClient.gitlabApi(forge, token, "groups", login, "projects", query = mapOf("per_page" to "1"))
            val reposCount = reposRes.totalCount() ?: 0
            return@forgeCall ForgeResult.Success(group.toProfile(reposCount))
        }

        ForgeResult.Failure(ForgeError.Http(404, "User or group $login not found"))
    }

    /** GitLab lists every project the token may see, private ones included, for a person and for a group. */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        val userProjectsRes = httpClient.gitlabApi(
            forge, token, "users", login, "projects",
            query = mapOf("order_by" to "updated_at", "sort" to "desc", "per_page" to "30"),
        )
        if (userProjectsRes.status.isSuccess()) {
            return@forgeCall ForgeResult.Success(userProjectsRes.body<List<GitLabProjectJson>>().map { it.toSummary(forge) })
        }

        // If not a user, try group projects
        val groupProjectsRes = httpClient.gitlabApi(
            forge, token, "groups", login, "projects",
            query = mapOf("order_by" to "updated_at", "sort" to "desc", "per_page" to "30"),
        )
        groupProjectsRes.toResult { body<List<GitLabProjectJson>>().map { it.toSummary(forge) } }
    }

    /** Every project one is a member of, by last activity; the pages after the first are asked all at once. */
    override suspend fun reachableRepos(token: String): ForgeResult<List<RepoSummary>> = forgeCall {
        val query = mapOf("membership" to "true", "order_by" to "last_activity_at", "sort" to "desc", "per_page" to "100")
        val first = httpClient.gitlabApi(forge, token, "projects", query = query + ("page" to "1"))
        if (!first.status.isSuccess()) return@forgeCall first.failure()
        val last = (first.lastPage() ?: 1).coerceAtMost(MAX_REACHABLE_PAGES)
        val rest = coroutineScope {
            (2..last).map { page ->
                async {
                    runCatching {
                        httpClient.gitlabApi(forge, token, "projects", query = query + ("page" to "$page"))
                            .takeIf { it.status.isSuccess() }?.body<List<GitLabProjectJson>>()
                    }.getOrNull().orEmpty()
                }
            }.awaitAll().flatten()
        }
        ForgeResult.Success((first.body<List<GitLabProjectJson>>() + rest).map { it.toSummary(forge) }.distinctBy { it.id })
    }

    override suspend fun organizations(token: String): ForgeResult<List<UserSummary>> = forgeCall {
        httpClient.gitlabApi(forge, token, "groups", query = mapOf("min_access_level" to "10", "per_page" to "100"))
            .toResult { body<List<GitLabGroupJson>>().map { it.toSummary(forge) } }
    }

    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgeCall {
        val userRes = httpClient.gitlabApi(forge, token, "users", query = mapOf("username" to login))
        if (!userRes.status.isSuccess()) return@forgeCall ForgeResult.Success(emptyList())

        val users = userRes.body<List<GitLabUserJson>>()
        val user = users.firstOrNull { it.username.equals(login, ignoreCase = true) }
            ?: return@forgeCall ForgeResult.Success(emptyList())

        httpClient.gitlabApi(forge, token, "users", user.id.toString(), "starred_projects", query = mapOf("per_page" to "30"))
            .toResult { body<List<GitLabProjectJson>>().map { it.toSummary(forge) } }
    }

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = forgeCall {
        val res = httpClient.gitlabApi(forge, token, "user", "following", query = mapOf("per_page" to "100"))
        if (!res.status.isSuccess()) return@forgeCall ForgeResult.Success(false)
        val following = res.body<List<GitLabUserJson>>()
        ForgeResult.Success(following.any { it.username.equals(login, ignoreCase = true) })
    }

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = forgeCall {
        val userRes = httpClient.gitlabApi(forge, token, "users", query = mapOf("username" to login))
        if (!userRes.status.isSuccess()) return@forgeCall userRes.failure()

        val user = userRes.body<List<GitLabUserJson>>().firstOrNull { it.username.equals(login, ignoreCase = true) }
            ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, "User $login not found"))

        val action = if (follow) "follow" else "unfollow"
        val res = httpClient.gitlabApi(forge, token, "users", user.id.toString(), action, method = HttpMethod.Post)
        if (res.status.isSuccess()) ForgeResult.Success(Unit) else res.failure()
    }
}

class GitLabStarApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : StarApi {

    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> = forgeCall {
        if (repos.isEmpty()) return@forgeCall ForgeResult.Success(emptyMap())
        // One list of what the reader starred answers for every repository asked about, however many.
        val starred = mutableSetOf<String>()
        var page: Int? = 1
        while (page != null && page <= MAX_PAGES) {
            val response = httpClient.gitlabApi(
                forge, token, "projects",
                query = mapOf("starred" to "true", "simple" to "true", "per_page" to "100", "page" to page.toString()),
            )
            if (!response.status.isSuccess()) return@forgeCall response.failure()
            response.body<List<GitLabProjectJson>>().mapTo(starred) { it.pathWithNamespace.lowercase() }
            page = response.nextPage()
        }
        ForgeResult.Success(repos.distinct().associateWith { it.fullName.lowercase() in starred })
    }

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> = forgeCall {
        val action = if (starred) "star" else "unstar"
        val response = httpClient.gitlabApi(repo.forge, token, "projects", repo.fullName, action, method = HttpMethod.Post)
        if (response.status.isSuccess() || response.status == HttpStatusCode.NotModified) {
            ForgeResult.Success(Unit)
        } else {
            response.failure()
        }
    }

    /**
     * GitLab has no watch of its own: a project is watched by setting what it notifies of to "watch". Anything else
     * (the account's general setting, "participating", "mention") is not watching.
     */
    override suspend fun isWatching(token: String, repo: RepoId): ForgeResult<Boolean> = forgeCall {
        httpClient.gitlabApi(repo.forge, token, "projects", repo.fullName, "notification_settings")
            .toResult { body<GitLabNotificationLevelJson>().level == WATCH }
    }

    /** No longer watching goes back to the account's general setting, as if the project had never been set. */
    override suspend fun setWatching(token: String, repo: RepoId, watching: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.gitlabApi(
            repo.forge, token, "projects", repo.fullName, "notification_settings",
            method = HttpMethod.Put, body = buildJsonObject { put("level", if (watching) WATCH else "global") },
        ).toResult { }
    }

    override suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> = forgeCall {
        httpClient.gitlabApi(repo.forge, token, "projects", repo.fullName, "fork", method = HttpMethod.Post)
            .toResult { body<GitLabProjectJson>().pathWithNamespace.let { path -> RepoId(path.substringBeforeLast('/'), path.substringAfterLast('/'), repo.forge) } }
    }

    private companion object {
        const val WATCH = "watch"

        /** A thousand stars read at most: past that, a repository further down reads as not starred. */
        const val MAX_PAGES = 10
    }
}

@Serializable
private data class GitLabNotificationLevelJson(val level: String = "")

/** Ten pages of a hundred: past a thousand projects, those left alone longest are left out. */
private const val MAX_REACHABLE_PAGES = 10
