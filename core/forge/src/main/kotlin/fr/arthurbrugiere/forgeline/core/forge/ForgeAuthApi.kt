package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser

/** OAuth 2.0 device authorization grant (RFC 8628): no client secret, no redirect, no server. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

sealed interface DeviceTokenPoll {
    data class Authorized(val token: String) : DeviceTokenPoll

    data object Pending : DeviceTokenPoll

    data class SlowDown(val intervalSeconds: Int) : DeviceTokenPoll

    data object Expired : DeviceTokenPoll

    data object Denied : DeviceTokenPoll
}

interface ForgeAuthApi {
    val forge: ForgeInstance

    /** Where users create a personal access token with the scopes Forgeline needs. */
    val personalAccessTokenUrl: String

    val supportsDeviceFlow: Boolean

    suspend fun requestDeviceCode(): ForgeResult<DeviceCode>

    suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll>

    suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser>

    /**
     * Whether [token] reaches the account's private repositories; null when the forge doesn't say. A sign-in that
     * doesn't shows them as missing and can't act on them, until it is renewed.
     */
    suspend fun reachesPrivateRepositories(token: String): ForgeResult<Boolean?> = ForgeResult.Success(null)

    /**
     * Whether "Sign in with <forge>" goes through the browser: OAuth 2.0 authorization code with PKCE (RFC 7636), for
     * forges without a device flow. The code comes back to a loopback redirect (RFC 8252), since forges like Forgejo
     * only accept http(s) redirect URIs.
     */
    val supportsBrowserSignIn: Boolean get() = false

    /** The page where the person approves Forgeline; it redirects to [redirectUri] with a code and [state]. */
    fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String): String =
        throw UnsupportedOperationException("${forge.host} has no browser sign-in")

    suspend fun exchangeCode(code: String, redirectUri: String, codeVerifier: String): ForgeResult<OAuthTokens> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** A new access token for an expired one. */
    suspend fun refresh(refreshToken: String): ForgeResult<OAuthTokens> = ForgeResult.Failure(ForgeError.Unsupported)
}

/** What a browser sign-in yields; [expiresInSeconds] null when the token doesn't expire. */
data class OAuthTokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long?)
