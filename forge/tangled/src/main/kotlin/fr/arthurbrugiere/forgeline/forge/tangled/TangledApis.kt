package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedNotificationsApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedRepoSettingsApi
import io.ktor.client.HttpClient

/**
 * Tangled's APIs, on [http]. Repositories, their issues and pull requests, people, search, the Feed and Trending are
 * read from Tangled's index (and a repository's git and pipelines from its knot and spindle) without an account, but
 * for the Feed, which the index answers only to the one it is for (signed for by their own server);
 * starring and following write records to the signed-in person's own AT Protocol server ([TangledAccount]), and
 * issues, pull requests and comments to the repository's knot ([TangledKnot]). The rest answers Unsupported
 * (docs/TANGLED.md says why).
 */
class TangledApis(http: HttpClient) : ForgeApis {
    private val xrpc = TangledXrpc(http)
    private val identity = TangledIdentity(http, xrpc)
    private val account = TangledAccount(xrpc, identity)

    private val knot = TangledKnot(xrpc, account, identity)

    override val repos = TangledRepoApi(xrpc, identity)
    override val users = TangledUserApi(xrpc, identity, TangledFollows(xrpc, account, identity))
    override val trending = TangledTrendingApi(xrpc, identity)
    override val issues = TangledIssueApi(xrpc, account, knot, repos.conversations)
    override val pulls = TangledPullRequestApi(xrpc, knot, repos.conversations, issues)
    override val repoSettings = UnsupportedRepoSettingsApi()
    override val ownerSettings = TangledOwnerSettingsApi(account)
    override val stars = TangledStarApi(xrpc, account, repos)
    override val search = TangledSearchApi(xrpc, identity, repos.conversations)
    override val feed = TangledFeedApi(xrpc, identity, account, repos.conversations)
    override val notifications = UnsupportedNotificationsApi()
    override val auth: ForgeAuthApi = TangledAuthApi(account)
    override val actions: ActionsApi? = null
    override val trendingMeter: TrendingMeter? = null
}

/**
 * Signing in to Tangled with the account's handle and an app password: an AT Protocol session with the account's own
 * server, renewed with its refresh token. Tangled's web app signs in with AT Protocol OAuth instead, which asks a
 * native app for a client metadata document served from a web address of its own, and DPoP-bound tokens.
 */
class TangledAuthApi internal constructor(private val account: TangledAccount) : ForgeAuthApi {
    override val forge: ForgeInstance = ForgeInstance.Tangled

    /** Where Bluesky accounts, which most people sign in to Tangled with, make app passwords. */
    override val personalAccessTokenUrl: String = "https://bsky.app/settings/app-passwords"
    override val supportsDeviceFlow: Boolean = false
    override val supportsPasswordSignIn: Boolean = true

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun signInWithPassword(identifier: String, password: String): ForgeResult<OAuthTokens> =
        account.createSession(identifier, password)

    override suspend fun refresh(refreshToken: String): ForgeResult<OAuthTokens> = account.refreshSession(refreshToken)

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> = account.user(token)
}
