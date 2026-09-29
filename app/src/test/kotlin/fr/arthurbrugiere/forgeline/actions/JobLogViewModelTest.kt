package fr.arthurbrugiere.forgeline.actions

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.actions.DefaultActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobLogViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeActionsApi()
    private val accounts = FakeAccountRepository()
    private val repo = RepoId("octo", "repo")
    private val log = JobLog(
        listOf(
            LogEntry.Group("Set up job", listOf(LogEntry.Line("Runner version 2.337"))),
            LogEntry.Group("Run pnpm test", listOf(LogEntry.Line("1 failed"), LogEntry.Line("Tests failed", LogLineKind.ERROR))),
            LogEntry.Line("Process completed with exit code 1.", LogLineKind.ERROR),
        ),
    )

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel() = JobLogViewModel(repo, 3, "test", DefaultActionsRepository(api, accounts))

    @Test
    fun groups_holding_an_error_start_open_and_the_rest_folded() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.logs[3] = log

        val viewModel = viewModel()
        advanceUntilIdle()

        val rows = viewModel.state.value.rows
        assertThat(rows.map { it::class.simpleName to (it as? LogRow.Header)?.open }).containsExactly(
            "Header" to false,
            "Header" to true, "Line" to null, "Line" to null,
            "Line" to null,
        ).inOrder()
        assertThat(viewModel.state.value.errorRows).containsExactly(3, 4).inOrder()
    }

    @Test
    fun a_folded_group_opens_on_demand() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.logs[3] = log
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.toggleGroup(0)

        assertThat(viewModel.state.value.rows[1]).isEqualTo(LogRow.Line(LogEntry.Line("Runner version 2.337"), 0, 0))
    }

    @Test
    fun signed_out_the_log_is_unauthorized() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.log).isEqualTo(Loadable.Failed(ForgeError.Unauthorized))
    }
}
