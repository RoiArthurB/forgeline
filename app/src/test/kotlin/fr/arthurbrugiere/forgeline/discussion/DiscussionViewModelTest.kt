package fr.arthurbrugiere.forgeline.discussion

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.discussionComment
import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscussionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repo = RepoId("octo", "repo")
    private val repos = FakeRepoRepository()
    private val read = Discussion(discussionSummary(7, "How do I page?", comments = 1), "I can't find it.", listOf(discussionComment("c1", "Use the cursor.")))

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun a_discussion_is_read_when_its_page_opens() = test {
        repos.discussions[7] = ForgeResult.Success(read)

        val viewModel = DiscussionViewModel(repo, 7, repos)
        assertThat(viewModel.state.value.isLoading).isTrue()
        advanceUntilIdle()

        assertThat(viewModel.state.value.discussion).isEqualTo(read)
        assertThat(viewModel.state.value.summary).isEqualTo(read.summary)
        assertThat(viewModel.state.value.isLoading).isFalse()
        assertThat(viewModel.state.value.webUrl).isEqualTo("https://github.com/octo/repo/discussions/7")
        assertThat(repos.calls).containsExactly("discussion:octo/repo#7")
    }

    @Test
    fun what_its_list_said_of_it_heads_the_page_while_the_rest_loads() = test {
        repos.seenDiscussions[7] = discussionSummary(7, "How do I page?")
        repos.discussions[7] = ForgeResult.Success(read)
        repos.gate = CompletableDeferred()

        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()

        assertThat(viewModel.state.value.summary?.title).isEqualTo("How do I page?")
        assertThat(viewModel.state.value.discussion).isNull()
        repos.gate!!.complete(Unit)
        advanceUntilIdle()
        // What was read replaces what the list said: one more comment came since.
        assertThat(viewModel.state.value.summary?.comments).isEqualTo(1)
    }

    @Test
    fun a_discussion_that_can_t_be_read_says_why_and_can_be_asked_for_again() = test {
        repos.discussions[7] = ForgeResult.Failure(ForgeError.Network)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.discussion).isNull()

        repos.discussions[7] = ForgeResult.Success(read)
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isNull()
        assertThat(viewModel.state.value.discussion).isEqualTo(read)
    }

    @Test
    fun a_refresh_that_fails_leaves_what_was_read_and_is_said_once() = test {
        repos.discussions[7] = ForgeResult.Success(read)
        val viewModel = DiscussionViewModel(repo, 7, repos)
        advanceUntilIdle()
        repos.discussions[7] = ForgeResult.Failure(ForgeError.Network)

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.discussion).isEqualTo(read)
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        viewModel.errorShown()
        assertThat(viewModel.state.value.error).isNull()
    }
}
