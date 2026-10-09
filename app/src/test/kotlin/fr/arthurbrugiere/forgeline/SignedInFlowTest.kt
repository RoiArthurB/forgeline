package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The whole way through the app, signed in: what someone does to a conversation, from the tab they start on. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class SignedInFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var accounts: AccountRepository

    /** The accounts are kept on disk, which the other app tests share: they expect nobody signed in. */
    @After
    fun signOut() = runBlocking { accounts.accounts.first().forEach { accounts.signOut(it.id) } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Signs in to GitHub, then goes to the Issues tab of the repository the fake forge serves. */
    private fun openIssuesSignedIn() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).performClick()
        waitFor("paperclip")
        composeRule.onNodeWithText("paperclip").performClick()
        waitFor("Issues")
        composeRule.onNodeWithText("Issues").performClick()
        // The action shows before the list has loaded: wait for the list, which the tests open a conversation from.
        waitFor("New issue")
        waitFor("Heartbeat recovery escalates too early")
    }

    /** The comment box closes the conversation: scroll down to it. */
    private fun reach(matcher: SemanticsMatcher) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    @Test
    fun one_s_own_work_opens_from_the_you_tab_and_leads_to_its_conversations() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        composeRule.onNode(hasContentDescription("You") and isSelectable()).performClick()
        waitFor("Your work")

        composeRule.onNodeWithText("Your work").performClick()

        waitFor("Waiting for your review")
        composeRule.onNodeWithText("Retry uploads on slow links").assertIsDisplayed()
        composeRule.onNodeWithText("Assigned to you").assertIsDisplayed()
        // Nothing of one's own is open: that heading is left out.
        composeRule.onNodeWithText("Your open pull requests").assertDoesNotExist()
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("Body of #14127")
        // Back returns to the work, not to the You tab.
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("Waiting for your review")
    }

    @Test
    fun a_comment_written_under_a_conversation_joins_it_and_empties_the_box() {
        openIssuesSignedIn()
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("I can reproduce this on every restart.")

        reach(hasSetTextAction())
        composeRule.onNode(hasSetTextAction()).performTextInput("Fixed in 2026.10, thanks.")
        reach(hasText("Comment"))
        composeRule.onNodeWithText("Comment").performClick()

        // Posted: it is the last word of the conversation, and the box is ready for the next one.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Add a comment")).fetchSemanticsNodes().isNotEmpty() }
        waitFor("Fixed in 2026.10, thanks.")
        composeRule.onNodeWithText("I can reproduce this on every restart.", substring = true).assertExists()
        composeRule.onNodeWithText("Comment").assertIsNotEnabled()
    }

    @Test
    fun an_issue_closes_and_reopens_from_its_conversation() {
        // Signed in as whoever opened it.
        openIssuesSignedIn()
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("I can reproduce this on every restart.")
        composeRule.onNodeWithText("Open").assertIsDisplayed()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Close issue")).fetchSemanticsNodes().isNotEmpty() }
        reach(hasText("Close issue"))
        composeRule.onNodeWithText("Close issue").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Reopen issue")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Closed"))
        composeRule.onNodeWithText("Closed").assertIsDisplayed()

        reach(hasText("Reopen issue"))
        composeRule.onNodeWithText("Reopen issue").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Close issue")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openManage() {
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("I can reproduce this on every restart.")
        // What the reader may do is asked once the conversation is known: the full menu follows.
        composeRule.onNode(hasContentDescription("Manage")).performClick()
        waitFor("Delete issue")
    }

    @Test
    fun labels_chosen_in_the_manage_sheet_show_on_the_conversation() {
        openIssuesSignedIn()
        openManage()

        composeRule.onNodeWithText("Labels").performClick()
        waitFor("enhancement")
        composeRule.onNodeWithText("enhancement").performClick()
        composeRule.onNodeWithText("Save").performClick()

        // The sheet closed, and the header wears the label.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Save")).fetchSemanticsNodes().isEmpty() }
        waitFor("enhancement")
        composeRule.onNodeWithText("Delete issue").assertDoesNotExist()
    }

    @Test
    fun a_transferred_issue_opens_where_it_went_and_back_returns_to_the_repository() {
        openIssuesSignedIn()
        openManage()

        composeRule.onNodeWithText("Transfer issue").performClick()
        // The comment box is still there under the sheet: the destination is the field that asks for a repository.
        composeRule.onNode(hasSetTextAction() and hasText("Repository name")).performTextInput("docs")
        composeRule.onNodeWithText("Transfer").performClick()

        waitFor("paperclipai/docs")
        waitFor("Heartbeat recovery escalates too early - #14127")
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("New issue").assertIsDisplayed()
    }

    @Test
    fun a_deleted_issue_leaves_its_conversation_for_the_repository() {
        openIssuesSignedIn()
        openManage()

        composeRule.onNodeWithText("Delete issue").performClick()
        composeRule.onNodeWithText("Delete for good").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("New issue")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("I can reproduce this on every restart.", substring = true).assertDoesNotExist()
    }

    @Test
    fun duplicating_an_issue_opens_the_form_already_written() {
        openIssuesSignedIn()
        openManage()

        composeRule.onNodeWithText("Duplicate issue").performClick()

        waitFor("Open issue")
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").assertIsDisplayed()
        composeRule.onNodeWithText("Body of #14127").assertIsDisplayed()
    }

    @Test
    fun time_added_in_the_tracker_counts_the_next_time_it_opens() {
        openIssuesSignedIn()
        openManage()

        composeRule.onNodeWithText("Time tracker").performClick()
        waitFor("No time recorded yet.")
        composeRule.onNode(hasSetTextAction() and hasText("Hours")).performTextInput("1")
        composeRule.onNode(hasSetTextAction() and hasText("Minutes")).performTextInput("30")
        composeRule.onNodeWithText("Add time").performClick()
        // The sheet closed on the change.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Add time")).fetchSemanticsNodes().isEmpty() }

        composeRule.onNode(hasContentDescription("Manage")).performClick()
        waitFor("Time tracker")
        composeRule.onNodeWithText("Time tracker").performClick()

        waitFor("Time spent so far: 1 h 30 min")
    }

    @Test
    fun a_dependency_added_by_its_number_is_listed_the_next_time() {
        openIssuesSignedIn()
        openManage()

        composeRule.onNodeWithText("Dependencies").performClick()
        waitFor("This doesn't depend on anything yet.")
        composeRule.onNode(hasSetTextAction() and hasText("Issue number, like 12")).performTextInput("#13990")
        composeRule.onNodeWithText("Add dependency").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Add dependency")).fetchSemanticsNodes().isEmpty() }

        composeRule.onNode(hasContentDescription("Manage")).performClick()
        waitFor("Dependencies")
        composeRule.onNodeWithText("Dependencies").performClick()

        waitFor("#13990 · open")
    }

    @Test
    fun an_issue_written_in_the_form_opens_as_a_conversation_and_back_returns_to_the_repository() {
        openIssuesSignedIn()

        composeRule.onNodeWithText("New issue").performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Crash when the list is empty")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("It happens on every start.")
        composeRule.onNodeWithText("Open issue").performClick()

        // The form gave way to the conversation, numbered by the forge and showing what was written.
        waitFor("Crash when the list is empty - #100")
        waitFor("It happens on every start.")
        composeRule.onNodeWithText("Open issue").assertDoesNotExist()

        // Back skips the form: it returns to the repository's issues.
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("New issue").assertIsDisplayed()
        composeRule.onNodeWithText("Search issues").assertIsDisplayed()
        composeRule.onNodeWithText("Open issue").assertDoesNotExist()
    }

    @Test
    fun an_unread_thread_opens_at_what_is_new_and_either_end_is_one_tap_away() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        composeRule.onNode(hasContentDescription("Inbox") and isSelectable()).performClick()
        waitFor("Keep install flags on retry")

        composeRule.onNodeWithText("Keep install flags on retry").performClick()

        // Read up to the 30th remark: the 31st, on the second page, is where it opens.
        waitFor("Remark 31 on the retry.")
        composeRule.onNodeWithText("Remark 31 on the retry.").assertIsDisplayed()
        composeRule.onNodeWithText("Remark 30 on the retry.").assertIsNotDisplayed()

        composeRule.onNodeWithContentDescription("Go to the top").performClick()
        composeRule.onNodeWithText("Keep install flags on retry - #14129").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Go to the latest").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Add a comment")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Remark 40 on the retry.").assertIsDisplayed()
    }

    @Test
    fun a_repository_swiped_away_in_the_inbox_takes_its_threads_and_undo_brings_them_back() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        composeRule.onNode(hasContentDescription("Inbox") and isSelectable()).performClick()
        waitFor("Flaky upload on slow links")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Bump the SDK"))

        composeRule.onNodeWithText("octo/\u2060tools").performTouchInput { swipeLeft() }

        // Both of its threads, and only they.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Flaky upload on slow links")).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("Bump the SDK").assertDoesNotExist()
        composeRule.onNodeWithText("Marked 2 as done").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Keep install flags on retry"))

        composeRule.onNodeWithText("Undo").performClick()

        waitFor("Flaky upload on slow links")
        waitFor("Bump the SDK")
    }

    /** Signs in to GitHub, then opens the repository the fake forge serves. */
    private fun openRepoSignedIn() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).performClick()
        waitFor("paperclip")
        composeRule.onNodeWithText("paperclip").performClick()
        waitFor("Fork")
    }

    @Test
    fun a_repository_is_watched_from_its_page() {
        openRepoSignedIn()
        waitFor("Watch")

        composeRule.onNodeWithText("Watch").performClick()

        waitFor("Watching")
    }

    @Test
    fun a_fork_is_made_once_agreed_to_and_is_where_the_screen_goes() {
        openRepoSignedIn()

        composeRule.onNodeWithText("Fork").performClick()
        waitFor("Fork paperclip?")
        composeRule.onAllNodes(hasText("Fork"))[1].performClick()

        // The fork's own page, in the reader's name; back returns to the repository it was made of.
        waitFor("A fork to try things in")
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("About paperclipai/paperclip")
    }

    @Test
    fun a_discussion_opens_from_its_repository_s_tab_and_back_returns_there() {
        openRepoSignedIn()

        composeRule.onNodeWithText("Discussions").performClick()
        waitFor("How do I page through runs?")
        composeRule.onNodeWithText("How do I page through runs?").performClick()

        waitFor("How do I page through runs? - #412")
        waitFor("The list stops at 30 runs.")
        waitFor("Use the cursor the list gives back.")
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("Q&A · Answered")
    }

    @Test
    fun one_s_own_comment_is_rewritten_where_it_stands_and_the_reader_s_turn_comes_back() {
        openIssuesSignedIn()
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("Mine happens after an update.")

        // The description's menu, hubot's, then the reader's own.
        composeRule.onAllNodes(hasContentDescription("Comment options"))[2].performClick()
        composeRule.onNodeWithText("Edit").performClick()
        waitFor("Rewriting your comment")
        // One field, holding the comment; the reader's turn waits at the end.
        composeRule.onNode(hasSetTextAction()).assert(hasText("Mine happens after an update."))
        composeRule.onNodeWithText("Comment").assertDoesNotExist()

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Rewriting your comment")).fetchSemanticsNodes().isEmpty() }
        waitFor("Mine happens after an update.")
        reach(hasText("Comment"))
        composeRule.onNodeWithText("Comment").assertIsDisplayed()
    }

    @Test
    fun a_conversation_named_by_its_number_in_a_comment_opens_when_tapped() {
        // Issue #11: "#14129" was plain text.
        openIssuesSignedIn()
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("Mine happens after an update.")
        reach(hasText("#14129"))

        composeRule.onNodeWithText("#14129").performClick()

        waitFor("Keep install flags on retry - #14129")
    }

    @Test
    fun a_conversation_is_found_by_its_title_while_a_comment_is_written() {
        // Issue #11: nothing helped find a number while typing.
        openIssuesSignedIn()
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        waitFor("Mine happens after an update.")
        reach(hasSetTextAction())

        composeRule.onNode(hasSetTextAction()).performTextInput("Fixed by #")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("Pull request #14129, Keep install flags on retry")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("Pull request #14129, Keep install flags on retry")).performClick()

        composeRule.onNode(hasSetTextAction()).assert(hasText("Fixed by #14129 "))
    }
}
