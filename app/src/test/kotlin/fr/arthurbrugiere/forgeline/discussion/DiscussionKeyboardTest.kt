package fr.arthurbrugiere.forgeline.discussion

import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.discussionComment
import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.navigation.TopLevelDestination
import fr.arthurbrugiere.forgeline.ui.SoftNavigation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Writing in a discussion with the keyboard up: the window is drawn edge to edge, so it isn't resized for the keyboard
 * and the screen has to make room for it itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class DiscussionKeyboardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val summary = discussionSummary(7, "How do I page?", category = "Q&A", comments = 2, isAnswered = true, upvotes = 3)
    private val writable = DiscussionUiState(
        RepoId("octo", "repo"), 7, summary,
        Discussion(
            summary, "I can't find it in the docs.",
            listOf(
                discussionComment("c1", "Use the cursor.", login = "bob", isAnswer = true, upvotes = 5, replies = listOf(discussionComment("r1", "Thanks, that was it.", login = "alice"))),
                discussionComment("c2", "Same question here.", login = "carol"),
            ),
            id = "D_1",
        ),
        isLoading = false,
    )

    /** A keyboard about as tall as a phone's, with its suggestion strip; taller with a clipboard or emoji strip on top. */
    private var keyboardDp = 360

    private fun setContent(state: State<DiscussionUiState> = mutableStateOf(writable)) {
        val activity = composeRule.activity
        composeRule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(activity.window, false) }
        composeRule.setContent {
            ForgelineTheme(darkTheme = false) {
                // In the app, with its tab bar along the bottom.
                SoftNavigation(selected = TopLevelDestination.INBOX, onSelect = {}) {
                    DiscussionScreen(
                        state = state.value, signedIn = true,
                        onBack = {}, onRefresh = {}, onOpenRepo = {}, onOpenUser = {}, onOpenInBrowser = {}, onLinkClick = {},
                        onSignIn = {}, onErrorShown = {}, onSend = { _, _ -> }, onSetAnswer = { _, _ -> }, onSendErrorShown = {},
                        nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun raiseKeyboard() {
        val activity = composeRule.activity
        composeRule.runOnUiThread {
            val px = (keyboardDp * activity.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        composeRule.waitForIdle()
    }

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    /** Where a node ends, in dp, whether or not that is in sight. */
    private fun SemanticsNodeInteraction.bottom(): Float = fetchSemanticsNode().let { node ->
        val density = composeRule.activity.resources.displayMetrics.density
        (node.positionInRoot.y + node.size.height) / density
    }

    /** Where the screen stops being usable: the keyboard, or the tab bar if it stood on top of the keyboard. */
    private fun keyboardTop(): Float {
        val density = composeRule.activity.resources.displayMetrics.density
        val keyboard = composeRule.onRoot().fetchSemanticsNode().size.height / density - keyboardDp
        val bar = composeRule.onNodeWithContentDescription("Inbox").fetchSemanticsNode().positionInRoot.y / density
        return minOf(keyboard, bar)
    }

    @Test
    fun the_comment_box_and_its_send_tile_stay_above_the_keyboard() {
        // Regression: the comment box at the end of a discussion stayed under the keyboard that came up for it, and then
        // under the tab bar, which rose with the keyboard and stood on top of it.
        setContent()
        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performClick()
        composeRule.waitForIdle()

        raiseKeyboard()

        val send = composeRule.onNodeWithContentDescription("Send comment").bottom()
        assertWithMessage("The send tile ends at ${send}dp, the keyboard starts at ${keyboardTop()}dp").that(send).isAtMost(keyboardTop() + 1f)
    }

    @Test
    fun a_reply_being_written_stays_above_the_keyboard() {
        keyboardDp = 420
        setContent()
        reach("Same question here.")
        composeRule.onAllNodesWithText("Reply")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reply to carol").performClick()
        // Back at the top, the reply being written sits low on the screen, where the keyboard comes up.
        composeRule.onNode(hasScrollAction()).performScrollToIndex(0)
        composeRule.waitForIdle()
        assertWithMessage("The reply starts out where the keyboard will be").that(composeRule.onNodeWithContentDescription("Send reply").bottom()).isGreaterThan(keyboardTop())

        raiseKeyboard()

        val send = composeRule.onNodeWithContentDescription("Send reply").bottom()
        assertWithMessage("The send tile ends at ${send}dp, the keyboard starts at ${keyboardTop()}dp").that(send).isAtMost(keyboardTop() + 1f)
    }

    @Test
    fun why_a_comment_didn_t_go_through_is_said_above_the_keyboard() {
        // Regression: the message stood over the navigation bar, under the keyboard still up for the comment.
        val state = mutableStateOf(writable)
        setContent(state)
        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performClick()
        raiseKeyboard()

        state.value = writable.copy(sendError = ForgeError.Http(403, "Discussion is locked"))
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("You can't comment here.", substring = true).fetchSemanticsNodes().isNotEmpty() }

        val said = composeRule.onNodeWithText("You can't comment here.", substring = true).bottom()
        assertWithMessage("The message ends at ${said}dp, the keyboard starts at ${keyboardTop()}dp").that(said).isAtMost(keyboardTop() + 1f)
    }
}
