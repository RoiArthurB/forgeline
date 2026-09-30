package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.InMemoryConversationDao
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class IssueViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val api = FakeIssueApi()
    private val dao = InMemoryConversationDao()
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), FakeAccountRepository(), dao, Clock.systemUTC())
    private val ref = IssueRef(RepoId("octo", "repo"), 7)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun loads_the_issue_and_its_conversation() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)

        val viewModel = IssueViewModel(ref, repository)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.issue).isEqualTo(issueDetails(ref))
        assertThat(state.items).containsExactly(comment(1, "First"))
        assertThat(state.nextPage).isEqualTo(2)
        assertThat(state.error).isNull()
    }

    @Test
    fun a_conversation_viewed_in_an_earlier_launch_shows_at_once_while_it_refreshes() = test {
        api.issues[ref] = issueDetails(ref, "Crash on start")
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = null)
        DefaultIssueRepository(FakeForgeClients(issues = api), FakeAccountRepository(), dao, Clock.systemUTC()).run {
            issue(ref)
            timeline(ref, 1)
        }
        // A new launch: nothing in memory, the forge is unreachable.
        api.failure = ForgeError.Network
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), FakeAccountRepository(), dao, Clock.systemUTC())

        val viewModel = IssueViewModel(ref, relaunched)
        advanceUntilIdle()

        assertThat(viewModel.state.value.issue?.title).isEqualTo("Crash on start")
        assertThat(viewModel.state.value.items).containsExactly(comment(1, "First"))
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
    }

    @Test
    fun more_of_a_long_conversation_loads_on_demand() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)
        api.pages[ref to 2] = TimelinePage(listOf(comment(2, "Second")), nextPage = null)
        val viewModel = IssueViewModel(ref, repository)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items).containsExactly(comment(1, "First"), comment(2, "Second")).inOrder()
        assertThat(viewModel.state.value.nextPage).isNull()
    }

    @Test
    fun a_conversation_seen_before_shows_instantly_then_refreshes() = test {
        api.issues[ref] = issueDetails(ref, title = "Old title")
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), null)
        IssueViewModel(ref, repository)
        advanceUntilIdle()
        api.issues[ref] = issueDetails(ref, title = "New title")

        val reopened = IssueViewModel(ref, repository)

        assertThat(reopened.state.value.issue?.title).isEqualTo("Old title")
        assertThat(reopened.state.value.items).containsExactly(comment(1, "First"))
        advanceUntilIdle()
        assertThat(reopened.state.value.issue?.title).isEqualTo("New title")
    }

    @Test
    fun a_failure_without_anything_to_show_is_an_error() = test {
        api.failure = ForgeError.Network

        val viewModel = IssueViewModel(ref, repository)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.issue).isNull()
    }

    @Test
    fun refresh_reloads_from_the_first_page() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)
        api.pages[ref to 2] = TimelinePage(listOf(comment(2, "Second")), nextPage = null)
        val viewModel = IssueViewModel(ref, repository)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items).containsExactly(comment(1, "First"))
        assertThat(viewModel.state.value.nextPage).isEqualTo(2)
    }
}
