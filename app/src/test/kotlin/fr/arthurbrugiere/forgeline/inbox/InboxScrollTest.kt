package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class InboxScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_long_inbox_scrolls_end_to_end() {
        val owners = listOf("acme", "Acme", "octo", "zed", "me")
        val reasons = NotificationReason.entries
        val threads = (0 until 120).map { i ->
            notificationThread(
                "$i",
                repo = "${owners[i % owners.size]}/repo${i % 7}",
                reason = reasons[i % reasons.size],
                unread = i % 3 != 0,
                type = SubjectType.entries[i % SubjectType.entries.size],
                number = if (i % 4 == 0) null else i,
                updatedAt = "2026-09-27T%02d:%02d:00Z".format(9 - i / 60, 59 - i % 60),
            )
        }
        val groups = threads.groupBy { if (it.needsYou) InboxSection.NEEDS_YOU else InboxSection.OTHERS }.toSortedMap().map { (section, list) ->
            SectionGroup(section, if (section == InboxSection.OTHERS) list.groupBy { it.repo.owner.lowercase() }.values.flatMap { o -> o.groupBy { it.repo }.values.flatten() } else list)
        }
        composeRule.setContent {
            InboxScreen(
                state = InboxUiState(filter = InboxFilter.ALL, groups = groups, syncedAtMillis = 1),
                onSelectFilter = {}, onRefresh = {}, onOpen = {}, onMarkRead = {}, onMarkDone = {}, onUnsubscribe = {},
                onErrorShown = {}, onActionFailureShown = {},
            )
        }
        val list = composeRule.onNode(hasScrollAction())
        repeat(30) { list.performTouchInput { swipeUp() } }
        list.performScrollToIndex(0)
        list.performScrollToIndex(150)
    }
}
