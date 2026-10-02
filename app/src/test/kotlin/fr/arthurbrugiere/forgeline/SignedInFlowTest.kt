package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasContentDescription
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
        waitFor("New issue")
    }

    /** The comment box closes the conversation: scroll down to it. */
    private fun reach(matcher: SemanticsMatcher) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
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
        assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()
    }
}
