package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
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

/** The whole way through the app, signed in: from a repository's Issues tab to the conversation of the issue opened. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class OpenIssueFlowTest {
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

    @Test
    fun an_issue_written_in_the_form_opens_as_a_conversation_and_back_returns_to_the_repository() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).performClick()
        waitFor("paperclip")
        composeRule.onNodeWithText("paperclip").performClick()
        waitFor("Issues")
        composeRule.onNodeWithText("Issues").performClick()
        waitFor("New issue")

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
