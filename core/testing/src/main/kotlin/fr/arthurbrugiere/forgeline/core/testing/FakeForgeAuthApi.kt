package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser

/** Scriptable auth API: queue poll outcomes, map tokens to users. */
class FakeForgeAuthApi(
    override val supportsDeviceFlow: Boolean = true,
    var deviceCodeResult: ForgeResult<DeviceCode> = ForgeResult.Success(
        DeviceCode("device-123", "ABCD-1234", "https://github.com/login/device", expiresInSeconds = 900, intervalSeconds = 5),
    ),
) : ForgeAuthApi {
    override val forge: ForgeInstance = ForgeInstance.GitHub
    override val personalAccessTokenUrl: String = "https://github.com/settings/tokens/new"

    val users = mutableMapOf<String, ForgeUser>()
    val pollResults = ArrayDeque<ForgeResult<DeviceTokenPoll>>()
    var pollCount = 0
        private set
    var userFailure: ForgeError? = null

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = deviceCodeResult

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> {
        pollCount++
        return pollResults.removeFirstOrNull() ?: ForgeResult.Success(DeviceTokenPoll.Pending)
    }

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> {
        userFailure?.let { return ForgeResult.Failure(it) }
        return users[token]?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unauthorized)
    }
}
