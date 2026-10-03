package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.lifecycle.SavedStateHandle
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.repo.Loadable
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
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import java.time.LocalDate
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
    private val drafts = IssueDrafts()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    @Test
    fun loads_the_issue_and_its_conversation() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)

        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
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

        val viewModel = IssueViewModel(ref, relaunched, SavedStateHandle(), drafts)
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
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
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
        IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        advanceUntilIdle()
        api.issues[ref] = issueDetails(ref, title = "New title")

        val reopened = IssueViewModel(ref, repository, SavedStateHandle(), drafts)

        assertThat(reopened.state.value.issue?.title).isEqualTo("Old title")
        assertThat(reopened.state.value.items).containsExactly(comment(1, "First"))
        advanceUntilIdle()
        assertThat(reopened.state.value.issue?.title).isEqualTo("New title")
    }

    @Test
    fun a_failure_without_anything_to_show_is_an_error() = test {
        api.failure = ForgeError.Network

        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        advanceUntilIdle()

        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.issue).isNull()
    }

    @Test
    fun refresh_reloads_from_the_first_page() = test {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = 2)
        api.pages[ref to 2] = TimelinePage(listOf(comment(2, "Second")), nextPage = null)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
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
        return IssueViewModel(ref, repository, SavedStateHandle(), drafts).also { advanceUntilIdle() }
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
        IssueViewModel(ref, repository, saved, drafts).draftChanged("Half a thought")

        val restored = IssueViewModel(ref, repository, saved, drafts)

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

        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
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
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
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
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
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

    private suspend fun TestScope.managing(
        access: RepoAccess,
        issue: fr.arthurbrugiere.forgeline.core.model.IssueDetails = issueDetails(ref),
        repository: DefaultIssueRepository = this@IssueViewModelTest.repository,
    ): IssueViewModel {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        api.access[ref.repo] = access
        api.issues[ref] = issue
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "First")), nextPage = null)
        return IssueViewModel(ref, repository, SavedStateHandle(), drafts).also { advanceUntilIdle() }
    }

    private val pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1)

    @Test
    fun what_can_be_done_grows_with_the_reader_s_role() = test {
        val anyone = setOf(ConversationAction.TIME_TRACKING)
        val triage = anyone + setOf(
            ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.CLOSE_REASON,
            ConversationAction.DUE_DATE, ConversationAction.DEPENDENCIES,
        )
        val write = triage + setOf(ConversationAction.LOCK, ConversationAction.PIN, ConversationAction.TRANSFER)

        // A role is asked once per session: each one below is a new session.
        suspend fun actionsAs(access: RepoAccess): Set<ConversationAction> {
            val session = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, dao, Clock.systemUTC())
            return managing(access, repository = session).state.value.actions
        }

        // Time is tracked by whoever the repository lets: the forge takes it away from the others.
        assertThat(actionsAs(RepoAccess.NONE)).containsExactlyElementsIn(anyone)
        assertThat(actionsAs(RepoAccess.TRIAGE)).containsExactlyElementsIn(triage)
        assertThat(actionsAs(RepoAccess.WRITE)).containsExactlyElementsIn(write)
        assertThat(actionsAs(RepoAccess.ADMIN)).containsExactlyElementsIn(write + ConversationAction.DELETE)
    }

    @Test
    fun whoever_opened_an_issue_may_say_why_they_close_it_and_nothing_more() = test {
        val viewModel = managing(RepoAccess.NONE, issueDetails(ref).copy(author = ForgeUser("me", null, null)))

        assertThat(viewModel.state.value.actions).containsExactly(ConversationAction.CLOSE_REASON, ConversationAction.TIME_TRACKING)
    }

    @Test
    fun a_pull_request_is_triaged_and_locked_but_not_pinned_moved_deleted_or_closed_with_a_reason() = test {
        val viewModel = managing(RepoAccess.ADMIN, issueDetails(ref).copy(pullRequest = pullRequest))

        assertThat(viewModel.state.value.actions).containsExactly(
            ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.LOCK,
            ConversationAction.DUE_DATE, ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES,
        )
    }

    @Test
    fun a_closed_issue_offers_no_reason_to_close_it() = test {
        val viewModel = managing(RepoAccess.TRIAGE, issueDetails(ref, state = IssueState.CLOSED))

        assertThat(viewModel.state.value.actions).doesNotContain(ConversationAction.CLOSE_REASON)
    }

    @Test
    fun only_what_the_forge_can_do_is_offered() = test {
        api.actions = setOf(ConversationAction.LABELS, ConversationAction.PIN)

        assertThat(managing(RepoAccess.ADMIN).state.value.actions).containsExactly(ConversationAction.LABELS, ConversationAction.PIN)
    }

    @Test
    fun the_repository_s_labels_load_once_when_their_page_opens() = test {
        val viewModel = managing(RepoAccess.TRIAGE)
        assertThat(viewModel.state.value.manage.labels).isEqualTo(Loadable.Idle)

        viewModel.loadLabels()
        assertThat(viewModel.state.value.manage.labels).isEqualTo(Loadable.Loading)
        advanceUntilIdle()
        viewModel.loadLabels()
        advanceUntilIdle()

        assertThat(viewModel.state.value.manage.labels).isEqualTo(Loadable.Loaded(api.repoLabels))
        assertThat(api.calls.count { it == "labels:octo/repo" }).isEqualTo(1)
    }

    @Test
    fun choices_that_failed_to_load_are_asked_again() = test {
        val viewModel = managing(RepoAccess.TRIAGE)
        api.manageFailure = ForgeError.Network
        viewModel.loadAssignable()
        viewModel.loadMilestones()
        advanceUntilIdle()
        assertThat(viewModel.state.value.manage.assignable).isEqualTo(Loadable.Failed(ForgeError.Network))
        assertThat(viewModel.state.value.manage.milestones).isEqualTo(Loadable.Failed(ForgeError.Network))

        api.manageFailure = null
        viewModel.loadAssignable()
        viewModel.loadMilestones()
        advanceUntilIdle()

        assertThat(viewModel.state.value.manage.assignable).isEqualTo(Loadable.Loaded(api.assignableUsers))
        assertThat(viewModel.state.value.manage.milestones).isEqualTo(Loadable.Loaded(api.repoMilestones))
    }

    @Test
    fun a_change_closes_the_sheet_and_reloads_the_conversation() = test {
        val viewModel = managing(RepoAccess.TRIAGE)
        val loadsBefore = api.calls.count { it.startsWith("issue:") }

        viewModel.setLabels(listOf("bug", "question"))
        assertThat(viewModel.state.value.manage.isWorking).isTrue()
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("labels octo/repo#7: bug, question")
        assertThat(viewModel.state.value.manage.isWorking).isFalse()
        assertThat(viewModel.state.value.manage.done).isTrue()
        // The header shows what the forge now says.
        assertThat(viewModel.state.value.issue?.labels?.map { it.name }).containsExactly("bug", "question").inOrder()
        assertThat(api.calls.count { it.startsWith("issue:") }).isEqualTo(loadsBefore + 1)

        viewModel.manageDoneShown()
        assertThat(viewModel.state.value.manage.done).isFalse()
    }

    @Test
    fun assignees_milestone_lock_and_close_reason_go_to_the_forge() = test {
        val viewModel = managing(RepoAccess.WRITE)

        viewModel.setAssignees(listOf("octocat"))
        advanceUntilIdle()
        viewModel.setMilestone(Milestone(4, "2026.10"))
        advanceUntilIdle()
        viewModel.setMilestone(null)
        advanceUntilIdle()
        viewModel.toggleLocked()
        advanceUntilIdle()
        assertThat(viewModel.state.value.issue?.isLocked).isTrue()
        viewModel.toggleLocked()
        advanceUntilIdle()
        viewModel.close(CloseReason.NOT_PLANNED)
        advanceUntilIdle()

        assertThat(api.managed).containsExactly(
            "assignees octo/repo#7: octocat", "milestone octo/repo#7: 2026.10", "milestone octo/repo#7: null", "lock octo/repo#7", "unlock octo/repo#7",
        ).inOrder()
        assertThat(api.stateChanges).containsExactly("octo/repo#7: closed as not_planned")
        assertThat(viewModel.state.value.issue?.state).isEqualTo(IssueState.CLOSED)
    }

    @Test
    fun a_refused_change_keeps_the_sheet_open_and_says_why() = test {
        val viewModel = managing(RepoAccess.TRIAGE)
        api.manageFailure = ForgeError.Http(403, "no")

        viewModel.setLabels(listOf("bug"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.manage.error).isEqualTo(ForgeError.Http(403, "no"))
        assertThat(viewModel.state.value.manage.done).isFalse()
        assertThat(viewModel.state.value.manage.isWorking).isFalse()

        // Opening the sheet again starts clean.
        viewModel.manageOpened()
        assertThat(viewModel.state.value.manage.error).isNull()
    }

    @Test
    fun one_change_at_a_time() = test {
        val viewModel = managing(RepoAccess.TRIAGE)
        api.gate = kotlinx.coroutines.CompletableDeferred()

        viewModel.setLabels(listOf("bug"))
        viewModel.setAssignees(listOf("octocat"))
        advanceUntilIdle()
        api.gate?.complete(Unit)
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("labels octo/repo#7: bug")
    }

    @Test
    fun whether_the_issue_is_pinned_is_asked_when_the_sheet_opens_then_toggled() = test {
        api.pinned += ref
        val viewModel = managing(RepoAccess.WRITE)
        assertThat(viewModel.state.value.manage.pinned).isNull()
        // Not known yet: nothing to toggle.
        viewModel.togglePinned()
        advanceUntilIdle()
        assertThat(api.managed).isEmpty()

        viewModel.manageOpened()
        advanceUntilIdle()
        assertThat(viewModel.state.value.manage.pinned).isTrue()
        viewModel.manageOpened()
        advanceUntilIdle()
        assertThat(api.calls.count { it.startsWith("isPinned:") }).isEqualTo(1)

        viewModel.togglePinned()
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("unpin octo/repo#7")
        assertThat(viewModel.state.value.manage.pinned).isFalse()
    }

    @Test
    fun whoever_can_t_pin_is_not_asked_about_it() = test {
        val viewModel = managing(RepoAccess.TRIAGE)

        viewModel.manageOpened()
        advanceUntilIdle()

        assertThat(api.calls.none { it.startsWith("isPinned:") }).isTrue()
    }

    @Test
    fun a_transferred_issue_hands_over_to_where_it_went() = test {
        val viewModel = managing(RepoAccess.WRITE)
        api.transferredNumber = 12
        val loadsBefore = api.calls.count { it.startsWith("issue:") }

        viewModel.transfer(" docs ")
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("transfer octo/repo#7 to octo/docs")
        assertThat(viewModel.state.value.movedTo).isEqualTo(IssueRef(RepoId("octo", "docs"), 12))
        // It is no longer here to load again.
        assertThat(api.calls.count { it.startsWith("issue:") }).isEqualTo(loadsBefore)
    }

    @Test
    fun a_destination_names_a_repository_of_the_same_owner_or_any_other_by_its_full_name() {
        val from = RepoId("octo", "repo", ForgeInstance.Codeberg)

        assertThat(transferTarget(from, "docs")).isEqualTo(RepoId("octo", "docs", ForgeInstance.Codeberg))
        assertThat(transferTarget(from, " other/docs/ ")).isEqualTo(RepoId("other", "docs", ForgeInstance.Codeberg))
        assertThat(transferTarget(from, "")).isNull()
        assertThat(transferTarget(from, "two words")).isNull()
        assertThat(transferTarget(from, "a/b/c")).isNull()
        assertThat(transferTarget(from, "/docs")).isNull()
        // Where it already is.
        assertThat(transferTarget(from, "Repo")).isNull()
        assertThat(transferTarget(from, "octo/repo")).isNull()
    }

    @Test
    fun a_destination_that_names_nothing_asks_the_forge_nothing() = test {
        val viewModel = managing(RepoAccess.WRITE)

        viewModel.transfer("two words")
        advanceUntilIdle()

        assertThat(api.managed).isEmpty()
        assertThat(viewModel.state.value.manage.isWorking).isFalse()
    }

    @Test
    fun a_deleted_issue_leaves_nothing_to_show() = test {
        val viewModel = managing(RepoAccess.ADMIN)

        viewModel.delete()
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("delete octo/repo#7")
        assertThat(viewModel.state.value.deleted).isTrue()
        assertThat(repository.cached(ref)).isNull()
    }

    @Test
    fun a_refused_deletion_leaves_the_issue_where_it_is() = test {
        val viewModel = managing(RepoAccess.ADMIN)
        api.manageFailure = ForgeError.Http(403, "no")

        viewModel.delete()
        advanceUntilIdle()

        assertThat(viewModel.state.value.deleted).isFalse()
        assertThat(viewModel.state.value.manage.error).isEqualTo(ForgeError.Http(403, "no"))
    }

    @Test
    fun duplicating_starts_a_new_issue_from_this_one_s_title_and_description() = test {
        val viewModel = managing(RepoAccess.NONE, issueDetails(ref, "Crash on start").copy(body = "Steps:\n1. Open it"))

        viewModel.duplicate()

        assertThat(drafts[ref.repo]).isEqualTo(IssueDraft("Crash on start", "Steps:\n1. Open it"))
        assertThat(api.opened).isEmpty()
    }

    @Test
    fun what_a_repository_switches_off_is_not_offered() = test {
        api.switchedOff[ref.repo] = setOf(ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES)

        val actions = managing(RepoAccess.ADMIN).state.value.actions

        assertThat(actions).containsNoneOf(ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES)
        assertThat(actions).contains(ConversationAction.DUE_DATE)
    }

    @Test
    fun a_due_date_is_set_and_taken_away() = test {
        val viewModel = managing(RepoAccess.WRITE)

        viewModel.setDueDate(LocalDate.parse("2026-10-10"))
        advanceUntilIdle()
        assertThat(viewModel.state.value.issue?.dueDate).isEqualTo(LocalDate.parse("2026-10-10"))
        assertThat(viewModel.state.value.manage.done).isTrue()

        viewModel.setDueDate(null)
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("due octo/repo#7: 2026-10-10", "due octo/repo#7: null").inOrder()
        assertThat(viewModel.state.value.issue?.dueDate).isNull()
    }

    @Test
    fun the_timer_starts_then_stops_and_the_time_spent_is_asked_again_each_time() = test {
        val viewModel = managing(RepoAccess.NONE)
        // Nothing to toggle before the forge said whether a timer runs.
        viewModel.toggleTimer()
        advanceUntilIdle()
        assertThat(api.managed).isEmpty()

        viewModel.loadTracking()
        advanceUntilIdle()
        assertThat(viewModel.state.value.manage.tracking).isEqualTo(Loadable.Loaded(TimeTracking(0, null)))
        viewModel.toggleTimer()
        advanceUntilIdle()
        // What was loaded no longer holds.
        assertThat(viewModel.state.value.manage.tracking).isEqualTo(Loadable.Idle)

        viewModel.loadTracking()
        advanceUntilIdle()
        assertThat((viewModel.state.value.manage.tracking as Loadable.Loaded).value.runningSince).isNotNull()
        viewModel.toggleTimer()
        advanceUntilIdle()
        viewModel.loadTracking()
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("start timer octo/repo#7", "stop timer octo/repo#7").inOrder()
        assertThat(viewModel.state.value.manage.tracking).isEqualTo(Loadable.Loaded(TimeTracking(api.timerSeconds, null)))
    }

    @Test
    fun time_already_spent_is_recorded_and_no_time_is_not() = test {
        val viewModel = managing(RepoAccess.NONE)

        viewModel.addTime(0)
        viewModel.addTime(5_400)
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("time octo/repo#7: 5400")
        assertThat(viewModel.state.value.manage.done).isTrue()
    }

    @Test
    fun dependencies_are_listed_added_and_removed() = test {
        val viewModel = managing(RepoAccess.WRITE)
        viewModel.loadDependencies()
        advanceUntilIdle()
        assertThat(viewModel.state.value.manage.dependencies).isEqualTo(Loadable.Loaded(emptyList<LinkedIssue>()))

        viewModel.addDependency("#3")
        advanceUntilIdle()
        viewModel.loadDependencies()
        advanceUntilIdle()
        assertThat((viewModel.state.value.manage.dependencies as Loadable.Loaded).value.map { it.ref }).containsExactly(IssueRef(ref.repo, 3))

        viewModel.removeDependency(IssueRef(ref.repo, 3))
        advanceUntilIdle()
        viewModel.loadDependencies()
        advanceUntilIdle()

        assertThat(api.managed).containsExactly("depend octo/repo#7 on octo/repo#3", "undepend octo/repo#7 on octo/repo#3").inOrder()
        assertThat(viewModel.state.value.manage.dependencies).isEqualTo(Loadable.Loaded(emptyList<LinkedIssue>()))
    }

    @Test
    fun a_dependency_is_named_by_its_number_alone_or_with_its_repository() {
        val codeberg = RepoId("octo", "repo", ForgeInstance.Codeberg)
        val from = IssueRef(codeberg, 7)

        assertThat(dependencyTarget(from, "12")).isEqualTo(IssueRef(codeberg, 12))
        assertThat(dependencyTarget(from, " #12 ")).isEqualTo(IssueRef(codeberg, 12))
        assertThat(dependencyTarget(from, "other/docs#3")).isEqualTo(IssueRef(RepoId("other", "docs", ForgeInstance.Codeberg), 3))
        assertThat(dependencyTarget(from, "")).isNull()
        assertThat(dependencyTarget(from, "twelve")).isNull()
        assertThat(dependencyTarget(from, "#0")).isNull()
        assertThat(dependencyTarget(from, "docs#3")).isNull()
        // An issue can't wait on itself.
        assertThat(dependencyTarget(from, "7")).isNull()
        assertThat(dependencyTarget(from, "Octo/Repo#7")).isNull()
    }

    @Test
    fun a_dependency_that_names_nothing_asks_the_forge_nothing() = test {
        val viewModel = managing(RepoAccess.WRITE)

        viewModel.addDependency("twelve")
        advanceUntilIdle()

        assertThat(api.managed).isEmpty()
    }

    private fun at(minute: Int) = java.time.Instant.parse("2026-09-26T09:00:00Z").plusSeconds(minute * 60L)

    private fun commentAt(id: Long, minute: Int) = comment(id, "Comment $id").copy(createdAt = at(minute))

    /** A conversation of [pages] pages, two comments each, a minute apart; the forge says how many when [counted]. */
    private fun longConversation(pages: Int, counted: Boolean) {
        api.issues[ref] = issueDetails(ref)
        for (page in 1..pages) {
            val first = (page - 1) * 2 + 1L
            api.pages[ref to page] = TimelinePage(
                listOf(commentAt(first, first.toInt()), commentAt(first + 1, first.toInt() + 1)),
                nextPage = (page + 1).takeIf { it <= pages },
                lastPage = pages.takeIf { counted && page < pages },
            )
        }
    }

    private fun IssueViewModel.shownIds() = state.value.items.map { (it as TimelineItem.Comment).id }

    @Test
    fun going_to_the_end_loads_what_is_left_of_the_conversation() = test {
        longConversation(pages = 4, counted = true)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        advanceUntilIdle()
        api.calls.clear()

        viewModel.toEnd()
        advanceUntilIdle()

        // Every page, each once, in the conversation's order.
        assertThat(api.calls).containsExactly("timeline:octo/repo#7@2", "timeline:octo/repo#7@3", "timeline:octo/repo#7@4")
        assertThat(viewModel.shownIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L).inOrder()
        assertThat(viewModel.state.value.nextPage).isNull()
        assertThat(viewModel.state.value.isLoadingMore).isFalse()
        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.End)

        viewModel.scrolled()

        assertThat(viewModel.state.value.scrollTo).isNull()
    }

    @Test
    fun a_forge_that_doesnt_count_its_pages_is_asked_one_after_the_other() = test {
        longConversation(pages = 3, counted = false)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        advanceUntilIdle()

        viewModel.toEnd()
        advanceUntilIdle()

        assertThat(viewModel.shownIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L).inOrder()
        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.End)
    }

    @Test
    fun a_conversation_loaded_whole_goes_to_its_end_without_asking() = test {
        val viewModel = openedSignedIn()
        api.calls.clear()

        viewModel.toEnd()
        advanceUntilIdle()

        assertThat(api.calls.filter { it.startsWith("timeline") }).isEmpty()
        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.End)
    }

    @Test
    fun a_page_that_fails_keeps_what_came_before_it_and_stays_put() = test {
        longConversation(pages = 4, counted = true)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        advanceUntilIdle()
        api.failingPages += 3

        viewModel.toEnd()
        advanceUntilIdle()

        // Page 4 came, but out of place without page 3: it is asked again with it.
        assertThat(viewModel.shownIds()).containsExactly(1L, 2L, 3L, 4L).inOrder()
        assertThat(viewModel.state.value.nextPage).isEqualTo(3)
        assertThat(viewModel.state.value.error).isEqualTo(ForgeError.Network)
        assertThat(viewModel.state.value.scrollTo).isNull()

        api.failingPages.clear()
        viewModel.toEnd()
        advanceUntilIdle()

        assertThat(viewModel.shownIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L).inOrder()
        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.End)
    }

    @Test
    fun an_unread_conversation_opens_at_the_first_entry_since_it_was_read() = test {
        longConversation(pages = 3, counted = true)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)

        // Read just after the fourth comment: the fifth, on a page not loaded yet, is the first new one.
        viewModel.openAtUnread(at(4).plusSeconds(1))
        advanceUntilIdle()

        assertThat(viewModel.shownIds()).hasSize(6)
        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.Item(4))
    }

    @Test
    fun without_knowing_when_it_was_read_it_opens_at_the_latest_entry() = test {
        longConversation(pages = 3, counted = false)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)

        viewModel.openAtUnread(null)
        advanceUntilIdle()

        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.Item(5))
    }

    @Test
    fun with_nothing_newer_than_the_last_read_it_opens_at_the_latest_entry() = test {
        longConversation(pages = 2, counted = true)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)

        viewModel.openAtUnread(at(60))
        advanceUntilIdle()

        assertThat(viewModel.state.value.scrollTo).isEqualTo(ScrollTarget.Item(3))
    }

    @Test
    fun a_conversation_without_a_timeline_stays_at_its_description() = test {
        api.issues[ref] = issueDetails(ref)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)

        viewModel.openAtUnread(null)
        advanceUntilIdle()

        assertThat(viewModel.state.value.scrollTo).isNull()
    }

    @Test
    fun it_opens_at_what_is_new_once_not_each_time_the_screen_comes_back() = test {
        longConversation(pages = 2, counted = true)
        val viewModel = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        viewModel.openAtUnread(null)
        advanceUntilIdle()
        viewModel.scrolled()

        viewModel.openAtUnread(null)
        advanceUntilIdle()

        assertThat(viewModel.state.value.scrollTo).isNull()
    }

    @Test
    fun what_is_new_is_looked_for_in_the_forges_answer_not_in_the_copy_kept() = test {
        // Seen before, when it had one comment.
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(commentAt(1, 1)), null)
        IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        advanceUntilIdle()
        api.pages[ref to 1] = TimelinePage(listOf(commentAt(1, 1), commentAt(2, 2), commentAt(3, 3)), null)

        val reopened = IssueViewModel(ref, repository, SavedStateHandle(), drafts)
        reopened.openAtUnread(at(1).plusSeconds(1))
        advanceUntilIdle()

        assertThat(reopened.state.value.scrollTo).isEqualTo(ScrollTarget.Item(1))
    }
}
