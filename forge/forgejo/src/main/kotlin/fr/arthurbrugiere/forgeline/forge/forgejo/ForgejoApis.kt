package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.ForgejoTrendingMeter
import io.ktor.client.HttpClient

/**
 * One Forgejo instance's APIs, on [http]. [clientId] is the OAuth application it signs in with, blank for none (a
 * token then); [trending] its published list, when it has one.
 */
class ForgejoApis(http: HttpClient, forge: ForgeInstance, clientId: String, override val trending: TrendingApi?) : ForgeApis {
    override val repos = ForgejoRepoApi(http, forge)
    override val issues = ForgejoIssueApi(http, forge)
    override val pulls = ForgejoPullRequestApi(http, forge)
    override val repoSettings = ForgejoRepoSettingsApi(http, forge)
    override val users = ForgejoUserApi(http, forge)
    override val stars = ForgejoStarApi(http, forge)
    override val search = ForgejoSearchApi(http, forge)
    override val notifications = ForgejoNotificationsApi(http, forge)
    override val feed = ForgejoFeedApi(http, forge)
    override val actions = ForgejoActionsApi(http, forge)
    override val trendingMeter = ForgejoTrendingMeter(http, forge)
    override val auth = ForgejoAuthApi(http, forge, clientId = clientId)
}
