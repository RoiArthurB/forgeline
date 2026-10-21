package fr.arthurbrugiere.forgeline.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoApis
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoTrendingApi
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabApis
import fr.arthurbrugiere.forgeline.forge.gitlab.gitlabHttpClient
import fr.arthurbrugiere.forgeline.forge.gitlab.trending.GitLabTrendingMeter
import fr.arthurbrugiere.forgeline.signin.BrowserRedirects
import fr.arthurbrugiere.forgeline.signin.ForgeHosts
import fr.arthurbrugiere.forgeline.signin.ForgeProbe
import fr.arthurbrugiere.forgeline.signin.HttpForgeProbe
import fr.arthurbrugiere.forgeline.signin.LoopbackRedirects
import fr.arthurbrugiere.forgeline.signin.StoredForgeHosts
import io.ktor.client.HttpClient
import okhttp3.Cache
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * GitHub's clients come from [ForgeModule]; each Forgejo or GitLab instance gets its own [ForgeApis] from
 * `forge:forgejo` and `forge:gitlab`, built on first use. What comes from the build (which instance signs in through
 * which application, which has a published Trending) is decided here.
 */
@Singleton
class DefaultForgeClients @Inject constructor(
    @Forgejo private val forgejoHttp: HttpClient,
    @GitLab private val gitlabHttp: HttpClient,
    @CodebergClientId private val codebergClientId: String,
    @GitLabClientId private val gitlabClientId: String,
    private val hosts: ForgeHosts,
    @CodebergTrendingUrl private val codebergTrendingUrl: String,
    @GitLabTrendingUrl private val gitlabTrendingUrl: String,
    repos: RepoApi,
    issues: IssueApi,
    pulls: PullRequestApi,
    repoSettings: RepoSettingsApi,
    users: UserApi,
    stars: StarApi,
    search: SearchApi,
    feed: FeedApi,
    notifications: NotificationsApi,
    auth: ForgeAuthApi,
    actions: ActionsApi,
    trending: TrendingApi,
) : ForgeClients {

    /** github.com's, one of each: GitHub has its own Trending, nothing to measure. */
    private val gitHub = object : ForgeApis {
        override val repos = repos
        override val issues = issues
        override val pulls = pulls
        override val repoSettings = repoSettings
        override val users = users
        override val stars = stars
        override val search = search
        override val feed = feed
        override val notifications = notifications
        override val auth = auth
        override val actions = actions
        override val trending = trending
        override val trendingMeter: TrendingMeter? = null
    }

    private val instances = ConcurrentHashMap<ForgeInstance, ForgeApis>()

    // gitlab.com's daily list has the same format as Codeberg's.
    private val gitlabTrendingMeter = GitLabTrendingMeter(gitlabHttp)

    private fun apis(forge: ForgeInstance): ForgeApis = when (forge.type) {
        ForgeType.GITHUB -> gitHub
        ForgeType.FORGEJO -> instances.getOrPut(forge) {
            ForgejoApis(
                forgejoHttp, forge,
                // Only Codeberg has a registered OAuth application: self-hosted instances sign in with a token.
                clientId = if (forge == ForgeInstance.Codeberg) codebergClientId else "",
                // Forgejo has no Trending: only Codeberg's is measured, by the daily job that publishes it.
                trending = codebergTrendingUrl.takeIf { forge == ForgeInstance.Codeberg && it.isNotBlank() }?.let { ForgejoTrendingApi(forgejoHttp, forge, it) },
            )
        }
        ForgeType.GITLAB -> instances.getOrPut(forge) {
            val gitlabCom = forge == ForgeInstance.GitLab
            GitLabApis(
                gitlabHttp, forge,
                // gitlab.com's application is Forgeline's own; a self-hosted server's is the one its owner created there.
                clientId = { if (gitlabCom) gitlabClientId else hosts.oauthClientId(forge.host) },
                trending = gitlabTrendingUrl.takeIf { gitlabCom && it.isNotBlank() }?.let { ForgejoTrendingApi(gitlabHttp, forge, it) },
                trendingMeter = gitlabTrendingMeter.takeIf { gitlabCom },
            )
        }
    }

    override fun repos(forge: ForgeInstance) = apis(forge).repos

    override fun issues(forge: ForgeInstance) = apis(forge).issues

    override fun pulls(forge: ForgeInstance) = apis(forge).pulls

    override fun repoSettings(forge: ForgeInstance) = apis(forge).repoSettings

    override fun users(forge: ForgeInstance) = apis(forge).users

    override fun stars(forge: ForgeInstance) = apis(forge).stars

    override fun search(forge: ForgeInstance) = apis(forge).search

    override fun feed(forge: ForgeInstance) = apis(forge).feed

    override fun notifications(forge: ForgeInstance) = apis(forge).notifications

    override fun auth(forge: ForgeInstance) = apis(forge).auth

    override fun actions(forge: ForgeInstance) = apis(forge).actions

    override fun trending(forge: ForgeInstance) = apis(forge).trending

    override fun trendingMeter(forge: ForgeInstance) = apis(forge).trendingMeter
}

/** Codeberg's OAuth client ID, from `forgeline.codebergClientId`; blank without one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodebergClientId

/** GitLab's OAuth client ID, from `forgeline.gitlabClientId`; blank without one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitLabClientId

/** Where Codeberg's Trending is published, from `forgeline.codebergTrendingUrl`; blank leaves Codeberg out. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodebergTrendingUrl

/** Where gitlab.com's Trending is published, from `forgeline.gitlabTrendingUrl`; blank leaves GitLab out. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitLabTrendingUrl

/** github.com's HTTP client. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitHub

/** Forgejo's HTTP client, shared by every instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Forgejo

/** GitLab's HTTP client, shared by every instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitLab

@Module
@InstallIn(SingletonComponent::class)
abstract class ForgeClientsModule {
    @Binds
    abstract fun bindForgeClients(impl: DefaultForgeClients): ForgeClients

    @Binds
    abstract fun bindBrowserRedirects(impl: LoopbackRedirects): BrowserRedirects

    @Binds
    abstract fun bindForgeHosts(impl: StoredForgeHosts): ForgeHosts

    companion object {
        @Provides
        @Singleton
        @Forgejo
        fun provideForgejoHttpClient(cache: Cache): HttpClient = forgejoHttpClient(forgeEngine(cache))

        @Provides
        @Singleton
        @GitLab
        fun provideGitLabHttpClient(cache: Cache): HttpClient = gitlabHttpClient(forgeEngine(cache))

        @Provides
        fun provideForgeProbe(@Forgejo http: HttpClient): ForgeProbe = HttpForgeProbe(http)

        @Provides
        @CodebergClientId
        fun provideCodebergClientId(): String = BuildConfig.CODEBERG_CLIENT_ID

        @Provides
        @GitLabClientId
        fun provideGitLabClientId(): String = BuildConfig.GITLAB_CLIENT_ID
    }
}

/**
 * Where the daily job publishes the Trending lists everyone sees. A module of its own, so app-level tests can blank
 * the addresses and stay off the network: a blank address leaves the forge out of the page.
 */
@Module
@InstallIn(SingletonComponent::class)
object PublishedTrendingModule {
    @Provides
    @CodebergTrendingUrl
    fun provideCodebergTrendingUrl(): String = BuildConfig.CODEBERG_TRENDING_URL

    @Provides
    @GitLabTrendingUrl
    fun provideGitLabTrendingUrl(): String = BuildConfig.GITLAB_TRENDING_URL
}
