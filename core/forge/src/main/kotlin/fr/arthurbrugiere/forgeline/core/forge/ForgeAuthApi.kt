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
}
