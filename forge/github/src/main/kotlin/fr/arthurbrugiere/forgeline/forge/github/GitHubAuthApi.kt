package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class GitHubAuthApi(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val webBaseUrl: String = "https://github.com",
    private val apiBaseUrl: String = "https://api.github.com",
) : ForgeAuthApi {

    override val forge: ForgeInstance = ForgeInstance.GitHub

    override val personalAccessTokenUrl: String =
        "$webBaseUrl/settings/tokens/new?scopes=${SCOPES.joinToString(",")}&description=Forgeline"

    override val supportsDeviceFlow: Boolean = clientId.isNotBlank()

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = gitHubCall {
        val response = httpClient.submitForm(
            url = "$webBaseUrl/login/device/code",
            formParameters = parameters {
                append("client_id", clientId)
                append("scope", SCOPES.joinToString(" "))
            },
        ) { accept(ContentType.Application.Json) }
        response.toResult { body<DeviceCodeResponse>().toModel() }
    }

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> = gitHubCall {
        val response = httpClient.submitForm(
            url = "$webBaseUrl/login/oauth/access_token",
            formParameters = parameters {
                append("client_id", clientId)
                append("device_code", deviceCode)
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            },
        ) { accept(ContentType.Application.Json) }
        if (!response.status.isSuccess()) return@gitHubCall response.failure()
        // GitHub reports pending/denied states as 200 responses with an `error` field.
        val token = response.body<AccessTokenResponse>()
        when {
            token.accessToken != null -> ForgeResult.Success(DeviceTokenPoll.Authorized(token.accessToken))
            token.error == "authorization_pending" -> ForgeResult.Success(DeviceTokenPoll.Pending)
            token.error == "slow_down" -> ForgeResult.Success(DeviceTokenPoll.SlowDown(token.interval ?: 5))
            token.error == "expired_token" -> ForgeResult.Success(DeviceTokenPoll.Expired)
            token.error == "access_denied" -> ForgeResult.Success(DeviceTokenPoll.Denied)
            else -> ForgeResult.Failure(ForgeError.Http(response.status.value, token.errorDescription ?: token.error))
        }
    }

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> = gitHubCall {
        val response = httpClient.get("$apiBaseUrl/user") {
            bearerAuth(token)
            accept(ContentType.parse("application/vnd.github+json"))
            header("X-GitHub-Api-Version", API_VERSION)
        }
        response.toResult { body<UserResponse>().toModel() }
    }

    /** GitHub lists a classic or OAuth token's scopes with every answer. */
    override suspend fun reachesPrivateRepositories(token: String): ForgeResult<Boolean?> = gitHubCall {
        val response = httpClient.get("$apiBaseUrl/user") {
            bearerAuth(token)
            accept(ContentType.parse("application/vnd.github+json"))
            header("X-GitHub-Api-Version", API_VERSION)
        }
        response.toResult { headers["X-OAuth-Scopes"]?.split(',')?.map { it.trim() }?.contains("repo") }
    }

    companion object {
        const val API_VERSION = "2022-11-28"
        /**
         * `repo`, not `public_repo`: private repositories are read and acted on too (conversations, comments, stars,
         * workflows). GitHub has no narrower scope that reads a private repository.
         */
        val SCOPES = listOf("notifications", "read:user", "user:follow", "repo")
    }
}

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
) {
    fun toModel() = DeviceCode(deviceCode, userCode, verificationUri, expiresIn, interval)
}

@Serializable
private data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val interval: Int? = null,
)

@Serializable
private data class UserResponse(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    fun toModel() = ForgeUser(login = login, name = name, avatarUrl = avatarUrl)
}
