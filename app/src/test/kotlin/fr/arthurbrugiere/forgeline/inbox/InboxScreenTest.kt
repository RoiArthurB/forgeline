package fr.arthurbrugiere.forgeline.inbox

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
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import org.junit.Rule
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

    private fun setContent(state: InboxUiState) {
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
                nowMillis = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private val grouped = InboxUiState(
        groups = listOf(RepoGroup(RepoId("acme", "rocket"), listOf(mention, review)), RepoGroup(RepoId("octo", "tools"), listOf(release))),
        syncedAtMillis = 1,
    )

    @Test
    fun shows_threads_grouped_under_their_repo() {
        setContent(grouped)

        composeRule.onNodeWithText("acme/rocket").assertIsDisplayed()
        composeRule.onNodeWithText("octo/tools").assertIsDisplayed()
        composeRule.onNodeWithText("Launch fails on cold start").assertIsDisplayed()
        composeRule.onNodeWithText("#42 · Mentioned · 1 hour ago").assertIsDisplayed()
        composeRule.onNodeWithText("Watching", substring = true).assertIsDisplayed()
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
}
