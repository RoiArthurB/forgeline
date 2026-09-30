package fr.arthurbrugiere.forgeline.di

import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.ForgejoTrendingMeter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
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
import fr.arthurbrugiere.forgeline.signin.LoopbackRedirects
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoActionsApi
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
import io.ktor.client.HttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** GitHub's clients come from [ForgeModule]; each Forgejo instance gets its own from `forge:forgejo`, built on first use. */
@Singleton
class DefaultForgeClients @Inject constructor(
    @Forgejo private val forgejoHttp: HttpClient,
    @CodebergClientId private val codebergClientId: String,
    @CodebergTrendingUrl private val codebergTrendingUrl: String,
    private val repos: RepoApi,
    private val issues: IssueApi,
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

    private val forgejo = ConcurrentHashMap<ForgeInstance, ForgejoClients>()

    private fun <T> pick(forge: ForgeInstance, gitHub: T, forgejo: ForgejoClients.() -> T): T =
        if (forge.type == ForgeType.GITHUB) gitHub else this.forgejo.getOrPut(forge) { ForgejoClients(forgejoHttp, forge) }.forgejo()

    override fun repos(forge: ForgeInstance) = pick(forge, repos) { repos }

    override fun issues(forge: ForgeInstance) = pick(forge, issues) { issues }

    override fun users(forge: ForgeInstance) = pick(forge, users) { users }

    override fun stars(forge: ForgeInstance) = pick(forge, stars) { stars }

    override fun search(forge: ForgeInstance) = pick(forge, search) { search }

    override fun feed(forge: ForgeInstance) = pick(forge, feed) { feed }

    override fun notifications(forge: ForgeInstance) = pick(forge, notifications) { notifications }

    override fun auth(forge: ForgeInstance) = pick(forge, auth) { auth }

    override fun actions(forge: ForgeInstance): ActionsApi? = pick(forge, actions) { actions }

    override fun trending(forge: ForgeInstance): TrendingApi? = pick(forge, trending) { trending }

    override fun trendingMeter(forge: ForgeInstance): TrendingMeter? = pick(forge, null) { trendingMeter }
}

/** Codeberg's OAuth client ID, from `forgeline.codebergClientId`; blank without one. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodebergClientId

/** Where Codeberg's Trending is published, from `forgeline.codebergTrendingUrl`; blank leaves Codeberg out. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodebergTrendingUrl

/** Forgejo's HTTP client: its own JSON settings and user agent, shared by every instance. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Forgejo

@Module
@InstallIn(SingletonComponent::class)
abstract class ForgeClientsModule {
    @Binds
    abstract fun bindForgeClients(impl: DefaultForgeClients): ForgeClients

    @Binds
    abstract fun bindBrowserRedirects(impl: LoopbackRedirects): BrowserRedirects

    companion object {
        @Provides
        @Singleton
        @Forgejo
        fun provideForgejoHttpClient(): HttpClient = forgejoHttpClient(forgeEngine())

        @Provides
        @CodebergClientId
        fun provideCodebergClientId(): String = BuildConfig.CODEBERG_CLIENT_ID

        @Provides
        @CodebergTrendingUrl
        fun provideCodebergTrendingUrl(): String = BuildConfig.CODEBERG_TRENDING_URL
    }
}
