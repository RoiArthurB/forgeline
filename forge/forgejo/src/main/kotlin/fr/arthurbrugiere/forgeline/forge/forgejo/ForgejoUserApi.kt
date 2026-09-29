package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ForgejoUserApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : UserApi {

    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgejoCall {
        coroutineScope {
            // Organizations answer /users too, looking like people; /orgs tells them apart. Neither counts repositories.
            val org = async { httpClient.forgejoApi(forge, token, "orgs", login).status == HttpStatusCode.OK }
            val repos = async { httpClient.forgejoApi(forge, token, "users", login, "repos", query = mapOf("limit" to "1")).totalCount() }
            val response = httpClient.forgejoApi(forge, token, "users", login)
            if (response.status != HttpStatusCode.OK) return@coroutineScope response.failure()
            val user = response.body<ProfileJson>()
            ForgeResult.Success(
                UserProfile(
                    login = user.login,
                    name = user.fullName?.ifBlank { null },
                    avatarUrl = user.avatarUrl,
                    bio = user.description?.ifBlank { null },
                    company = null,
                    location = user.location?.ifBlank { null },
                    website = user.website?.ifBlank { null },
                    followers = user.followers,
                    following = user.following,
                    publicRepos = repos.await() ?: 0,
                    isOrganization = org.await(),
                    createdAt = instant(user.created),
                ),
            )
        }
    }

    override suspend fun repos(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgejoCall {
        httpClient.forgejoApi(forge, token, "users", login, "repos", query = mapOf("limit" to "30"))
            .toResult { body<List<RepoJson>>().map { it.toSummary(forge) }.sortedByDescending { it.updatedAt } }
    }

    /** Codeberg only shows a person's stars to signed-in callers (401 anonymously, 2026-09-29). */
    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgejoCall {
        httpClient.forgejoApi(forge, token, "users", login, "starred", query = mapOf("limit" to "30"))
            .toResult { body<List<RepoJson>>().map { it.toSummary(forge) } }
    }

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = forgejoCall {
        // Answered by status code alone: 204 follows, 404 doesn't.
        val response = httpClient.forgejoApi(forge, token, "user", "following", login)
        when (response.status) {
            HttpStatusCode.NoContent -> ForgeResult.Success(true)
            HttpStatusCode.NotFound -> ForgeResult.Success(false)
            else -> response.failure()
        }
    }

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(forge, token, "user", "following", login, method = if (follow) HttpMethod.Put else HttpMethod.Delete).toResult { }
    }
}

class ForgejoStarApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : StarApi {

    /** One request per repository (Forgejo has no batch lookup), a few at a time. */
    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> = forgejoCall {
        val gate = Semaphore(CONCURRENCY)
        val answers = coroutineScope {
            repos.map { repo ->
                async {
                    gate.withPermit {
                        val response = httpClient.forgejoApi(forge, token, "user", "starred", repo.owner, repo.name)
                        when (response.status) {
                            HttpStatusCode.NoContent -> repo to true
                            HttpStatusCode.NotFound -> repo to false
                            else -> null
                        }
                    }
                }
            }.awaitAll()
        }
        ForgeResult.Success(answers.filterNotNull().toMap())
    }

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(forge, token, "user", "starred", repo.owner, repo.name, method = if (starred) HttpMethod.Put else HttpMethod.Delete)
            .toResult { }
    }

    private companion object {
        const val CONCURRENCY = 4
    }
}

class ForgejoSearchApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : SearchApi {

    override suspend fun repositories(token: String?, query: String, page: Int): ForgeResult<SearchPage<RepoSummary>> = forgejoCall {
        val response = httpClient.forgejoApi(forge, token, "repos", "search", query = params(query, page))
        response.toResult {
            val items = body<SearchJson<RepoJson>>().data.map { it.toSummary(forge) }
            SearchPage(items, totalCount() ?: items.size, nextPage())
        }
    }

    /** Codeberg answered 500 to anonymous issue searches (2026-09-29), so this needs an account there. */
    override suspend fun issues(token: String?, query: String, page: Int): ForgeResult<SearchPage<IssueSearchResult>> = forgejoCall {
        val response = httpClient.forgejoApi(forge, token, "repos", "issues", "search", query = params(query, page))
        response.toResult {
            val items = body<List<IssueJson>>().mapNotNull { issue ->
                issue.repository?.let { IssueSearchResult(RepoId(it.owner, it.name, forge), issue.toSummary()) }
            }
            SearchPage(items, totalCount() ?: items.size, nextPage())
        }
    }

    override suspend fun users(token: String?, query: String, page: Int): ForgeResult<SearchPage<UserSummary>> = forgejoCall {
        val response = httpClient.forgejoApi(forge, token, "users", "search", query = params(query, page))
        response.toResult {
            val items = body<SearchJson<UserJson>>().data.map { it.toSummary() }
            SearchPage(items, totalCount() ?: items.size, nextPage())
        }
    }

    private fun params(query: String, page: Int) = mapOf("q" to query, "page" to page.toString(), "limit" to "$PAGE_SIZE")

    private companion object {
        const val PAGE_SIZE = 30
    }
}

@Serializable
private data class ProfileJson(
    val login: String,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val description: String? = null,
    val location: String? = null,
    val website: String? = null,
    @SerialName("followers_count") val followers: Int = 0,
    @SerialName("following_count") val following: Int = 0,
    val created: String? = null,
)
