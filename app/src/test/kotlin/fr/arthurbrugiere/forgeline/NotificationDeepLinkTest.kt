package fr.arthurbrugiere.forgeline

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tapped notification carries the thread's GitHub URL; the app must open it in-app. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class NotificationDeepLinkTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Test
    fun a_notification_link_opens_the_conversation() {
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip/issues/14127".toUri())
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("Heartbeat recovery escalates too early", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun a_notification_of_an_unread_thread_opens_the_conversation_at_what_is_new() {
        // What the system notification carries: the thread is unread, and was last read after its 30th remark.
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip/pull/14129".toUri())
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(fr.arthurbrugiere.forgeline.notifications.EXTRA_UNREAD, true)
            .putExtra(fr.arthurbrugiere.forgeline.notifications.EXTRA_LAST_READ_AT, java.time.Instant.parse("2026-09-26T09:30:30Z").toEpochMilli())

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Remark 31 on the retry.")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasText("Remark 31 on the retry.")).assertIsDisplayed()
            composeRule.onNode(hasText("Remark 30 on the retry.")).assertIsNotDisplayed()
        }
    }

    @Test
    fun the_same_link_without_a_notification_opens_at_the_title() {
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip/pull/14129".toUri())
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Remark 1 on the retry.")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasText("Keep install flags on retry - #14129")).assertIsDisplayed()
        }
    }
}
