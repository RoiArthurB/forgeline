package fr.arthurbrugiere.forgeline.di

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
import javax.inject.Inject
import javax.inject.Singleton

/** GitHub's clients come from [ForgeModule]; Forgejo instances get theirs from `forge:forgejo`. */
@Singleton
class DefaultForgeClients @Inject constructor(
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

    private fun <T> gitHub(forge: ForgeInstance, client: T): T {
        check(forge.type == ForgeType.GITHUB) { "No client for ${forge.host} yet" }
        return client
    }

    override fun repos(forge: ForgeInstance) = gitHub(forge, repos)

    override fun issues(forge: ForgeInstance) = gitHub(forge, issues)

    override fun users(forge: ForgeInstance) = gitHub(forge, users)

    override fun stars(forge: ForgeInstance) = gitHub(forge, stars)

    override fun search(forge: ForgeInstance) = gitHub(forge, search)

    override fun feed(forge: ForgeInstance) = gitHub(forge, feed)

    override fun notifications(forge: ForgeInstance) = gitHub(forge, notifications)

    override fun auth(forge: ForgeInstance) = gitHub(forge, auth)

    override fun actions(forge: ForgeInstance): ActionsApi? = actions.takeIf { forge.type == ForgeType.GITHUB }

    override fun trending(forge: ForgeInstance): TrendingApi? = trending.takeIf { forge.type == ForgeType.GITHUB }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ForgeClientsModule {
    @Binds
    abstract fun bindForgeClients(impl: DefaultForgeClients): ForgeClients
}
