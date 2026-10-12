package fr.arthurbrugiere.forgeline.core.data.repo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoSettingsApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultRepoSettingsRepositoryTest {
    private val gitHub = FakeRepoSettingsApi()
    private val gitLab = FakeRepoSettingsApi()
    private val clients = FakeForgeClients(repoSettings = gitHub).apply { put(ForgeInstance.GitLab, FakeForgeClients(repoSettings = gitLab)) }
    private val accounts = FakeAccountRepository()
    private val repos = FakeRepoRepository()
    private val repository = DefaultRepoSettingsRepository(clients, accounts, repos)

    private val tools = RepoId("octo", "tools")
    private val app = RepoId("group", "app", ForgeInstance.GitLab)

    @Test
    fun settings_need_an_account_on_the_repository_s_forge() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

        assertThat(repository.settings(tools)).isEqualTo(ForgeResult.Success(gitHub.settings))
        // Signed in on GitHub only: GitLab is asked nothing.
        assertThat(repository.settings(app)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.change(app, RepoSettingsChange.Description("x"))).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(gitHub.calls).containsExactly("settings:octo/tools")
        assertThat(gitLab.calls).isEmpty()
    }

    @Test
    fun a_change_goes_to_its_forge_and_the_repository_is_read_again() = runTest {
        accounts.signIn(ForgeInstance.GitLab, ForgeUser("me", null, null), "tok")

        val result = repository.change(app, RepoSettingsChange.Switch(RepoSwitch.WIKI, true))

        assertThat((result as ForgeResult.Success).value.switches[RepoSwitch.WIKI]).isTrue()
        assertThat(gitLab.changes).containsExactly(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        assertThat(gitHub.calls).isEmpty()
        // Its page shows what changed: it is asked for again whatever its age.
        assertThat(repos.refreshes).containsExactly(true)
    }

    @Test
    fun a_refused_change_leaves_the_repository_as_it_was_read() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        gitHub.writeFailure = ForgeError.Http(422, "Validation Failed")

        assertThat(repository.change(tools, RepoSettingsChange.Description("x"))).isEqualTo(ForgeResult.Failure(ForgeError.Http(422, "Validation Failed")))
        assertThat(repos.refreshes).isEmpty()
    }
}
