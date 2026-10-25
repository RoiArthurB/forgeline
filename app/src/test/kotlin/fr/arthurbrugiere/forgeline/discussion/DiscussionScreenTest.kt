package fr.arthurbrugiere.forgeline.discussion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.discussionComment
import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class DiscussionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "repo")
    private val summary = discussionSummary(7, "How do I page?", category = "Q&A", comments = 2, isAnswered = true, upvotes = 3)
    private val read = Discussion(
        summary, "I can't find it in the docs.",
        listOf(
            discussionComment("c1", "Use the cursor.", login = "bob", isAnswer = true, upvotes = 5, replies = listOf(discussionComment("r1", "Thanks, that was it.", login = "alice"))),
            discussionComment("c2", "Same question here.", login = "carol"),
        ),
    )
    private val loaded = DiscussionUiState(repo, 7, summary, read, isLoading = false)

    private fun setContent(state: DiscussionUiState, signedIn: Boolean = true) = setContent(mutableStateOf(state), signedIn)

    private fun setContent(state: androidx.compose.runtime.State<DiscussionUiState>, signedIn: Boolean = true) {
        composeRule.setContent {
            ForgelineTheme {
                DiscussionScreen(
                    state = state.value, signedIn = signedIn,
                    onBack = { events += "back" }, onRefresh = { events += "refresh" }, onOpenRepo = { events += "repo:${it.fullName}" },
                    onOpenUser = { events += "user:$it" }, onOpenInBrowser = { events += "browser:$it" }, onLinkClick = { events += "link:$it" },
                    onSignIn = { events += "sign-in" }, onErrorShown = { events += "error-shown" },
                    onSend = { body, under -> events += "send:${under?.id ?: "discussion"}:$body" },
                    onSetAnswer = { comment, answer -> events += "answer:${comment.id}:$answer" },
                    onSendErrorShown = { events += "send-error-shown" },
                    nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun shown(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun a_discussion_is_headed_by_what_it_asks_where_it_was_filed_and_who_started_it() {
        setContent(loaded)

        composeRule.onNodeWithText("How do I page? - #7").assertIsDisplayed()
        composeRule.onNodeWithText("Q&A").assertIsDisplayed()
        composeRule.onNodeWithText("Answered").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("3 upvotes")).assertIsDisplayed()
        composeRule.onNodeWithText("alice started this 2 hours ago").assertIsDisplayed()
    }

    @Test
    fun its_text_its_comments_and_their_replies_are_read_in_order_and_the_answer_says_so() {
        setContent(loaded)

        composeRule.waitUntil(5_000) { shown("I can't find it in the docs.") }
        reach("Use the cursor.")
        assertThat(composeRule.onAllNodesWithText("Answer", useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
        reach("Same question here.")
        assertThat(shown("Use the cursor.")).isTrue()
        assertThat(shown("Thanks, that was it.")).isTrue()
        val answer = composeRule.onNodeWithText("Use the cursor.").fetchSemanticsNode().boundsInRoot
        val reply = composeRule.onNodeWithText("Thanks, that was it.").fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithText("Same question here.").fetchSemanticsNode().boundsInRoot
        assertThat(answer.top).isLessThan(reply.top)
        assertThat(reply.top).isLessThan(next.top)
        // A reply stands in from the comment it answers.
        assertThat(reply.left).isGreaterThan(next.left - 1f)
    }

    @Test
    fun a_discussion_nobody_answered_yet_says_so() {
        setContent(loaded.copy(discussion = read.copy(summary = summary.copy(comments = 0), comments = emptyList())))

        reach("No comments yet.")
        composeRule.onNodeWithText("No comments yet.").assertIsDisplayed()
    }

    @Test
    fun when_only_the_first_comments_were_read_it_says_so_and_offers_the_rest_on_the_forge() {
        setContent(loaded.copy(discussion = read.copy(summary = summary.copy(comments = 80))))

        reach("Only the first comments and replies are shown here.")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Open on GitHub"))
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/discussions/7")
    }

    @Test
    fun a_whole_discussion_does_not_say_some_is_missing() {
        setContent(loaded)

        reach("Open on GitHub")
        assertThat(shown("Only the first comments")).isFalse()
    }

    @Test
    fun while_it_loads_what_its_list_said_heads_the_page() {
        setContent(DiscussionUiState(repo, 7, summary = summary, isLoading = true))

        composeRule.onNodeWithText("How do I page? - #7").assertIsDisplayed()
    }

    @Test
    fun opened_from_a_link_it_is_headed_by_its_number_until_it_is_read() {
        setContent(DiscussionUiState(repo, 7, isLoading = true))

        composeRule.onNodeWithText("#7").assertIsDisplayed()
    }

    @Test
    fun signed_out_it_says_an_account_is_needed_and_offers_to_sign_in_or_the_forge() {
        setContent(DiscussionUiState(repo, 7, isLoading = false, error = ForgeError.Unauthorized), signedIn = false)

        composeRule.onNodeWithText("Sign in to read discussions").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("sign-in", "browser:https://github.com/octo/repo/discussions/7").inOrder()
    }

    @Test
    fun one_that_is_gone_says_so_and_offers_the_forge_rather_than_trying_again() {
        setContent(DiscussionUiState(repo, 7, isLoading = false, error = ForgeError.Http(404, "Not Found")))

        composeRule.onNodeWithText("It may have been deleted, or moved to an issue.").assertIsDisplayed()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/discussions/7")
    }

    @Test
    fun one_that_couldn_t_be_read_offline_can_be_asked_for_again() {
        setContent(DiscussionUiState(repo, 7, isLoading = false, error = ForgeError.Network))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun a_refresh_that_failed_is_only_said_over_what_was_read() {
        setContent(loaded.copy(error = ForgeError.Network))

        composeRule.waitUntil(5_000) { shown("Couldn't refresh") || events.contains("error-shown") }
        assertThat(events).contains("error-shown")
        assertThat(shown("Couldn't load the discussion")).isFalse()
    }

    @Test
    fun its_repository_and_the_people_in_it_open() {
        setContent(loaded)

        composeRule.onNodeWithText("octo/repo").performClick()
        reach("Same question here.")
        composeRule.onNodeWithText("carol").performClick()

        assertThat(events).containsExactly("repo:octo/repo", "user:carol").inOrder()
    }

    @Test
    fun every_target_is_large_enough_to_tap() {
        setContent(loaded)

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun a_discussion_is_shared_by_its_address_under_what_it_asks() {
        setContent(loaded)

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        com.google.common.truth.Truth.assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octo/repo/discussions/7" to "How do I page?")
    }

    private val writable = loaded.copy(
        discussion = read.copy(
            id = "D_1",
            comments = listOf(read.comments[0].copy(canUnmarkAnswer = true), read.comments[1].copy(canMarkAnswer = true)),
        ),
    )

    @Test
    fun a_comment_is_written_at_the_end_and_its_field_emptied_once_sent() {
        val state = mutableStateOf(writable)
        setContent(state)

        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performTextInput("Found it, thanks.")
        composeRule.onNodeWithContentDescription("Send comment").performClick()
        assertThat(events).containsExactly("send:discussion:Found it, thanks.")

        state.value = writable.copy(sent = DiscussionViewModel.COMMENT to 1)
        composeRule.waitForIdle()
        assertThat(shown("Found it, thanks.")).isFalse()
    }

    @Test
    fun a_reply_is_written_under_the_comment_it_answers() {
        setContent(writable)

        reach("Same question here.")
        composeRule.onAllNodesWithText("Reply")[1].performClick()
        composeRule.onNodeWithText("Reply to carol").performTextInput("Did the cursor help?")
        composeRule.onNodeWithContentDescription("Send reply").performClick()

        assertThat(events).containsExactly("send:c2:Did the cursor help?")
    }

    @Test
    fun an_answer_is_picked_or_taken_back_where_the_reader_may() {
        setContent(writable)

        reach("No longer the answer")
        composeRule.onNodeWithText("No longer the answer").performClick()
        reach("Mark as answer")
        composeRule.onNodeWithText("Mark as answer").performClick()

        assertThat(events).containsExactly("answer:c1:false", "answer:c2:true").inOrder()
    }

    @Test
    fun a_locked_discussion_says_so_and_offers_no_field_nor_reply() {
        setContent(writable.copy(discussion = writable.discussion!!.copy(locked = true)))

        reach("This discussion is locked")
        assertThat(shown("Comment on this discussion")).isFalse()
        assertThat(shown("Reply")).isFalse()
        // Picking the answer is still the author's.
        assertThat(shown("Mark as answer")).isTrue()
    }

    @Test
    fun a_discussion_that_can_t_be_commented_on_from_here_offers_no_field() {
        // Read without what the forge names it by.
        setContent(loaded)

        reach("Open on GitHub")
        assertThat(shown("Comment on this discussion")).isFalse()
        assertThat(shown("Reply")).isFalse()
    }

    @Test
    fun a_comment_that_didn_t_go_through_says_why_and_stays_written() {
        val state = mutableStateOf(writable)
        setContent(state)
        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performTextInput("Hello")

        state.value = writable.copy(sendError = ForgeError.Http(403, "Discussion is locked"))

        composeRule.waitUntil(5_000) { shown("You can't comment here.") }
        assertThat(events).contains("send-error-shown")
        assertThat(shown("Hello")).isTrue()
    }
}
