package fr.arthurbrugiere.forgeline.discussion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

    private fun setContent(state: DiscussionUiState, signedIn: Boolean = true) {
        composeRule.setContent {
            ForgelineTheme {
                DiscussionScreen(
                    state = state, signedIn = signedIn,
                    onBack = { events += "back" }, onRefresh = { events += "refresh" }, onOpenRepo = { events += "repo:${it.fullName}" },
                    onOpenUser = { events += "user:$it" }, onOpenInBrowser = { events += "browser:$it" }, onLinkClick = { events += "link:$it" },
                    onSignIn = { events += "sign-in" }, onErrorShown = { events += "error-shown" },
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
    fun a_whole_discussion_does_not_say_some_is_missing_but_says_where_to_write() {
        setContent(loaded)

        reach("Reading only for now: comment on GitHub.")
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
}
