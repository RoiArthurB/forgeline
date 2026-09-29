package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
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

    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "users", login).toResult { body<ProfileJson>().toModel() }
    }

    override suspend fun repos(token: String?, login: String): ForgeResult<List<RepoSummary>> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "users", login, "repos", query = mapOf("sort" to "updated", "per_page" to "30"))
            .toResult { body<List<RepoJson>>().map { it.toModel() } }
    }

    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "users", login, "starred", query = mapOf("per_page" to "30"))
            .toResult { body<List<RepoJson>>().map { it.toModel() } }
    }

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = gitHubCall {
        // Answered by status code alone: 204 follows, 404 doesn't.
        val response = httpClient.gitHubApi(apiBaseUrl, token, "user", "following", login)
        when (response.status) {
            HttpStatusCode.NoContent -> ForgeResult.Success(true)
            HttpStatusCode.NotFound -> ForgeResult.Success(false)
            else -> response.failure()
        }
    }

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "user", "following", login, method = if (follow) HttpMethod.Put else HttpMethod.Delete)
            .toResult { }
    }
}

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
) {
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
private data class RepoJsonOwner(@SerialName("avatar_url") val avatarUrl: String? = null)

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
) {
    fun toModel(): RepoSummary {
        val (owner, name) = fullName.split('/', limit = 2)
        return RepoSummary(RepoId(owner, name), description, language, stars, forks, fork, updatedAt?.let(Instant::parse), this.owner?.avatarUrl)
    }
}
