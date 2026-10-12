package fr.arthurbrugiere.forgeline.pull

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.pull.DefaultPullRequestRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakePullRequestApi()
    private val repository = DefaultPullRequestRepository(FakeForgeClients(pulls = api), FakeAccountRepository())
    private val tools = RepoId("octo", "tools")
    private val report = CheckReport("health", CheckState.FAILURE, "Code Health", "PR Check", "Gate failed", null, null, "https://health.example/7", "https://github.com/octo/tools/runs/4242")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel() = CheckViewModel(tools, 4242, "health", repository)

    @Test
    fun says_what_was_tapped_until_the_report_is_read() = test {
        api.checkReports[4242] = report

        val viewModel = viewModel()
        assertThat(viewModel.state.value.name).isEqualTo("health")
        assertThat(viewModel.state.value.report).isNull()
        assertThat(viewModel.state.value.isRefreshing).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.state.value.report).isEqualTo(report)
        assertThat(viewModel.state.value.isRefreshing).isFalse()
        assertThat(api.calls).containsExactly("checkReport:octo/tools:4242")
    }

    @Test
    fun a_report_that_cannot_be_read_is_an_error_that_a_retry_clears() = test {
        api.failure = ForgeError.Network
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.report).isNull()

        api.failure = null
        api.checkReports[4242] = report
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.report).isEqualTo(report)
    }

    @Test
    fun a_failed_refresh_keeps_the_report_and_says_so_once() = test {
        api.checkReports[4242] = report
        val viewModel = viewModel()
        advanceUntilIdle()

        api.failure = ForgeError.Network
        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.state.value.report).isEqualTo(report)
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)

        viewModel.errorShown()
        assertThat(viewModel.state.value.error).isNull()
    }
}
