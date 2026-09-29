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
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.RunStep
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

    private fun job(status: RunStatus, conclusion: RunConclusion? = null) = RunJob(
        3, "test", status, conclusion, null, null,
        listOf(RunStep(1, "Set up job", RunStatus.COMPLETED, RunConclusion.SUCCESS), RunStep(2, "Run tests", status, conclusion)),
    )

    @Test
    fun a_running_job_shows_its_steps_without_asking_for_a_log() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.IN_PROGRESS))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isRunning).isTrue()
        assertThat(viewModel.state.value.isLive).isTrue()
        assertThat(viewModel.state.value.job?.steps?.last()?.name).isEqualTo("Run tests")
        assertThat(api.calls.none { it.startsWith("log:") }).isTrue()
    }

    @Test
    fun once_the_job_finishes_its_log_loads() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.IN_PROGRESS))
        val viewModel = viewModel()
        advanceUntilIdle()

        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.FAILURE))
        api.logs[3] = log
        viewModel.poll()
        advanceUntilIdle()

        assertThat(viewModel.state.value.isRunning).isFalse()
        assertThat(viewModel.state.value.log).isEqualTo(Loadable.Loaded(log))
        assertThat(viewModel.state.value.isLive).isFalse()
    }

    @Test
    fun a_log_not_published_yet_is_asked_for_again_a_few_times() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.jobs[1] = listOf(job(RunStatus.COMPLETED, RunConclusion.SUCCESS))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.isLive).isTrue()

        repeat(MAX_LOG_ATTEMPTS) {
            viewModel.poll()
            advanceUntilIdle()
        }

        assertThat(viewModel.state.value.isLive).isFalse()
        assertThat(viewModel.state.value.log).isEqualTo(Loadable.Failed(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun signed_out_the_log_is_unauthorized() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.log).isEqualTo(Loadable.Failed(ForgeError.Unauthorized))
    }
}
