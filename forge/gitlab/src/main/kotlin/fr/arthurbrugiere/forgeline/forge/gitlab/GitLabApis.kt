package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeApis
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import io.ktor.client.HttpClient

/**
 * One GitLab instance's APIs, on [http]. [clientId] is the OAuth application it signs in with, asked each time (a
 * self-hosted server's is typed at sign-in, after its APIs were built); [trending] and [trendingMeter] where it has them.
 */
class GitLabApis(
    http: HttpClient,
    forge: ForgeInstance,
    clientId: () -> String,
    override val trending: TrendingApi?,
    override val trendingMeter: TrendingMeter?,
) : ForgeApis {
    override val repos = GitLabRepoApi(http, forge)
    override val issues = GitLabIssueApi(http, forge)
    override val pulls = GitLabPullRequestApi(http, forge)
    override val repoSettings = GitLabRepoSettingsApi(http, forge)
    override val users = GitLabUserApi(http, forge)
    override val stars = GitLabStarApi(http, forge)
    override val search = GitLabSearchApi(http, forge)
    override val notifications = GitLabNotificationsApi(http, forge)
    override val feed = GitLabFeedApi(http, forge)
    override val actions = GitLabActionsApi(http)
    override val auth = GitLabAuthApi(http, forge, clientId = clientId)
}
