package fr.arthurbrugiere.forgeline.actions

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.actions.DefaultActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.workflowRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeActionsApi()
    private val accounts = FakeAccountRepository()
    private val repo = RepoId("octo", "repo")
    private val job = RunJob(1, "build", RunStatus.COMPLETED, RunConclusion.FAILURE, null, null, emptyList())

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel() = RunViewModel(repo, 7, DefaultActionsRepository(FakeForgeClients(actions = api), accounts))

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")

    @Test
    fun loads_the_run_and_its_jobs() = test {
        api.runs[7] = workflowRun(7, conclusion = RunConclusion.FAILURE)
        api.jobs[7] = listOf(job)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.run?.id).isEqualTo(7)
        assertThat(viewModel.state.value.jobs).containsExactly(job)
        assertThat(viewModel.state.value.isFinished).isTrue()
        assertThat(viewModel.state.value.isRefreshing).isFalse()
    }

    @Test
    fun a_missing_run_is_an_error() = test {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Http(404, "Not Found"))
    }

    @Test
    fun a_failed_poll_keeps_what_is_shown_quietly() = test {
        api.runs[7] = workflowRun(7, status = RunStatus.IN_PROGRESS, conclusion = null)
        api.jobs[7] = listOf(job)
        val viewModel = viewModel()
        advanceUntilIdle()

        api.runs.clear()
        viewModel.poll()
        advanceUntilIdle()

        assertThat(viewModel.state.value.run?.id).isEqualTo(7)
        assertThat(viewModel.state.value.error).isNull()
    }

    @Test
    fun a_rerun_reports_its_result_then_reloads_the_run() = test {
        signIn()
        api.runs[7] = workflowRun(7, conclusion = RunConclusion.FAILURE)
        api.jobs[7] = listOf(job)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.perform(RunAction.RERUN_FAILED)
        runCurrent()
        assertThat(viewModel.state.value.result).isEqualTo(RunActionResult(RunAction.RERUN_FAILED))
        api.runs[7] = workflowRun(7, status = RunStatus.QUEUED, conclusion = null)
        advanceTimeBy(RunViewModel.SETTLE_MILLIS + 1)
        runCurrent()

        assertThat(api.calls).contains("rerun:octo/repo:7:failed")
        assertThat(viewModel.state.value.run?.status).isEqualTo(RunStatus.QUEUED)
    }

    @Test
    fun a_refused_action_reports_why() = test {
        signIn()
        api.runs[7] = workflowRun(7, status = RunStatus.IN_PROGRESS, conclusion = null)
        api.writeFailure = ForgeError.Http(403, "Must have admin rights to Repository.")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.perform(RunAction.CANCEL)
        advanceUntilIdle()

        assertThat(viewModel.state.value.result).isEqualTo(RunActionResult(RunAction.CANCEL, ForgeError.Http(403, "Must have admin rights to Repository.")))
        assertThat(viewModel.state.value.pending).isNull()
        viewModel.resultShown()
        assertThat(viewModel.state.value.result).isNull()
    }

    @Test
    fun one_action_at_a_time() = test {
        signIn()
        api.runs[7] = workflowRun(7, status = RunStatus.IN_PROGRESS, conclusion = null)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.perform(RunAction.CANCEL)
        viewModel.perform(RunAction.CANCEL)
        advanceUntilIdle()

        assertThat(api.calls.count { it.startsWith("cancel:") }).isEqualTo(1)
    }
}
