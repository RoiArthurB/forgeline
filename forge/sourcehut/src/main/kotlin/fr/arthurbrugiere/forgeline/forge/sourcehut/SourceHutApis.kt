package fr.arthurbrugiere.forgeline.forge.sourcehut

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
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
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.sourcehut.graphql.MeQuery
import io.ktor.client.HttpClient

/**
 * SourceHut's APIs, on [http]: git.sr.ht's repositories and people, and todo.sr.ht's tickets, with a personal access
 * token. Patches, builds and the rest are other services, or things SourceHut doesn't do (stars, a feed,
 * notifications): Unsupported, as docs/SOURCEHUT.md says.
 */
class SourceHutApis(http: HttpClient) : ForgeApis {
    override val issues = SourceHutIssueApi(http)
    override val repos = SourceHutRepoApi(http, tickets = issues)
    override val users = SourceHutUserApi(http)
    override val auth: ForgeAuthApi = SourceHutAuthApi(http)
    override val pulls = UnsupportedPullRequestApi()
    override val repoSettings = UnsupportedRepoSettingsApi()
    override val stars = UnsupportedStarApi()
    override val search = UnsupportedSearchApi()
    override val feed = UnsupportedFeedApi()
    override val notifications = UnsupportedNotificationsApi()
    override val actions: ActionsApi? = null
    override val trending: TrendingApi? = null
    override val trendingMeter: TrendingMeter? = null
}

/**
 * Signing in with a personal access token made on meta.sr.ht. SourceHut's OAuth 2.0 needs a client registered with a
 * secret, which an app without a server can't keep, and it has no device flow.
 */
class SourceHutAuthApi(private val httpClient: HttpClient) : ForgeAuthApi {
    override val forge: ForgeInstance = ForgeInstance.SourceHut
    override val personalAccessTokenUrl: String = "https://meta.sr.ht/oauth2/personal-token"
    override val supportsDeviceFlow: Boolean = false

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> = forgeCall {
        when (val result = httpClient.srht(token, MeQuery())) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> ForgeResult.Success(ForgeUser(result.value.me.canonicalName, null, null))
        }
    }
}
