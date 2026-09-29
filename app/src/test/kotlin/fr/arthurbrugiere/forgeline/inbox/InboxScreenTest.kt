package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import org.junit.Rule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class InboxScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val mention = notificationThread("42", repo = "acme/rocket", title = "Launch fails on cold start", reason = NotificationReason.MENTION)
    private val review = notificationThread("43", repo = "acme/rocket", title = "Add retry", reason = NotificationReason.REVIEW_REQUESTED)
    private val release = notificationThread("9", repo = "octo/tools", title = "v2.0.0", reason = NotificationReason.SUBSCRIBED, number = null)

    private fun setContent(state: InboxUiState, prompt: NotificationPrompt? = null) {
        composeRule.setContent {
            InboxScreen(
                state = state,
                onSelectFilter = { events += "filter:$it" },
                onRefresh = { events += "refresh" },
                onOpen = { events += "open:${it.id}" },
                onMarkRead = { events += "read:${it.id}" },
                onMarkDone = { events += "done:${it.id}" },
                onUnsubscribe = { events += "unsubscribe:${it.id}" },
                onErrorShown = {},
                onActionFailureShown = {},
                notificationPrompt = prompt,
                onAllowNotifications = { events += "allow" },
                onUndo = { events += "undo:${it.key.substringAfter('|')}:${it.action}" },
                nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private val grouped = InboxUiState(
        groups = listOf(SectionGroup(InboxSection.NEEDS_YOU, listOf(mention, review)), SectionGroup(InboxSection.OTHERS, listOf(release))),
        syncedAtMillis = 1,
    )

    @Test
    fun what_needs_you_comes_first_led_by_why() {
        setContent(grouped)

        composeRule.onNodeWithText("Needs you").assertIsDisplayed()
        composeRule.onNodeWithText("Mentioned").assertIsDisplayed()
        composeRule.onNodeWithText("Review requested").assertIsDisplayed()
        composeRule.onNodeWithText("acme/\u2060rocket #42").assertIsDisplayed()
        composeRule.onNodeWithText("Launch fails on cold start").assertIsDisplayed()
        composeRule.onAllNodes(hasText("1 hr. ago")).assertCountEquals(2)
    }

    @Test
    fun everything_else_is_led_by_what_it_is() {
        setContent(grouped)

        composeRule.onNodeWithText("Everything else").assertIsDisplayed()
        composeRule.onNodeWithText("Issue").assertIsDisplayed()
        composeRule.onNodeWithText("octo/\u2060tools").assertIsDisplayed()
        composeRule.onNodeWithText("Watching · 1 hr. ago").assertIsDisplayed()
    }

    @Test
    fun everything_else_says_where_a_pull_request_or_issue_stands() {
        // Regression: every pull request read "Pull request", merged or not.
        val merged = notificationThread("7", repo = "octo/tools", type = SubjectType.PULL_REQUEST, reason = NotificationReason.SUBSCRIBED)
            .copy(state = SubjectState.MERGED)
        val open = notificationThread("8", repo = "octo/tools", type = SubjectType.PULL_REQUEST, reason = NotificationReason.SUBSCRIBED)
            .copy(state = SubjectState.OPEN)
        val closed = notificationThread("9", repo = "octo/tools", type = SubjectType.ISSUE, reason = NotificationReason.SUBSCRIBED)
            .copy(state = SubjectState.CLOSED)
        setContent(grouped.copy(groups = listOf(SectionGroup(InboxSection.OTHERS, listOf(merged, open, closed)))))

        composeRule.onNodeWithText("Merged pull request").assertIsDisplayed()
        composeRule.onNodeWithText("Open pull request").assertIsDisplayed()
        composeRule.onNodeWithText("Closed issue").assertIsDisplayed()
        composeRule.onNodeWithText("Pull request").assertDoesNotExist()
    }

    @Test
    fun a_held_action_offers_undo() {
        setContent(grouped.copy(undo = PendingUndo(notificationThread("42").key, InboxAction.DONE, serial = 1)))

        composeRule.onNodeWithText("Marked as done").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        assertThat(events).containsExactly("undo:42:DONE")
    }

    @Test
    fun a_swiped_away_thread_comes_back_in_place_on_undo() {
        var state by mutableStateOf(grouped)
        composeRule.setContent {
            InboxScreen(
                state = state, onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {},
                onMarkDone = { done ->
                    state = state.copy(
                        groups = state.groups.map { g -> g.copy(threads = g.threads - done) },
                        undo = PendingUndo(done.id, InboxAction.DONE, serial = 1),
                    )
                },
                onUnsubscribe = {}, onErrorShown = {}, onActionFailureShown = {},
                onUndo = { events += "undo"; state = grouped },
            )
        }

        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasText("Add retry")).assertCountEquals(0)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        // Back, in place, and not marked done a second time.
        assertThat(events).containsExactly("undo")
        composeRule.onAllNodes(hasText("Marked as done")).assertCountEquals(0)
        val row = composeRule.onNodeWithText("Add retry").fetchSemanticsNode().boundsInRoot
        val heading = composeRule.onNodeWithText("Needs you").fetchSemanticsNode().boundsInRoot
        assertThat(row.left).isLessThan(heading.right)
        composeRule.onNodeWithText("Add retry").assertIsDisplayed()
    }

    @Test
    fun undo_goes_away_once_the_action_is_sent() {
        var state by mutableStateOf(grouped.copy(undo = PendingUndo(notificationThread("42").key, InboxAction.READ, serial = 1)))
        composeRule.setContent {
            InboxScreen(
                state = state, onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {},
                onUnsubscribe = {}, onErrorShown = {}, onActionFailureShown = {},
            )
        }
        composeRule.onNodeWithText("Marked as read").assertIsDisplayed()

        state = state.copy(undo = null)
        composeRule.waitForIdle()

        composeRule.onAllNodes(hasText("Undo")).assertCountEquals(0)
    }

    @Test
    fun tapping_a_thread_opens_it() {
        setContent(grouped)

        composeRule.onNodeWithText("Add retry").performClick()

        assertThat(events).containsExactly("open:43")
    }

    @Test
    fun swiping_right_marks_read_and_left_marks_done() {
        setContent(grouped)

        composeRule.onNodeWithText("Launch fails on cold start").performTouchInput { swipeRight() }
        composeRule.onNodeWithText("Add retry").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("read:42", "done:43").inOrder()
    }

    @Test
    fun the_menu_offers_every_action() {
        setContent(grouped)

        composeRule.onAllNodes(hasContentDescription("More actions")).onFirst().performClick()
        composeRule.onNodeWithText("Unsubscribe").performClick()

        assertThat(events).containsExactly("unsubscribe:42")
    }

    @Test
    fun filters_are_chips() {
        setContent(grouped)

        composeRule.onNodeWithText("Participating").performClick()

        assertThat(events).containsExactly("filter:PARTICIPATING")
    }

    @Test
    fun an_empty_unread_inbox_is_all_caught_up() {
        setContent(InboxUiState(syncedAtMillis = 1))

        composeRule.onNodeWithText("You're all caught up").assertIsDisplayed()
    }

    @Test
    fun an_empty_filtered_inbox_says_nothing_matches() {
        setContent(InboxUiState(filter = InboxFilter.ALL, syncedAtMillis = 1))

        composeRule.onNodeWithText("No notifications match this filter.").assertIsDisplayed()
    }

    @Test
    fun a_token_without_the_notifications_scope_is_explained() {
        setContent(InboxUiState(error = ForgeError.Http(403, "Missing the 'notifications' scope.")))

        composeRule.onNodeWithText("Your token can't read notifications", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun asks_for_the_notification_permission_when_needed() {
        setContent(grouped, NotificationPrompt.ASK)

        composeRule.onNodeWithText("Get notified about new activity").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performClick()
        assertThat(events).containsExactly("allow")
    }

    @Test
    fun once_denied_the_prompt_points_to_settings() {
        setContent(grouped, NotificationPrompt.OPEN_SETTINGS)

        composeRule.onNodeWithText("Open settings").performClick()
        assertThat(events).containsExactly("allow")
    }

    @Test
    fun no_prompt_when_notifications_are_allowed() {
        setContent(grouped)

        composeRule.onAllNodes(hasText("Get notified about new activity")).assertCountEquals(0)
    }
}
