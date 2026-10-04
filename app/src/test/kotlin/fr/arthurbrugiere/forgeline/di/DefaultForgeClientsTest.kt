package fr.arthurbrugiere.forgeline.di

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeAuthApi
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.FakeNotificationsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.FakeStarApi
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingApi
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoActionsApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoRepoApi
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoTrendingApi
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabActionsApi
import fr.arthurbrugiere.forgeline.forge.gitlab.GitLabRepoApi
import fr.arthurbrugiere.forgeline.forge.gitlab.gitlabHttpClient
import fr.arthurbrugiere.forgeline.forge.gitlab.trending.GitLabTrendingMeter
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Test

class DefaultForgeClientsTest {
    private val gitHubRepos = FakeRepoApi()
    private fun clients(
        codebergTrendingUrl: String = "https://example.org/codeberg/trending.json",
        gitlabTrendingUrl: String = "https://example.org/gitlab/trending.json",
        gitlabClientId: String = "gitlab-client",
    ) = DefaultForgeClients(
        forgejoHttp = forgejoHttpClient(OkHttp.create()),
        gitlabHttp = gitlabHttpClient(OkHttp.create()),
        codebergClientId = "codeberg-client",
        gitlabClientId = gitlabClientId,
        codebergTrendingUrl = codebergTrendingUrl,
        gitlabTrendingUrl = gitlabTrendingUrl,
        repos = gitHubRepos, issues = FakeIssueApi(), users = FakeUserApi(), stars = FakeStarApi(), search = FakeSearchApi(),
        feed = FakeFeedApi(), notifications = FakeNotificationsApi(), auth = FakeForgeAuthApi(), actions = FakeActionsApi(),
        trending = FakeTrendingApi(),
    )
    private val clients = clients()

    @Test
    fun github_uses_its_own_clients() {
        assertThat(clients.repos(ForgeInstance.GitHub)).isSameInstanceAs(gitHubRepos)
    }

    @Test
    fun each_forgejo_instance_gets_its_clients_once() {
        val codeberg = clients.repos(ForgeInstance.Codeberg)
        val selfHosted = clients.repos(ForgeInstance(ForgeType.FORGEJO, "git.example.org"))

        assertThat(codeberg).isInstanceOf(ForgejoRepoApi::class.java)
        assertThat(clients.repos(ForgeInstance.Codeberg)).isSameInstanceAs(codeberg)
        assertThat(selfHosted).isNotSameInstanceAs(codeberg)
    }

    @Test
    fun only_codeberg_signs_in_through_the_browser() {
        assertThat(clients.auth(ForgeInstance.Codeberg).supportsBrowserSignIn).isTrue()
        assertThat(clients.auth(ForgeInstance(ForgeType.FORGEJO, "git.example.org")).supportsBrowserSignIn).isFalse()
        assertThat(clients.auth(ForgeInstance.Codeberg).forge).isEqualTo(ForgeInstance.Codeberg)
    }

    @Test
    fun each_forgejo_instance_has_actions_without_rerun() {
        val codeberg = clients.actions(ForgeInstance.Codeberg)

        assertThat(codeberg).isInstanceOf(ForgejoActionsApi::class.java)
        assertThat(codeberg!!.supportsRerun).isFalse()
        assertThat(clients.actions(ForgeInstance.GitHub)!!.supportsRerun).isTrue()
    }

    @Test
    fun only_codeberg_has_a_trending_list_and_only_once_it_is_published() {
        assertThat(clients.trending(ForgeInstance.Codeberg)).isInstanceOf(ForgejoTrendingApi::class.java)
        assertThat(clients.trending(ForgeInstance.Codeberg)!!.forge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(clients.trending(ForgeInstance(ForgeType.FORGEJO, "git.example.org"))).isNull()
        assertThat(clients(codebergTrendingUrl = "").trending(ForgeInstance.Codeberg)).isNull()
    }

    @Test
    fun gitlab_has_its_published_list_and_its_own_meter() {
        assertThat(clients.trending(ForgeInstance.GitLab)!!.forge).isEqualTo(ForgeInstance.GitLab)
        assertThat(clients.trendingMeter(ForgeInstance.GitLab)).isInstanceOf(GitLabTrendingMeter::class.java)
        assertThat(clients(gitlabTrendingUrl = "").trending(ForgeInstance.GitLab)).isNull()
    }

    @Test
    fun each_gitlab_instance_gets_its_clients_once() {
        val gitlab = clients.repos(ForgeInstance.GitLab)
        val selfHosted = clients.repos(ForgeInstance(ForgeType.GITLAB, "gitlab.example.org"))

        assertThat(gitlab).isInstanceOf(GitLabRepoApi::class.java)
        assertThat(clients.repos(ForgeInstance.GitLab)).isSameInstanceAs(gitlab)
        assertThat(selfHosted).isNotSameInstanceAs(gitlab)
    }

    @Test
    fun gitlab_signs_in_through_the_browser_when_client_id_provided() {
        assertThat(clients.auth(ForgeInstance.GitLab).supportsBrowserSignIn).isTrue()
        assertThat(clients(gitlabClientId = "").auth(ForgeInstance.GitLab).supportsBrowserSignIn).isFalse()
        assertThat(clients.auth(ForgeInstance(ForgeType.GITLAB, "gitlab.example.org")).supportsBrowserSignIn).isFalse()
        assertThat(clients.auth(ForgeInstance.GitLab).forge).isEqualTo(ForgeInstance.GitLab)
    }

    @Test
    fun gitlab_has_actions_with_rerun() {
        val gitlab = clients.actions(ForgeInstance.GitLab)

        assertThat(gitlab).isInstanceOf(GitLabActionsApi::class.java)
        assertThat(gitlab!!.supportsRerun).isTrue()
    }
}
