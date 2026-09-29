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
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoRepoApi
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Test

class DefaultForgeClientsTest {
    private val gitHubRepos = FakeRepoApi()
    private val clients = DefaultForgeClients(
        forgejoHttp = forgejoHttpClient(OkHttp.create()),
        repos = gitHubRepos, issues = FakeIssueApi(), users = FakeUserApi(), stars = FakeStarApi(), search = FakeSearchApi(),
        feed = FakeFeedApi(), notifications = FakeNotificationsApi(), auth = FakeForgeAuthApi(), actions = FakeActionsApi(),
        trending = FakeTrendingApi(),
    )

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
    fun forgejo_has_no_actions_or_trending_client_yet() {
        assertThat(clients.actions(ForgeInstance.Codeberg)).isNull()
        assertThat(clients.trending(ForgeInstance.Codeberg)).isNull()
    }
}
