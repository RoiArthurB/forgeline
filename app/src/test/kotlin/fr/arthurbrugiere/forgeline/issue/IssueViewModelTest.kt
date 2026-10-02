package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.lifecycle.SavedStateHandle
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
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
    private val accounts = FakeAccountRepository()
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, dao, Clock.systemUTC())
    private val ref = IssueRef(RepoId("octo", "repo"), 7)

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun loads_the_issue_and_its_conversation() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)

        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
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

        val viewModel = IssueViewModel(ref, relaunched, SavedStateHandle())
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
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
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
        IssueViewModel(ref, repository, SavedStateHandle())
        advanceUntilIdle()
        api.issues[ref] = issueDetails(ref, title = "New title")

        val reopened = IssueViewModel(ref, repository, SavedStateHandle())

        assertThat(reopened.state.value.issue?.title).isEqualTo("Old title")
        assertThat(reopened.state.value.items).containsExactly(comment(1, "First"))
        advanceUntilIdle()
        assertThat(reopened.state.value.issue?.title).isEqualTo("New title")
    }

    @Test
    fun a_failure_without_anything_to_show_is_an_error() = test {
        api.failure = ForgeError.Network

        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.issue).isNull()
    }

    @Test
    fun refresh_reloads_from_the_first_page() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)
        api.pages[ref to 2] = TimelinePage(listOf(comment(2, "Second")), nextPage = null)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items).containsExactly(comment(1, "First"))
        assertThat(viewModel.state.value.nextPage).isEqualTo(2)
    }

    private suspend fun TestScope.openedSignedIn(nextPage: Int? = null): IssueViewModel {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = nextPage)
        return IssueViewModel(ref, repository, SavedStateHandle()).also { advanceUntilIdle() }
    }

    @Test
    fun a_comment_is_posted_joins_the_conversation_and_empties_the_draft() = test {
        val viewModel = openedSignedIn()

        viewModel.draftChanged("  Thanks, fixed!  ")
        viewModel.sendComment()
        assertThat(viewModel.state.value.isCommenting).isTrue()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(api.posted).containsExactly("octo/repo#7: Thanks, fixed!")
        assertThat(state.items.map { (it as TimelineItem.Comment).body }).containsExactly("First", "Thanks, fixed!").inOrder()
        assertThat(state.issue?.comments).isEqualTo(issueDetails(ref).comments + 1)
        assertThat(state.draft).isEmpty()
        assertThat(state.isCommenting).isFalse()
        assertThat(state.commentError).isNull()
        assertThat(state.commentPostedOutOfSight).isFalse()
    }

    @Test
    fun a_refused_comment_keeps_the_draft_and_says_why() = test {
        val viewModel = openedSignedIn()
        api.commentFailure = ForgeError.Http(403, "locked")

        viewModel.draftChanged("Hello")
        viewModel.sendComment()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.draft).isEqualTo("Hello")
        assertThat(state.commentError).isEqualTo(ForgeError.Http(403, "locked"))
        assertThat(state.items).containsExactly(comment(1, "First"))
        // Typing again takes the error away.
        viewModel.draftChanged("Hello again")
        assertThat(viewModel.state.value.commentError).isNull()
    }

    @Test
    fun an_empty_comment_is_not_sent() = test {
        val viewModel = openedSignedIn()

        viewModel.draftChanged("   \n ")
        viewModel.sendComment()
        advanceUntilIdle()

        assertThat(api.posted).isEmpty()
    }

    @Test
    fun a_comment_is_sent_once_however_often_send_is_tapped() = test {
        val viewModel = openedSignedIn()

        viewModel.draftChanged("Hello")
        viewModel.sendComment()
        viewModel.sendComment()
        advanceUntilIdle()

        assertThat(api.posted).hasSize(1)
    }

    @Test
    fun a_comment_posted_past_what_is_loaded_is_announced_not_misplaced() = test {
        // More of the conversation is still to load: the comment belongs after it.
        val viewModel = openedSignedIn(nextPage = 2)

        viewModel.draftChanged("Hello")
        viewModel.sendComment()
        advanceUntilIdle()

        assertThat(viewModel.state.value.items).containsExactly(comment(1, "First"))
        assertThat(viewModel.state.value.commentPostedOutOfSight).isTrue()
        viewModel.commentNoticeShown()
        assertThat(viewModel.state.value.commentPostedOutOfSight).isFalse()
    }

    @Test
    fun a_draft_survives_the_app_being_stopped() = test {
        val saved = SavedStateHandle()
        api.issues[ref] = issueDetails(ref)
        IssueViewModel(ref, repository, saved).draftChanged("Half a thought")

        val restored = IssueViewModel(ref, repository, saved)

        assertThat(restored.state.value.draft).isEqualTo("Half a thought")
    }

    @Test
    fun someone_else_s_conversation_in_someone_else_s_repository_can_t_be_closed() = test {
        val viewModel = openedSignedIn()

        assertThat(viewModel.state.value.canChangeState).isFalse()
    }

    @Test
    fun whoever_opened_the_conversation_can_close_it() = test {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        api.issues[ref] = issueDetails(ref)

        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
        advanceUntilIdle()

        assertThat(viewModel.state.value.canChangeState).isTrue()
    }

    @Test
    fun whoever_manages_the_repository_can_close_anyone_s_conversation() = test {
        api.access[ref.repo] = RepoAccess.TRIAGE

        val viewModel = openedSignedIn()

        assertThat(viewModel.state.value.canChangeState).isTrue()
    }

    @Test
    fun signing_in_while_the_conversation_is_open_is_asked_about_again() = test {
        api.issues[ref] = issueDetails(ref)
        api.access[ref.repo] = RepoAccess.TRIAGE
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
        advanceUntilIdle()
        assertThat(viewModel.state.value.canChangeState).isFalse()

        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        viewModel.checkPermissions()
        advanceUntilIdle()

        assertThat(viewModel.state.value.canChangeState).isTrue()
    }

    @Test
    fun closing_shows_at_once_then_reloads_what_the_forge_says_of_it() = test {
        api.access[ref.repo] = RepoAccess.TRIAGE
        val viewModel = openedSignedIn()
        val loadsBefore = api.calls.count { it.startsWith("timeline:") }

        viewModel.toggleOpen()
        assertThat(viewModel.state.value.isChangingState).isTrue()
        advanceUntilIdle()

        assertThat(api.stateChanges).containsExactly("octo/repo#7: closed")
        assertThat(viewModel.state.value.issue?.state).isEqualTo(IssueState.CLOSED)
        assertThat(viewModel.state.value.isChangingState).isFalse()
        assertThat(viewModel.state.value.stateError).isNull()
        // The line that closes the conversation comes from the forge.
        assertThat(api.calls.count { it.startsWith("timeline:") }).isEqualTo(loadsBefore + 1)
    }

    @Test
    fun a_closed_conversation_reopens() = test {
        api.access[ref.repo] = RepoAccess.TRIAGE
        val viewModel = openedSignedIn()
        viewModel.toggleOpen()
        advanceUntilIdle()

        viewModel.toggleOpen()
        advanceUntilIdle()

        assertThat(api.stateChanges).containsExactly("octo/repo#7: closed", "octo/repo#7: open").inOrder()
        assertThat(viewModel.state.value.issue?.state).isEqualTo(IssueState.OPEN)
    }

    @Test
    fun a_refused_state_change_leaves_the_conversation_as_it_was_and_says_why() = test {
        api.access[ref.repo] = RepoAccess.TRIAGE
        val viewModel = openedSignedIn()
        api.stateFailure = ForgeError.Http(403, "no")

        viewModel.toggleOpen()
        advanceUntilIdle()

        assertThat(viewModel.state.value.issue?.state).isEqualTo(IssueState.OPEN)
        assertThat(viewModel.state.value.stateError).isEqualTo(ForgeError.Http(403, "no"))
        assertThat(viewModel.state.value.isChangingState).isFalse()

        // Trying again takes the error away.
        api.stateFailure = null
        viewModel.toggleOpen()
        assertThat(viewModel.state.value.stateError).isNull()
    }

    @Test
    fun a_conversation_is_closed_once_however_often_it_is_tapped() = test {
        api.access[ref.repo] = RepoAccess.TRIAGE
        val viewModel = openedSignedIn()
        api.gate = kotlinx.coroutines.CompletableDeferred()

        viewModel.toggleOpen()
        viewModel.toggleOpen()
        advanceUntilIdle()
        api.gate?.complete(Unit)
        advanceUntilIdle()

        assertThat(api.stateChanges).hasSize(1)
    }

    @Test
    fun a_merged_pull_request_stays_merged() = test {
        api.access[ref.repo] = RepoAccess.TRIAGE
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.issues[ref] = issueDetails(ref, state = IssueState.MERGED)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.toggleOpen()
        advanceUntilIdle()

        assertThat(api.stateChanges).isEmpty()
    }

    @Test
    fun what_the_reader_may_do_in_the_repository_is_known_once_the_conversation_is() = test {
        api.access[ref.repo] = RepoAccess.WRITE

        val viewModel = openedSignedIn()

        assertThat(viewModel.state.value.access).isEqualTo(RepoAccess.WRITE)
    }
}
