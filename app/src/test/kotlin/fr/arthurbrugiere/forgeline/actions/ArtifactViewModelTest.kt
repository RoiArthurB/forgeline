package fr.arthurbrugiere.forgeline.actions

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.actions.DefaultActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ArtifactDocument
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunArtifact
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeActionsApi()
    private val accounts = FakeAccountRepository()
    private val repo = RepoId("RoiArthurB", "GAMA-GC-Benchmark")
    private val report = RunArtifact(12520640926, "report", 1_154_768, expired = false)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel() = ArtifactViewModel(repo, 7, report, DefaultActionsRepository(FakeForgeClients(actions = api), accounts))

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")

    @Test
    fun an_artifact_with_one_report_shows_it_at_once() = test {
        signIn()
        api.documents[report.id] = listOf(ArtifactDocument("report/report.md", "# GAMA garbage-collector benchmark"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.shown?.path).isEqualTo("report/report.md")
        assertThat(viewModel.state.value.isLoading).isFalse()
    }

    @Test
    fun of_several_files_the_one_picked_shows_and_going_back_lists_them_again() = test {
        signIn()
        api.documents[report.id] = listOf(ArtifactDocument("README.md", "Read me"), ArtifactDocument("report/report.md", "# Report"))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertThat(viewModel.state.value.shown).isNull()

        viewModel.select("report/report.md")
        assertThat(viewModel.state.value.shown?.markdown).isEqualTo("# Report")

        viewModel.select(null)
        assertThat(viewModel.state.value.shown).isNull()
    }

    @Test
    fun signed_out_it_says_why_it_can_t_be_read() = test {
        api.documents[report.id] = listOf(ArtifactDocument("report/report.md", "# Report"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Unauthorized)
        assertThat(viewModel.state.value.documents).isNull()
    }
}
