package fr.arthurbrugiere.forgeline.di

import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.ForgejoTrendingMeter
import fr.arthurbrugiere.forgeline.forge.gitlab.trending.GitLabTrendingMeter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import dagger.Provides
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.signin.BrowserRedirects
import fr.arthurbrugiere.forgeline.signin.ForgeHosts
import fr.arthurbrugiere.forgeline.signin.ForgeProbe
import fr.arthurbrugiere.forgeline.signin.HttpForgeProbe
import fr.arthurbrugiere.forgeline.signin.StoredForgeHosts
import fr.arthurbrugiere.forgeline.signin.LoopbackRedirects
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoActionsApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoPullRequestApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoAuthApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoFeedApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoIssueApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoNotificationsApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoRepoApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoSearchApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoStarApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoTrendingApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoUserApi
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabActionsApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabPullRequestApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabAuthApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabFeedApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabIssueApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabNotificationsApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabRepoApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabSearchApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabStarApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabUserApi
import fr.arthurbrugiere.forgeline.forge.gitlab.gitlabHttpClient
import io.ktor.client.HttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * GitHub's clients come from [ForgeModule]; each Forgejo or GitLab instance gets its own from `forge:forgejo` and `forge:gitlab`, built on first use.
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
    private val repos: RepoApi,
    private val issues: IssueApi,
    private val pulls: PullRequestApi,
    private val users: UserApi,
    private val stars: StarApi,
    private val search: SearchApi,
    private val feed: FeedApi,
    private val notifications: NotificationsApi,
    private val auth: ForgeAuthApi,
    private val actions: ActionsApi,
    private val trending: TrendingApi,
) : ForgeClients {

    /** One Forgejo instance's clients. */
    private inner class ForgejoClients(http: HttpClient, forge: ForgeInstance) {
        val repos = ForgejoRepoApi(http, forge)
        val issues = ForgejoIssueApi(http, forge)
        val pulls = ForgejoPullRequestApi(http, forge)
        val users = ForgejoUserApi(http, forge)
        val stars = ForgejoStarApi(http, forge)
        val search = ForgejoSearchApi(http, forge)
        val notifications = ForgejoNotificationsApi(http, forge)
        val feed = ForgejoFeedApi(http, forge)
        val actions = ForgejoActionsApi(http, forge)
        val trendingMeter = ForgejoTrendingMeter(http, forge)

        // Only Codeberg has a registered OAuth application: self-hosted instances sign in with a token.
        val auth = ForgejoAuthApi(http, forge, clientId = if (forge == ForgeInstance.Codeberg) codebergClientId else "")

        // Forgejo has no Trending: only Codeberg's is measured, by the daily job that publishes it.
        val trending = codebergTrendingUrl.takeIf { forge == ForgeInstance.Codeberg && it.isNotBlank() }
            ?.let { ForgejoTrendingApi(http, forge, it) }
    }

    /** One GitLab instance's clients. */
    private inner class GitLabClients(http: HttpClient, forge: ForgeInstance) {
        val repos = GitLabRepoApi(http, forge)
        val issues = GitLabIssueApi(http, forge)
        val pulls = GitLabPullRequestApi(http, forge)
        val users = GitLabUserApi(http, forge)
        val stars = GitLabStarApi(http, forge)
        val search = GitLabSearchApi(http, forge)
        val notifications = GitLabNotificationsApi(http, forge)
        val feed = GitLabFeedApi(http, forge)
        val actions = GitLabActionsApi(http)
        val trendingMeter = if (forge == ForgeInstance.GitLab) gitlabTrendingMeter else null

        // gitlab.com's application is Forgeline's own; a self-hosted server's is the one its owner created there.
        val auth = GitLabAuthApi(http, forge, clientId = { if (forge == ForgeInstance.GitLab) gitlabClientId else hosts.oauthClientId(forge.host) })

        val trending = gitlabTrendingUrl.takeIf { forge == ForgeInstance.GitLab && it.isNotBlank() }
            ?.let { ForgejoTrendingApi(http, forge, it) }
    }

    private val forgejo = ConcurrentHashMap<ForgeInstance, ForgejoClients>()
    private val gitlab = ConcurrentHashMap<ForgeInstance, GitLabClients>()

    private fun <T> pick(
        forge: ForgeInstance,
        gitHub: T,
        forgejo: ForgejoClients.() -> T,
        gitlab: GitLabClients.() -> T,
    ): T = when (forge.type) {
        ForgeType.GITHUB -> gitHub
        ForgeType.GITLAB -> this.gitlab.getOrPut(forge) { GitLabClients(gitlabHttp, forge) }.gitlab()
        ForgeType.FORGEJO -> this.forgejo.getOrPut(forge) { ForgejoClients(forgejoHttp, forge) }.forgejo()
    }

    // gitlab.com's daily list has the same format as Codeberg's.
    private val gitlabTrendingMeter = GitLabTrendingMeter(gitlabHttp)

    override fun repos(forge: ForgeInstance) = pick(forge, repos, { repos }, { repos })

    override fun issues(forge: ForgeInstance) = pick(forge, issues, { issues }, { issues })

    override fun pulls(forge: ForgeInstance): PullRequestApi = pick(forge, pulls, { pulls }, { pulls })

    override fun users(forge: ForgeInstance) = pick(forge, users, { users }, { users })

    override fun stars(forge: ForgeInstance) = pick(forge, stars, { stars }, { stars })

    override fun search(forge: ForgeInstance) = pick(forge, search, { search }, { search })

    override fun feed(forge: ForgeInstance) = pick(forge, feed, { feed }, { feed })

    override fun notifications(forge: ForgeInstance) = pick(forge, notifications, { notifications }, { notifications })

    override fun auth(forge: ForgeInstance) = pick(forge, auth, { auth }, { auth })

    override fun actions(forge: ForgeInstance): ActionsApi? = pick(forge, actions, { actions }, { actions })

    override fun trending(forge: ForgeInstance): TrendingApi? =
        pick(forge, trending, { trending }, { trending })

    override fun trendingMeter(forge: ForgeInstance): TrendingMeter? =
        pick(forge, null, { trendingMeter }, { trendingMeter })
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

/** Forgejo's HTTP client: its own JSON settings and user agent, shared by every instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Forgejo

/** GitLab's HTTP client: its own JSON settings and user agent, shared by every instance. */
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
        fun provideForgejoHttpClient(): HttpClient = forgejoHttpClient(forgeEngine())

        @Provides
        @Singleton
        @GitLab
        fun provideGitLabHttpClient(): HttpClient = gitlabHttpClient(forgeEngine())

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
