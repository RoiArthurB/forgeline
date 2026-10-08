package fr.arthurbrugiere.forgeline.issue

import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.hasContentDescription
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
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
                suggestions = suggested.value,
                onReferenceTyped = { typedReferences += it },
            )
        }
    }

    private val typedReferences = mutableListOf<TypedReference?>()
    private val suggested = mutableStateOf(ReferenceOffer())

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

    private fun previews() = composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(WRITING_PREVIEW_TAG)).fetchSemanticsNodes().size

    @Test
    fun the_description_can_be_read_as_it_will_look_and_the_title_stays_a_field() {
        setContent(empty.copy(title = "Crash", body = "Steps:\n\n1. Open **it**"))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Preview")).performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Open it", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(previews()).isEqualTo(1)
        // Only the description is Markdown: the title is still written.
        assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(1)
        composeRule.onNodeWithText("Open issue").assertIsEnabled()

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Back to writing")).performClick()

        assertThat(previews()).isEqualTo(0)
        assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun an_issue_without_a_description_has_nothing_to_preview() {
        setContent(empty.copy(title = "Crash"))

        assertThat(composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Preview")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun an_issue_refused_while_previewed_still_says_why() {
        setContent(empty.copy(title = "Crash", body = "Steps"))
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Preview")).performClick()

        shown.value = empty.copy(title = "Crash", body = "Steps", error = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Network)

        composeRule.onNodeWithText("Not sent: check your connection and send again. Your issue is kept.").assertIsDisplayed()
    }

    @Test
    fun every_action_of_the_form_is_large_enough_to_tap() {
        setContent(empty.copy(title = "Crash", body = "Steps"))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    // Issue #11: conversations named by number, found while typed.

    @Test
    fun a_reference_typed_in_the_description_says_which() {
        setContent()

        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("Like #12")

        assertThat(events).containsExactly("body:Like #12")
        assertThat(typedReferences).containsExactly(TypedReference(5, '#', "12"))
    }

    @Test
    fun a_conversation_suggested_for_the_description_writes_its_number_when_picked() {
        setContent(empty.copy(title = "Crash", body = "Like #hea"))
        suggested.value = ReferenceOffer(listOf(issueSummary(14127, "Heartbeat recovery escalates too early")))
        composeRule.waitForIdle()

        composeRule.onNode(hasContentDescription("Issue #14127, Heartbeat recovery escalates too early")).performClick()

        assertThat(events).containsExactly("body:Like #14127 ")
        assertThat(typedReferences.last()).isNull()
    }

    @Test
    fun a_title_takes_no_reference() {
        // A forge links none there.
        setContent()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Like #12")

        assertThat(typedReferences).isEmpty()
    }

    @Test
    fun typing_in_the_middle_of_a_description_keeps_the_cursor_where_it_is() {
        // Regression guard: the field now holds its own cursor, which a description coming back from the state must not move.
        setContent(empty.copy(body = "Hello world"))
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInputSelection(androidx.compose.ui.text.TextRange(5))
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput(",")
        shown.value = shown.value.copy(body = "Hello, world")
        composeRule.waitForIdle()

        val selection = composeRule.onAllNodes(hasSetTextAction())[1].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertThat(events).containsExactly("body:Hello, world")
        assertThat(selection).isEqualTo(androidx.compose.ui.text.TextRange(6))
    }
}
