package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
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

/**
 * One set of fakes per forge: GitHub's are the constructor's, other forges get theirs through [on]. Every lookup is
 * recorded in [asked] as "kind:host", so tests can check a call went to the right forge.
 */
class FakeForgeClients(
    val repos: RepoApi = FakeRepoApi(),
    val issues: IssueApi = FakeIssueApi(),
    val users: UserApi = FakeUserApi(),
    val stars: StarApi = FakeStarApi(),
    val search: SearchApi = FakeSearchApi(),
    val feed: FeedApi = FakeFeedApi(),
    val notifications: NotificationsApi = FakeNotificationsApi(),
    val auth: ForgeAuthApi = FakeForgeAuthApi(),
    val actions: ActionsApi? = FakeActionsApi(),
    val trending: TrendingApi? = FakeTrendingApi(),
    val trendingMeter: TrendingMeter? = null,
) : ForgeClients {
    private val others = mutableMapOf<ForgeInstance, FakeForgeClients>()
    val asked = mutableListOf<String>()

    /** The fakes answering for [forge]; GitHub's are this object's own. */
    fun on(forge: ForgeInstance): FakeForgeClients =
        if (forge == ForgeInstance.GitHub) this else others.getOrPut(forge) { FakeForgeClients() }

    fun put(forge: ForgeInstance, clients: FakeForgeClients) {
        others[forge] = clients
    }

    private fun <T> ask(kind: String, forge: ForgeInstance, pick: FakeForgeClients.() -> T): T {
        asked += "$kind:${forge.host}"
        return on(forge).pick()
    }

    override fun repos(forge: ForgeInstance) = ask("repos", forge) { repos }

    override fun issues(forge: ForgeInstance) = ask("issues", forge) { issues }

    override fun users(forge: ForgeInstance) = ask("users", forge) { users }

    override fun stars(forge: ForgeInstance) = ask("stars", forge) { stars }

    override fun search(forge: ForgeInstance) = ask("search", forge) { search }

    override fun feed(forge: ForgeInstance) = ask("feed", forge) { feed }

    override fun notifications(forge: ForgeInstance) = ask("notifications", forge) { notifications }

    override fun auth(forge: ForgeInstance) = ask("auth", forge) { auth }

    override fun actions(forge: ForgeInstance) = ask("actions", forge) { actions }

    override fun trending(forge: ForgeInstance) = ask("trending", forge) { trending }

    override fun trendingMeter(forge: ForgeInstance) = ask("meter", forge) { trendingMeter }
}
