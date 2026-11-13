package fr.arthurbrugiere.forgeline.discussion

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DiscussionCategory
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewDiscussionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val repo = RepoId("octo", "repo")
    private val repos = FakeRepoRepository()
    private val help = DiscussionCategory("DIC_1", "Help", "Ask for help", isAnswerable = true)
    private val ideas = DiscussionCategory("DIC_2", "Ideas", null, isAnswerable = false)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun the_categories_are_read_and_the_first_is_picked_until_the_reader_picks_another() = test {
        repos.discussionCategories = ForgeResult.Success(listOf(help, ideas))

        val viewModel = NewDiscussionViewModel(repo, repos)
        advanceUntilIdle()

        assertThat(viewModel.state.value.categories).containsExactly(help, ideas).inOrder()
        assertThat(viewModel.state.value.category).isEqualTo("DIC_1")
        viewModel.categoryPicked("DIC_2")
        assertThat(viewModel.state.value.category).isEqualTo("DIC_2")
    }

    @Test
    fun categories_that_couldn_t_be_read_are_asked_for_again() = test {
        val viewModel = NewDiscussionViewModel(repo, repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.categoriesError).isEqualTo(ForgeError.Network)

        repos.discussionCategories = ForgeResult.Success(listOf(ideas))
        viewModel.readCategories()
        advanceUntilIdle()

        assertThat(viewModel.state.value.categoriesError).isNull()
        assertThat(viewModel.state.value.category).isEqualTo("DIC_2")
    }

    @Test
    fun a_discussion_with_a_title_is_started_where_it_was_filed_and_its_number_kept() = test {
        repos.discussionCategories = ForgeResult.Success(listOf(help, ideas))
        repos.startedDiscussion = 101
        val viewModel = NewDiscussionViewModel(repo, repos)
        advanceUntilIdle()
        assertThat(viewModel.state.value.canSend).isFalse()

        viewModel.titleChanged("  How do I page?  ")
        viewModel.bodyChanged("I can't find it. ")
        viewModel.send()
        advanceUntilIdle()

        assertThat(repos.calls.last()).isEqualTo("startDiscussion:octo/repo:DIC_1:How do I page?:I can't find it.")
        assertThat(viewModel.state.value.created).isEqualTo(101)
        assertThat(viewModel.state.value.canSend).isFalse()
    }

    @Test
    fun one_refused_says_why_and_stays_written() = test {
        repos.discussionCategories = ForgeResult.Success(listOf(help))
        repos.discussionWriteFailure = ForgeError.Http(403, "Forbidden")
        val viewModel = NewDiscussionViewModel(repo, repos)
        advanceUntilIdle()

        viewModel.titleChanged("Hello")
        viewModel.send()
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Http(403, "Forbidden"))
        assertThat(viewModel.state.value.title).isEqualTo("Hello")
        assertThat(viewModel.state.value.created).isNull()
        // Writing again clears what was said.
        viewModel.titleChanged("Hello!")
        assertThat(viewModel.state.value.error).isNull()
    }
}
