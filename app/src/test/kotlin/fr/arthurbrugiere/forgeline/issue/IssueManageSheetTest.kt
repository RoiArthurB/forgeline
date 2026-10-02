package fr.arthurbrugiere.forgeline.issue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class IssueManageSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val ref = IssueRef(RepoId("octo", "repo"), 7)
    private val labels = listOf(Label("bug", "d73a4a"), Label("enhancement", "a2eeef"), Label("question", "d876e3"))
    private val people = listOf(ForgeUser("octocat", null, null), ForgeUser("hubot", null, null))
    private val milestones = listOf(Milestone(4, "2026.10"), Milestone(5, "2026.11"))

    /** An open issue whose reader owns the repository on a forge that can do everything. */
    private val owned = IssueUiState(
        ref,
        issueDetails(ref, "Crash on start").copy(labels = listOf(labels[0]), assignees = listOf(people[0]), milestone = milestones[0]),
        access = RepoAccess.ADMIN,
        canChangeState = true,
        supported = ConversationAction.entries.toSet(),
        manage = ManageUiState(Loadable.Loaded(labels), Loadable.Loaded(people), Loadable.Loaded(milestones), pinned = false),
    )
    private val shown = mutableStateOf(owned)

    private val actions = ManageActions(
        onLoadLabels = { events += "load-labels" },
        onLoadAssignable = { events += "load-assignable" },
        onLoadMilestones = { events += "load-milestones" },
        onSetLabels = { events += "labels:${it.joinToString()}" },
        onSetAssignees = { events += "assignees:${it.joinToString()}" },
        onSetMilestone = { events += "milestone:${it?.title}" },
        onClose = { events += "close:$it" },
        onToggleLocked = { events += "lock" },
        onTogglePinned = { events += "pin" },
        onDuplicate = { events += "duplicate" },
        onTransfer = { events += "transfer:$it" },
        onDelete = { events += "delete" },
    )

    private fun setContent(state: IssueUiState = owned, page: ManagePage = ManagePage.MENU) {
        shown.value = state
        composeRule.setContent { IssueManageContent(shown.value, actions, onDismiss = { events += "dismiss" }, startPage = page) }
    }

    private fun choice(text: String) = composeRule.onNode(isToggleable() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)

    @Test
    fun the_owner_of_a_repository_can_do_everything_to_an_issue() {
        setContent()

        composeRule.onNode(hasText("Manage") and isHeading()).assertIsDisplayed()
        listOf(
            "Labels", "Assignees", "Milestone", "Close as not planned", "Close as duplicate", "Lock conversation", "Pin issue",
            "Duplicate issue", "Transfer issue", "Delete issue",
        ).forEach { composeRule.onNodeWithText(it).assertExists() }
        // What it has now is said under each.
        composeRule.onNodeWithText("bug").assertExists()
        composeRule.onNodeWithText("octocat").assertExists()
        composeRule.onNodeWithText("2026.10").assertExists()
    }

    @Test
    fun only_what_the_reader_may_do_is_listed() {
        // Triage: no locking, pinning, transferring or deleting.
        setContent(owned.copy(access = RepoAccess.TRIAGE))

        composeRule.onNodeWithText("Labels").assertExists()
        composeRule.onNodeWithText("Close as not planned").assertExists()
        composeRule.onNodeWithText("Duplicate issue").assertExists()
        listOf("Lock conversation", "Pin issue", "Transfer issue", "Delete issue").forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun anyone_signed_in_can_start_another_issue_from_this_one() {
        setContent(owned.copy(access = RepoAccess.NONE, canChangeState = false))

        composeRule.onNodeWithText("Labels").assertDoesNotExist()
        composeRule.onNodeWithText("Duplicate issue").performClick()

        assertThat(events).containsExactly("duplicate", "dismiss").inOrder()
    }

    @Test
    fun a_forge_that_can_t_lock_or_transfer_offers_neither() {
        setContent(owned.copy(supported = ConversationAction.entries.toSet() - ConversationAction.LOCK - ConversationAction.TRANSFER - ConversationAction.CLOSE_REASON))

        listOf("Lock conversation", "Transfer issue", "Close as not planned", "Close as duplicate").forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
        composeRule.onNodeWithText("Pin issue").assertExists()
        composeRule.onNodeWithText("Delete issue").assertExists()
    }

    @Test
    fun a_pull_request_is_not_duplicated_pinned_moved_or_deleted() {
        val pull = owned.issue!!.copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))
        setContent(owned.copy(issue = pull))

        composeRule.onNodeWithText("Labels").assertExists()
        composeRule.onNodeWithText("Lock conversation").assertExists()
        listOf("Duplicate issue", "Pin issue", "Transfer issue", "Delete issue", "Close as not planned").forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun nothing_set_reads_none() {
        setContent(owned.copy(issue = issueDetails(ref, "Crash on start")))

        assertThat(composeRule.onAllNodes(hasText("None")).fetchSemanticsNodes()).hasSize(3)
    }

    @Test
    fun closing_with_a_reason_locking_and_pinning_act_at_once() {
        setContent()

        composeRule.onNodeWithText("Close as not planned").performClick()
        composeRule.onNodeWithText("Close as duplicate").performClick()
        composeRule.onNodeWithText("Lock conversation").performClick()
        composeRule.onNodeWithText("Pin issue").performClick()

        assertThat(events).containsExactly("close:${CloseReason.NOT_PLANNED}", "close:${CloseReason.DUPLICATE}", "lock", "pin").inOrder()
    }

    @Test
    fun a_locked_pinned_issue_offers_to_undo_both() {
        setContent(owned.copy(issue = owned.issue!!.copy(isLocked = true), manage = owned.manage.copy(pinned = true)))

        composeRule.onNodeWithText("Unlock conversation").assertExists()
        composeRule.onNodeWithText("Unpin issue").assertExists()
    }

    @Test
    fun pinning_waits_until_the_forge_has_said_whether_it_is_pinned() {
        setContent(owned.copy(manage = owned.manage.copy(pinned = null)))

        composeRule.onNodeWithText("Pin issue").performClick()

        assertThat(events).isEmpty()
    }

    @Test
    fun while_a_change_is_on_its_way_nothing_else_can_be_started() {
        setContent(owned.copy(manage = owned.manage.copy(isWorking = true)))

        composeRule.onNodeWithText("Lock conversation").performClick()
        composeRule.onNodeWithText("Labels").performClick()

        assertThat(events).isEmpty()
    }

    @Test
    fun the_labels_page_loads_the_repository_s_labels_and_checks_the_ones_worn() {
        setContent()

        composeRule.onNodeWithText("Labels").performClick()

        assertThat(events).containsExactly("load-labels")
        composeRule.onNode(hasText("Labels") and isHeading()).assertIsDisplayed()
        choice("bug").assertIsOn()
        choice("enhancement").assertIsOff()
    }

    @Test
    fun labels_are_chosen_then_saved_whole() {
        setContent(page = ManagePage.LABELS)

        choice("question").performClick()
        choice("bug").performClick()
        choice("enhancement").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("labels:question, enhancement")
    }

    @Test
    fun assignees_are_chosen_then_saved_whole() {
        setContent(page = ManagePage.ASSIGNEES)
        choice("octocat").assertIsOn()

        choice("hubot").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("assignees:octocat, hubot")
    }

    @Test
    fun choosing_a_milestone_saves_it_and_no_milestone_is_a_choice() {
        setContent(page = ManagePage.MILESTONE)
        choice("2026.10").assertIsOn()

        choice("2026.11").performClick()
        choice("No milestone").performClick()

        assertThat(events).containsExactly("milestone:2026.11", "milestone:null").inOrder()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun choices_still_loading_or_that_failed_say_so() {
        setContent(owned.copy(manage = owned.manage.copy(labels = Loadable.Loading)), page = ManagePage.LABELS)
        composeRule.onNodeWithContentDescription("Loading the choices").assertExists()
        composeRule.onNodeWithText("Save").assertDoesNotExist()

        shown.value = owned.copy(manage = owned.manage.copy(labels = Loadable.Failed(ForgeError.Network)))
        composeRule.onNodeWithText("Couldn't load the choices").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("load-labels")
    }

    @Test
    fun a_repository_without_labels_says_so() {
        setContent(owned.copy(manage = owned.manage.copy(labels = Loadable.Loaded(emptyList()))), page = ManagePage.LABELS)

        composeRule.onNodeWithText("This repository has no labels.").assertIsDisplayed()
    }

    @Test
    fun a_page_goes_back_to_the_menu() {
        setContent(page = ManagePage.LABELS)

        composeRule.onNodeWithContentDescription("Back to Manage").performClick()

        composeRule.onNode(hasText("Manage") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun transferring_takes_a_destination_that_names_another_repository() {
        setContent(page = ManagePage.TRANSFER)
        composeRule.onNodeWithText("another repository of octo", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Transfer").assertIsNotEnabled()

        // Where it already is names nothing to move to.
        composeRule.onNode(hasSetTextAction()).performTextInput("repo")
        composeRule.onNodeWithText("Transfer").assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("-docs")
        composeRule.onNodeWithText("Transfer").assertIsEnabled().performClick()

        assertThat(events).containsExactly("transfer:repo-docs")
    }

    @Test
    fun deleting_says_it_can_t_be_undone_and_takes_a_second_press() {
        setContent()

        composeRule.onNodeWithText("Delete issue").performClick()
        assertThat(events).isEmpty()
        composeRule.onNodeWithText("It can't be undone.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Delete for good").performClick()

        assertThat(events).containsExactly("delete")
    }

    @Test
    fun deleting_can_be_called_off() {
        setContent(page = ManagePage.DELETE)

        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(events).isEmpty()
        composeRule.onNode(hasText("Manage") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun a_refused_change_says_the_role_doesn_t_allow_it() {
        setContent(owned.copy(manage = owned.manage.copy(error = ForgeError.Http(403, "no"))))

        composeRule.onNodeWithText("your role in this repository doesn't allow it", substring = true).assertExists()
    }

    @Test
    fun a_lost_connection_says_so() {
        setContent(owned.copy(manage = owned.manage.copy(error = ForgeError.Network)), page = ManagePage.LABELS)

        composeRule.onNodeWithText("check your connection", substring = true).assertExists()
    }

    @Test
    fun every_row_and_choice_is_large_enough_to_press() {
        setContent()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun whether_there_is_anything_to_manage() {
        val nobody = owned.copy(access = RepoAccess.NONE, canChangeState = false)
        val pull = nobody.issue!!.copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))

        // An issue can always be duplicated by someone signed in; a pull request needs a role.
        assertThat(nobody.canManage(signedIn = true)).isTrue()
        assertThat(nobody.canManage(signedIn = false)).isFalse()
        assertThat(nobody.copy(issue = pull).canManage(signedIn = true)).isFalse()
        assertThat(owned.copy(issue = pull).canManage(signedIn = true)).isTrue()
        assertThat(IssueUiState(ref).canManage(signedIn = true)).isFalse()
        assertThat(owned.copy(issue = owned.issue!!.copy(state = IssueState.CLOSED)).canManage(signedIn = true)).isTrue()
    }
}
