package fr.arthurbrugiere.forgeline.issue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class NewIssueScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val empty = NewIssueUiState(RepoId("octo", "repo"))
    private val shown = mutableStateOf(empty)

    private fun setContent(state: NewIssueUiState = empty, signedIn: Boolean = true) {
        shown.value = state
        composeRule.setContent {
            NewIssueScreen(
                state = shown.value,
                signedIn = signedIn,
                onTitleChange = { events += "title:$it" },
                onBodyChange = { events += "body:$it" },
                onSend = { events += "send" },
                onSignIn = { events += "signin" },
                onBack = { events += "back" },
            )
        }
    }

    @Test
    fun says_what_it_opens_and_where() {
        setContent(NewIssueUiState(RepoId("octo", "repo", ForgeInstance.Codeberg)))

        composeRule.onNode(hasText("New issue") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("octo/repo").assertIsDisplayed()
        composeRule.onNodeWithText("Codeberg").assertIsDisplayed()
    }

    @Test
    fun the_title_and_the_description_are_written_separately() {
        setContent()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Crash")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("Steps")

        // The field is fed back what the screen holds (nothing, here), so only what was typed is looked for.
        assertThat(events).containsAtLeast("title:Crash", "body:Steps").inOrder()
    }

    @Test
    fun an_issue_without_a_title_can_t_be_sent_and_a_titled_one_can() {
        setContent(empty.copy(body = "Steps"))
        composeRule.onNodeWithText("Open issue").assertIsNotEnabled()

        shown.value = empty.copy(title = "Crash")
        composeRule.onNodeWithText("Open issue").assertIsEnabled().performClick()

        assertThat(events).containsExactly("send")
    }

    @Test
    fun while_it_is_sent_the_action_says_so_and_waits() {
        setContent(empty.copy(title = "Crash", isSending = true))

        composeRule.onNodeWithText("Sending").assertIsNotEnabled()
        composeRule.onNodeWithText("Open issue").assertDoesNotExist()
    }

    @Test
    fun a_refusal_says_the_repository_may_not_take_issues() {
        setContent(empty.copy(title = "Crash", error = ForgeError.Http(410, "Issues are disabled for this repo")))

        composeRule.onNodeWithText("You can't open an issue here.", substring = true).assertIsDisplayed()
    }

    @Test
    fun a_lost_connection_says_the_issue_is_kept() {
        setContent(empty.copy(title = "Crash", error = ForgeError.Network))

        composeRule.onNodeWithText("Your issue is kept.", substring = true).assertIsDisplayed()
    }

    @Test
    fun an_expired_sign_in_names_the_forge() {
        setContent(NewIssueUiState(RepoId("octo", "repo", ForgeInstance.Codeberg), title = "Crash", error = ForgeError.Unauthorized))

        composeRule.onNodeWithText("Your sign-in to Codeberg no longer works.", substring = true).assertIsDisplayed()
    }

    @Test
    fun signed_out_of_the_forge_it_offers_to_sign_in_instead_of_fields() {
        setContent(NewIssueUiState(RepoId("octo", "repo", ForgeInstance.Codeberg)), signedIn = false)

        composeRule.onNodeWithText("Sign in to Codeberg to open an issue.").assertIsDisplayed()
        assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isEmpty()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("signin")
    }

    @Test
    fun back_leaves_the_form() {
        setContent()

        composeRule.onNodeWithContentDescription("Navigate up").performClick()

        assertThat(events).containsExactly("back")
    }

    @Test
    fun targets_are_large_enough() {
        setContent(empty.copy(title = "Crash"))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    private val editing = empty.copy(title = "Crash on start", body = "Steps", editing = fr.arthurbrugiere.forgeline.core.model.IssueRef(empty.repo, 7))

    @Test
    fun editing_says_so_and_starts_from_what_is_there() {
        setContent(editing)

        composeRule.onNode(hasText("Edit issue") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Crash on start").assertIsDisplayed()
        composeRule.onNodeWithText("Steps").assertIsDisplayed()
        // Nothing is opened: the action saves.
        composeRule.onNodeWithText("Save changes").assertIsEnabled().performClick()
        assertThat(events).containsExactly("send")
    }

    @Test
    fun a_pull_request_being_edited_is_named_as_one() {
        setContent(editing.copy(isPullRequest = true))

        composeRule.onNode(hasText("Edit pull request") and isHeading()).assertIsDisplayed()
    }

    @Test
    fun changes_on_their_way_can_t_be_sent_again() {
        setContent(editing.copy(isSending = true))

        composeRule.onNodeWithText("Saving").assertIsNotEnabled()
    }

    @Test
    fun changes_that_weren_t_saved_say_why_in_their_own_words() {
        // Not "you can't open an issue here": none is being opened.
        setContent(editing.copy(error = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(403, "no")))
        composeRule.onNodeWithText("You can't change this. Your sign-in may not allow it.").assertIsDisplayed()

        shown.value = editing.copy(error = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Network)
        composeRule.onNodeWithText("Not saved: check your connection and save again. Your changes are kept.").assertIsDisplayed()
    }
}
