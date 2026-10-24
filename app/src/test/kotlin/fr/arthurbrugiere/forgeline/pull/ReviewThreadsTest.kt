package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
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
class ReviewThreadsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val at = Instant.parse("2026-10-20T10:00:00Z")

    private val open = ReviewThread(
        "T1", "src/Upload.kt", 3, onOldSide = false,
        hunk = "@@ -1,2 +1,3 @@\n fun upload() {\n-    send(file)\n+    retry { send(file) }",
        comments = listOf(
            ThreadComment(1, ForgeUser("hubot", null, null), "Why not three times?", at),
            ThreadComment(2, ForgeUser("octocat", null, null), "Flaky links.", at),
        ),
        resolved = false, canResolve = true,
    )
    private val resolved = ReviewThread(
        "T2", "docs/retry.md", 1, onOldSide = false, hunk = null,
        comments = listOf(ThreadComment(3, ForgeUser("hubot", null, null), "Typo here", at)), resolved = true, canResolve = true,
    )

    private val actions = PullActions(
        onReply = { thread, body -> events += "reply:${thread.id}:$body" },
        onSetResolved = { thread, resolved -> events += "resolved:${thread.id}:$resolved" },
    )

    private fun setContent(threads: List<ReviewThread>, state: PullRequestUiState = PullRequestUiState(resolvesThreads = true), canReply: Boolean = true) =
        composeRule.setContent {
            ForgelineTheme {
                ReviewThreads(threads, state, actions, canReply, ReadmeContext("https://raw.example/", "https://blob.example/"), at.toEpochMilli(), onOpenUser = {}, onLinkClick = {})
            }
        }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun an_open_thread_says_where_it_is_shows_the_code_and_what_was_said() {
        setContent(listOf(open, resolved))

        composeRule.onNodeWithText("1 open thread").assertIsDisplayed()
        composeRule.onNodeWithText("src/Upload.kt:3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, added:     retry { send(file) }").assertIsDisplayed()
        waitFor("Why not three times?")
        composeRule.onNodeWithText("Flaky links.").assertIsDisplayed()
        // The resolved one waits behind a row.
        composeRule.onNodeWithText("Typo here").assertDoesNotExist()
        composeRule.onNodeWithText("Show 1 resolved thread").performClick()
        waitFor("Typo here")
        composeRule.onNodeWithText("docs/retry.md:1 · Resolved").assertIsDisplayed()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun a_suggestion_shows_as_the_change_it_would_make() {
        val suggesting = open.copy(line = 2, comments = listOf(ThreadComment(1, ForgeUser("hubot", null, null), "Three times:\n```suggestion\n    retry(3) { send(file) }\n```", at)))
        setContent(listOf(suggesting))

        waitFor("Three times:")
        composeRule.onNodeWithText("Suggested change").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, removed:     retry { send(file) }").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, added:     retry(3) { send(file) }").assertIsDisplayed()
        // The fence itself isn't shown as code.
        composeRule.onAllNodes(hasText("suggestion", substring = true)).assertCountEquals(0)
    }

    @Test
    fun a_suggestion_to_take_lines_away_says_so() {
        setContent(listOf(open.copy(line = 2, comments = listOf(ThreadComment(1, ForgeUser("hubot", null, null), "```suggestion\n```", at)))))

        composeRule.onNodeWithText("Suggests removing these lines").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, removed:     retry { send(file) }").assertIsDisplayed()
    }

    @Test
    fun a_thread_is_answered_where_it_stands() {
        setContent(listOf(open))

        composeRule.onNodeWithText("Reply").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Three is plenty.")
        composeRule.onNodeWithContentDescription("Send reply").performClick()

        assertThat(events).containsExactly("reply:T1:Three is plenty.")
    }

    @Test
    fun a_thread_is_resolved_and_a_resolved_one_reopened() {
        setContent(listOf(open, resolved))

        composeRule.onNodeWithText("Resolve").performClick()
        composeRule.onNodeWithText("Show 1 resolved thread").performClick()
        composeRule.onNodeWithText("Reopen").performClick()

        assertThat(events).containsExactly("resolved:T1:true", "resolved:T2:false").inOrder()
    }

    @Test
    fun a_thread_being_changed_can_t_be_pressed_again() {
        setContent(listOf(open), PullRequestUiState(resolvesThreads = true, busyThread = "T1"))

        composeRule.onNodeWithText("Resolve").assertIsNotEnabled()
    }

    @Test
    fun a_forge_that_can_t_resolve_or_a_thread_the_reader_may_not_offers_only_to_reply() {
        setContent(listOf(open), PullRequestUiState(resolvesThreads = false))

        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Resolve").assertDoesNotExist()
    }

    @Test
    fun signed_out_threads_are_read_and_nothing_more() {
        setContent(listOf(open), canReply = false)

        waitFor("Why not three times?")
        composeRule.onNodeWithText("Reply").assertDoesNotExist()
        composeRule.onNodeWithText("Resolve").assertDoesNotExist()
    }

    @Test
    fun threads_all_resolved_say_so() {
        setContent(listOf(resolved))

        composeRule.onNodeWithText("1 thread, resolved").assertIsDisplayed()
    }
}
