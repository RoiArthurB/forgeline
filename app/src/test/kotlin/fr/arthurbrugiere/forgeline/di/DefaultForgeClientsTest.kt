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
import io.ktor.client.engine.okhttp.OkHttp
import org.junit.Test

class DefaultForgeClientsTest {
    private val gitHubRepos = FakeRepoApi()
    private fun clients(codebergTrendingUrl: String = "https://example.org/codeberg/trending.json") = DefaultForgeClients(
        forgejoHttp = forgejoHttpClient(OkHttp.create()),
        codebergClientId = "codeberg-client",
        codebergTrendingUrl = codebergTrendingUrl,
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
}
