package fr.arthurbrugiere.forgeline.firstissues

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.firstissues.FirstIssues
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FirstIssue
import fr.arthurbrugiere.forgeline.core.model.FirstIssueSource
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeFirstIssuesRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstIssuesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val starred = FirstIssue(RepoId("me", "tool"), issueSummary(1, "Fix a typo"), FirstIssueSource.STARRED)
    private val trending = FirstIssue(RepoId("hot", "thing", ForgeInstance.Codeberg), issueSummary(2, "Add a test"), FirstIssueSource.TRENDING)
    private val repository = FakeFirstIssuesRepository()

    @Test
    fun opening_looks_for_them_unless_found_lately_and_shows_what_was_kept_at_once() = runTest(main.testDispatcher) {
        repository.stored.value = FirstIssues(listOf(starred), 1L)
        val viewModel = FirstIssuesViewModel(SavedStateHandle(), repository).also { it.state.launchIn(backgroundScope) }
        advanceUntilIdle()

        assertThat(repository.refreshes).containsExactly(false)
        assertThat(viewModel.state.value.shown).containsExactly(starred)
        assertThat(viewModel.state.value.isRefreshing).isFalse()

        viewModel.refresh()
        advanceUntilIdle()
        assertThat(repository.refreshes).containsExactly(false, true).inOrder()
    }

    @Test
    fun one_source_can_be_picked_once_both_found_some() = runTest(main.testDispatcher) {
        repository.stored.value = FirstIssues(listOf(starred, trending), 1L)
        val viewModel = FirstIssuesViewModel(SavedStateHandle(), repository).also { it.state.launchIn(backgroundScope) }
        advanceUntilIdle()
        assertThat(viewModel.state.value.hasBothSources).isTrue()
        assertThat(viewModel.state.value.showForge).isTrue()

        viewModel.selectSource(FirstIssueSource.TRENDING)
        advanceUntilIdle()

        assertThat(viewModel.state.value.shown).containsExactly(trending)
    }

    @Test
    fun a_failed_look_is_said_until_shown() = runTest(main.testDispatcher) {
        repository.answer = ForgeResult.Failure(ForgeError.Network)
        val viewModel = FirstIssuesViewModel(SavedStateHandle(), repository).also { it.state.launchIn(backgroundScope) }
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)

        viewModel.errorShown()
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isNull()
    }
}
