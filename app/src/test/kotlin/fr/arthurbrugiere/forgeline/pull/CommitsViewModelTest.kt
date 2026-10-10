package fr.arthurbrugiere.forgeline.pull

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.pull.DefaultPullRequestRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.commit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakePullRequestApi()
    private val repository = DefaultPullRequestRepository(FakeForgeClients(pulls = api), FakeAccountRepository())
    private val tools = RepoId("octo", "tools")

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun a_pull_request_lists_its_commits_all_at_once() = test {
        api.commits = listOf(commit("a1"), commit("b2"))

        val viewModel = CommitsViewModel(CommitsTarget.Pull(IssueRef(tools, 88, isPullRequest = true)), repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.commits!!.map { it.sha }).containsExactly("a1", "b2").inOrder()
        assertThat(viewModel.state.value.nextPage).isNull()
        viewModel.loadMore()
        advanceUntilIdle()
        assertThat(api.calls).containsExactly("commits:octo/tools#88")
    }

    @Test
    fun a_file_s_history_is_read_page_by_page_from_its_ref() = test {
        api.history = listOf(listOf(commit("c3"), commit("b2")), listOf(commit("a1")))

        val viewModel = CommitsViewModel(CommitsTarget.History(tools, ref = "main", path = "src/a.kt"), repository)
        advanceUntilIdle()
        assertThat(viewModel.state.value.commits!!.map { it.sha }).containsExactly("c3", "b2").inOrder()

        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.state.value.commits!!.map { it.sha }).containsExactly("c3", "b2", "a1").inOrder()
        assertThat(viewModel.state.value.nextPage).isNull()
        assertThat(api.calls).containsExactly("history:octo/tools:main:src/a.kt:1", "history:octo/tools:main:src/a.kt:2").inOrder()
    }

    @Test
    fun a_history_that_cannot_be_read_says_so_and_can_be_asked_again() = test {
        api.failure = ForgeError.Network
        val viewModel = CommitsViewModel(CommitsTarget.History(tools, ref = null, path = null), repository)
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)

        api.failure = null
        api.history = listOf(listOf(commit("a1")))
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.commits).hasSize(1)
    }
}
