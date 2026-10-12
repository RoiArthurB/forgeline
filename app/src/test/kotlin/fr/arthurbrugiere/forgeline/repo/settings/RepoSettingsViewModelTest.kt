package fr.arthurbrugiere.forgeline.repo.settings

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.repo.DefaultRepoSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoSettingsApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepoSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeRepoSettingsApi()
    private val accounts = FakeAccountRepository()
    private val repos = FakeRepoRepository()
    private val tools = RepoId("octo", "tools")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        block()
    }

    private fun viewModel() = RepoSettingsViewModel(tools, DefaultRepoSettingsRepository(FakeForgeClients(repoSettings = api), accounts, repos), repos)

    @Test
    fun reads_the_settings() = test {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.isRefreshing).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.state.value.settings).isEqualTo(api.settings)
        assertThat(viewModel.state.value.isRefreshing).isFalse()
    }

    @Test
    fun settings_that_cannot_be_read_are_an_error_a_retry_clears() = test {
        api.failure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.settings).isNull()

        // Nothing is changed in what was never read.
        viewModel.change(RepoSettingsChange.Description("x"))
        advanceUntilIdle()
        assertThat(api.changes).isEmpty()

        api.failure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.settings).isNotNull()
    }

    @Test
    fun a_change_shows_at_once_and_is_the_forge_s_once_it_answers() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.change(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        assertThat(viewModel.state.value.settings!!.switches[RepoSwitch.WIKI]).isTrue()
        assertThat(viewModel.state.value.saving).isEqualTo(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        assertThat(api.changes).isEmpty()
        advanceUntilIdle()

        assertThat(api.changes).containsExactly(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        assertThat(viewModel.state.value.saving).isNull()
        assertThat(viewModel.state.value.saved).isEqualTo(1)
        assertThat(viewModel.state.value.settings).isEqualTo(api.settings)
    }

    @Test
    fun a_refused_change_is_taken_back_and_said_once() = test {
        val viewModel = viewModel()
        advanceUntilIdle()
        api.writeFailure = ForgeError.Http(422, "Validation Failed")

        viewModel.change(RepoSettingsChange.Switch(RepoSwitch.MERGE_COMMIT, false))
        assertThat(viewModel.state.value.settings!!.switches[RepoSwitch.MERGE_COMMIT]).isFalse()
        advanceUntilIdle()

        assertThat(viewModel.state.value.settings!!.switches[RepoSwitch.MERGE_COMMIT]).isTrue()
        assertThat(viewModel.state.value.refused).isEqualTo(ForgeError.Http(422, "Validation Failed"))
        assertThat(viewModel.state.value.saved).isEqualTo(0)
        viewModel.refusalShown()
        assertThat(viewModel.state.value.refused).isNull()
    }

    @Test
    fun one_change_at_a_time_and_none_that_changes_nothing() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.change(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        // Tapped while the first is on its way: it waits for another tap.
        viewModel.change(RepoSettingsChange.Switch(RepoSwitch.ISSUES, false))
        advanceUntilIdle()
        viewModel.change(RepoSettingsChange.Description(api.settings.description))
        advanceUntilIdle()

        assertThat(api.changes).containsExactly(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        assertThat(viewModel.state.value.settings!!.switches[RepoSwitch.ISSUES]).isTrue()
    }

    @Test
    fun the_branches_are_read_once_when_asked_for() = test {
        repos.refs = ForgeResult.Success(GitRefs(listOf("main", "dev"), listOf("v1")))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.branches).isEqualTo(Loadable.Idle)

        viewModel.loadBranches()
        viewModel.loadBranches()
        advanceUntilIdle()
        viewModel.loadBranches()
        advanceUntilIdle()

        assertThat(viewModel.state.value.branches).isEqualTo(Loadable.Loaded(listOf("main", "dev")))
        assertThat(repos.calls.count { it == "refs:octo/tools" }).isEqualTo(1)
    }

    @Test
    fun branches_that_cannot_be_read_can_be_asked_for_again() = test {
        repos.refs = ForgeResult.Failure(ForgeError.Network)
        val viewModel = viewModel()
        viewModel.loadBranches()
        advanceUntilIdle()
        assertThat(viewModel.state.value.branches).isEqualTo(Loadable.Failed(ForgeError.Network))

        repos.refs = ForgeResult.Success(GitRefs(listOf("main"), emptyList()))
        viewModel.loadBranches()
        advanceUntilIdle()

        assertThat(viewModel.state.value.branches).isEqualTo(Loadable.Loaded(listOf("main")))
    }

    @Test
    fun a_renamed_repository_says_where_it_now_is() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.rename(" kit ")
        assertThat(viewModel.state.value.working).isTrue()
        advanceUntilIdle()

        assertThat(api.renamed).containsExactly("kit")
        assertThat(viewModel.state.value.renamedTo).isEqualTo(RepoId("octo", "kit"))
        assertThat(viewModel.state.value.working).isFalse()
    }

    @Test
    fun the_same_name_or_none_renames_nothing() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.rename("tools")
        viewModel.rename("  ")
        advanceUntilIdle()

        assertThat(api.renamed).isEmpty()
    }

    @Test
    fun a_deleted_repository_says_what_became_of_it_and_takes_nothing_more() = test {
        api.deletion = RepoDeletion.SCHEDULED
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete()
        viewModel.delete()
        advanceUntilIdle()
        viewModel.change(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
        viewModel.rename("kit")
        advanceUntilIdle()

        assertThat(viewModel.state.value.deleted).isEqualTo(RepoDeletion.SCHEDULED)
        assertThat(api.deleted).containsExactly(tools)
        assertThat(api.changes).isEmpty()
        assertThat(api.renamed).isEmpty()
    }

    @Test
    fun a_refused_deletion_or_rename_is_said_and_nothing_is_gone() = test {
        api.writeFailure = ForgeError.Unauthorized
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.delete()
        advanceUntilIdle()
        assertThat(viewModel.state.value.deleted).isNull()
        assertThat(viewModel.state.value.refused).isEqualTo(ForgeError.Unauthorized)

        viewModel.rename("kit")
        assertThat(viewModel.state.value.refused).isNull()
        advanceUntilIdle()
        assertThat(viewModel.state.value.renamedTo).isNull()
        assertThat(viewModel.state.value.refused).isEqualTo(ForgeError.Unauthorized)
    }
}
