package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import kotlinx.coroutines.awaitAll
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.ProfileLink
import fr.arthurbrugiere.forgeline.core.model.distinctPlaces
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

class GitHubUserApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : UserApi {

    /** The profile, and beside it the social accounts the person listed (organisations have none to list). */
    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = forgeCall {
        coroutineScope {
            val social = async {
                runCatching {
                    httpClient.gitHubApi(apiBaseUrl, token, "users", login, "social_accounts")
                        .takeIf { it.status == HttpStatusCode.OK }?.body<List<SocialAccountJson>>()
                }.getOrNull().orEmpty()
            }
            httpClient.gitHubApi(apiBaseUrl, token, "users", login).toResult {
                val profile = body<ProfileJson>()
                profile.toModel().copy(links = profile.links(social.await()))
            }
        }
    }

    /**
     * GitHub's list of someone's repositories holds only the public ones, even for that person. One's own come from
     * `/user/repos`, an organisation's from `/orgs/…/repos`, which add the private ones the token may see.
     */
    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = forgeCall {
        val query = mapOf("sort" to "updated", "per_page" to "30")
        val response = when {
            mine && token != null -> httpClient.gitHubApi(apiBaseUrl, token, "user", "repos", query = query + ("affiliation" to "owner"))
            organization -> httpClient.gitHubApi(apiBaseUrl, token, "orgs", login, "repos", query = query + ("type" to "all"))
            else -> httpClient.gitHubApi(apiBaseUrl, token, "users", login, "repos", query = query)
        }
        response.toResult { body<List<RepoJson>>().map { it.toModel() } }
    }

    /** Own, organisations' and shared repositories, by last push; the pages after the first are asked all at once. */
    override suspend fun reachableRepos(token: String): ForgeResult<List<RepoSummary>> = forgeCall {
        val query = mapOf("affiliation" to "owner,collaborator,organization_member", "sort" to "pushed", "per_page" to "100")
        val first = httpClient.gitHubApi(apiBaseUrl, token, "user", "repos", query = query + ("page" to "1"))
        if (first.status != HttpStatusCode.OK) return@forgeCall first.failure()
        val last = (first.lastPage() ?: 1).coerceAtMost(MAX_REACHABLE_PAGES)
        val rest = coroutineScope {
            (2..last).map { page ->
                async {
                    // A later page that fails only loses its own repositories.
                    runCatching {
                        httpClient.gitHubApi(apiBaseUrl, token, "user", "repos", query = query + ("page" to "$page"))
                            .takeIf { it.status == HttpStatusCode.OK }?.body<List<RepoJson>>()
                    }.getOrNull().orEmpty()
                }
            }.awaitAll().flatten()
        }
        ForgeResult.Success((first.body<List<RepoJson>>() + rest).map { it.toModel() }.distinctBy { it.id })
    }

    override suspend fun organizations(token: String): ForgeResult<List<UserSummary>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "user", "orgs", query = mapOf("per_page" to "100"))
            .toResult { body<List<OrgJson>>().map { UserSummary(it.login, it.avatarUrl, isOrganization = true, forge = ForgeInstance.GitHub) } }
    }

    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "users", login, "starred", query = mapOf("per_page" to "30"))
            .toResult { body<List<RepoJson>>().map { it.toModel() } }
    }

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = forgeCall {
        // Answered by status code alone: 204 follows, 404 doesn't.
        val response = httpClient.gitHubApi(apiBaseUrl, token, "user", "following", login)
        when (response.status) {
            HttpStatusCode.NoContent -> ForgeResult.Success(true)
            HttpStatusCode.NotFound -> ForgeResult.Success(false)
            else -> response.failure()
        }
    }

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "user", "following", login, method = if (follow) HttpMethod.Put else HttpMethod.Delete)
            .toResult { }
    }
}

@Serializable
private data class OrgJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null)

/** Ten pages of a hundred: past a thousand repositories, those left alone longest are left out. */
private const val MAX_REACHABLE_PAGES = 10

@Serializable
private data class SocialAccountJson(val provider: String = "generic", val url: String)

@Serializable
private data class ProfileJson(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val blog: String? = null,
    val followers: Int = 0,
    val following: Int = 0,
    @SerialName("public_repos") val publicRepos: Int = 0,
    val type: String = "User",
    @SerialName("created_at") val createdAt: String? = null,
    val email: String? = null,
    @SerialName("twitter_username") val twitterUsername: String? = null,
) {
    /** The public email, the social accounts, then the X name GitHub keeps apart (once, if also listed). */
    fun links(social: List<SocialAccountJson>): List<ProfileLink> = (
        listOfNotNull(ProfileLink.email(email)) +
            social.mapNotNull { ProfileLink.of(it.url, mastodon = it.provider == "mastodon") } +
            listOfNotNull(twitterUsername?.ifBlank { null }?.let { ProfileLink.of("https://x.com/$it") })
        ).distinctPlaces()

    fun toModel() = UserProfile(
        login = login,
        name = name?.ifBlank { null },
        avatarUrl = avatarUrl,
        bio = bio?.ifBlank { null },
        company = company?.ifBlank { null },
        location = location?.ifBlank { null },
        website = blog?.ifBlank { null },
        followers = followers,
        following = following,
        publicRepos = publicRepos,
        isOrganization = type == "Organization",
        createdAt = createdAt?.let(Instant::parse),
    )
}

@Serializable
private data class RepoJsonOwner(@SerialName("avatar_url") val avatarUrl: String? = null, val type: String? = null)

@Serializable
private data class RepoJson(
    @SerialName("full_name") val fullName: String,
    val owner: RepoJsonOwner? = null,
    val description: String? = null,
    val language: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    val fork: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    val private: Boolean = false,
    @SerialName("pushed_at") val pushedAt: String? = null,
) {
    fun toModel(): RepoSummary {
        val (owner, name) = fullName.split('/', limit = 2)
        return RepoSummary(RepoId(owner, name, ForgeInstance.GitHub), description, language, stars, forks, fork, updatedAt?.let(Instant::parse), this.owner?.avatarUrl, private, pushedAt?.let(Instant::parse), this.owner?.type?.let { it == "Organization" })
    }
}
