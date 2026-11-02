package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedFeedApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedIssueApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedNotificationsApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedPullRequestApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedRepoSettingsApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedSearchApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedStarApi
import io.ktor.client.HttpClient

/**
 * Tangled's APIs, on [http]: read-only and without an account. Repositories (README, files, branches, tags), people and
 * Trending come from Tangled's index; the rest answers Unsupported until Forgeline signs in with AT Protocol OAuth
 * (docs/TANGLED.md says what that takes).
 */
class TangledApis(http: HttpClient) : ForgeApis {
    private val identity = TangledIdentity(http)

    override val repos = TangledRepoApi(http, identity)
    override val users = TangledUserApi(http, identity)
    override val trending = TangledTrendingApi(http, identity)
    override val issues = UnsupportedIssueApi()
    override val pulls = UnsupportedPullRequestApi()
    override val repoSettings = UnsupportedRepoSettingsApi()
    override val stars = UnsupportedStarApi()
    override val search = UnsupportedSearchApi()
    override val feed = UnsupportedFeedApi()
    override val notifications = UnsupportedNotificationsApi()
    override val auth: ForgeAuthApi = TangledAuthApi()
    override val actions: ActionsApi? = null
    override val trendingMeter: TrendingMeter? = null
}

/** No sign-in yet: Tangled signs in with AT Protocol OAuth, which Forgeline doesn't speak yet. */
class TangledAuthApi : ForgeAuthApi {
    override val forge: ForgeInstance = ForgeInstance.Tangled
    override val personalAccessTokenUrl: String = "${forge.webUrl}/settings"
    override val supportsDeviceFlow: Boolean = false

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> = ForgeResult.Failure(ForgeError.Unsupported)
}
