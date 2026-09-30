package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Signing in to a Forgejo instance: an access token, or (with a registered OAuth application's [clientId]) the browser
 * with PKCE. Forgejo has no device flow: Codeberg's OpenID configuration lists only `authorization_code` and
 * `refresh_token` (checked 2026-09-29).
 */
class ForgejoAuthApi(
    private val httpClient: HttpClient,
    override val forge: ForgeInstance,
    private val clientId: String = "",
) : ForgeAuthApi {

    override val personalAccessTokenUrl: String = "${forge.webUrl}/user/settings/applications"

    override val supportsDeviceFlow: Boolean = false

    override val supportsBrowserSignIn: Boolean = clientId.isNotBlank()

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> = forgejoCall {
        httpClient.forgejoApi(forge, token, "user").toResult { body<UserJson>().toModel() }
    }

    override fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String): String =
        URLBuilder("${forge.webUrl}/login/oauth/authorize").apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

    override suspend fun exchangeCode(code: String, redirectUri: String, codeVerifier: String): ForgeResult<OAuthTokens> = token(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to redirectUri,
        "code_verifier" to codeVerifier,
    )

    override suspend fun refresh(refreshToken: String): ForgeResult<OAuthTokens> = token(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
    )

    private suspend fun token(vararg fields: Pair<String, String>): ForgeResult<OAuthTokens> = forgejoCall {
        httpClient.submitForm(
            url = "${forge.webUrl}/login/oauth/access_token",
            formParameters = parameters {
                append("client_id", clientId)
                fields.forEach { (key, value) -> append(key, value) }
            },
        ) { accept(ContentType.Application.Json) }
            .toResult { body<TokenJson>().let { OAuthTokens(it.accessToken, it.refreshToken, it.expiresIn) } }
    }
}

@Serializable
private data class TokenJson(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)
