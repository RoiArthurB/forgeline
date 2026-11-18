package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Account
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Repository
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedFeedApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedNotificationsApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedStarApi
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bitbucket Cloud's APIs, on [http]: see docs/BITBUCKET.md. What Bitbucket doesn't have (issues and wikis since 2026,
 * stars, follows, a feed, notifications an app can read, a Trending) is Unsupported or empty, and the app says so.
 */
class BitbucketApis(http: HttpClient) : ForgeApis {
    /** Who people are, shared by every client: a nickname seen in one answer can be asked for by another. */
    private val people = BitbucketPeople()

    override val repos = BitbucketRepoApi(http, people)
    override val issues = BitbucketIssueApi(http, people, repos)
    override val pulls = BitbucketPullRequestApi(http, people)
    override val users = BitbucketUserApi(http, people)
    override val search = BitbucketSearchApi(http, people, users)
    override val actions = BitbucketActionsApi(http, people)
    override val repoSettings: RepoSettingsApi = BitbucketRepoSettingsApi(http)
    override val stars = BitbucketStarApi(http)
    override val auth: ForgeAuthApi = BitbucketAuthApi(http, people)
    override val feed = UnsupportedFeedApi()
    override val notifications = UnsupportedNotificationsApi()

    /** Bitbucket has no stars to measure a Trending by, nor a list of public repositories to measure: see issue #59. */
    override val trending: TrendingApi? = null
    override val trendingMeter: TrendingMeter? = null
}

/**
 * Signing in to Bitbucket with an Atlassian account's email and an API token made for Forgeline, with Bitbucket's
 * scopes (https://id.atlassian.com/manage-profile/security/api-tokens): what Atlassian has people use since app
 * passwords were retired (2026). Its OAuth needs a client secret, which an app without a server can't keep, and it
 * has no device flow. The token doesn't expire unless its owner gave it an end, and isn't renewed.
 */
class BitbucketAuthApi(private val http: HttpClient, private val people: BitbucketPeople = BitbucketPeople()) : ForgeAuthApi {
    override val forge: ForgeInstance = ForgeInstance.Bitbucket
    override val personalAccessTokenUrl: String = "https://id.atlassian.com/manage-profile/security/api-tokens"
    override val supportsDeviceFlow: Boolean = false
    override val supportsPasswordSignIn: Boolean = true

    override suspend fun requestDeviceCode(): ForgeResult<DeviceCode> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun pollDeviceToken(deviceCode: String): ForgeResult<DeviceTokenPoll> = ForgeResult.Failure(ForgeError.Unsupported)

    /** [identifier] is the account's email, [password] the API token; refused ones answer Unauthorized. */
    override suspend fun signInWithPassword(identifier: String, password: String): ForgeResult<OAuthTokens> {
        val credential = bitbucketBasicCredential(identifier.trim(), password.trim())
        return fetchAuthenticatedUser(credential).map { OAuthTokens(credential, refreshToken = null, expiresInSeconds = null) }
    }

    override suspend fun fetchAuthenticatedUser(token: String): ForgeResult<ForgeUser> = forgeCall {
        val answer = http.bitbucket(token, "user")
        if (!answer.status.isSuccess()) return@forgeCall answer.failure()
        people.user(answer.body<Account>())?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
    }
}

/** Forking a Bitbucket repository, into the person's own workspace. No one stars, and watching can't be set by the API. */
class BitbucketStarApi(private val http: HttpClient) : UnsupportedStarApi() {
    override suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> = forgeCall {
        val answer = http.bitbucket(token, *repo.api("forks"), method = HttpMethod.Post, body = buildJsonObject { })
        if (!answer.status.isSuccess()) return@forgeCall answer.failure()
        answer.body<Repository>().repoId()?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
    }
}

/**
 * What a Bitbucket repository's administrator sets from here: its description, website, main branch and who sees it,
 * its name, and deleting it. Its merge rules are its branches' restrictions, set on its own pages.
 */
class BitbucketRepoSettingsApi(private val http: HttpClient) : RepoSettingsApi {
    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> = forgeCall {
        http.bitbucket(token, *repo.api()).toResult { body<Repository>().toSettings() }
    }

    private fun Repository.toSettings() = RepoSettings(
        description = description.orEmpty(),
        website = website.orEmpty(),
        hasWebsite = true,
        defaultBranch = mainbranch?.name.orEmpty(),
        visibility = if (isPrivate == true) RepoVisibility.PRIVATE else RepoVisibility.PUBLIC,
        visibilities = listOf(RepoVisibility.PUBLIC, RepoVisibility.PRIVATE),
    )

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> {
        val fields: JsonObject = when (change) {
            is RepoSettingsChange.Description -> buildJsonObject { put("description", change.text) }
            is RepoSettingsChange.Website -> buildJsonObject { put("website", change.url) }
            is RepoSettingsChange.DefaultBranch -> buildJsonObject { putJsonObject("mainbranch") { put("name", change.branch) } }
            is RepoSettingsChange.Visibility -> when (change.visibility) {
                RepoVisibility.PUBLIC -> buildJsonObject { put("is_private", false) }
                RepoVisibility.PRIVATE -> buildJsonObject { put("is_private", true) }
                RepoVisibility.INTERNAL -> return ForgeResult.Failure(ForgeError.Unsupported)
            }
            else -> return ForgeResult.Failure(ForgeError.Unsupported)
        }
        return forgeCall { http.bitbucket(token, *repo.api(), method = HttpMethod.Put, body = fields).toResult { body<Repository>().toSettings() } }
    }

    /** Bitbucket names a repository's address after its name: renaming it moves it. */
    override suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId> = forgeCall {
        val answer = http.bitbucket(token, *repo.api(), method = HttpMethod.Put, body = buildJsonObject { put("name", name) })
        if (!answer.status.isSuccess()) return@forgeCall answer.failure()
        ForgeResult.Success(answer.body<Repository>().repoId() ?: repo.copy(name = name))
    }

    override suspend fun delete(token: String, repo: RepoId): ForgeResult<RepoDeletion> = forgeCall {
        http.bitbucket(token, *repo.api(), method = HttpMethod.Delete).toResult { RepoDeletion.DELETED }
    }
}
