package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end journeys on a real Android system image; the JVM suite covers the details. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Unselected tabs show only their icon; every tab is named for screen readers.
    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and isSelectable())

    /**
     * Regression guard: a system dialog (a launcher ANR on the google_apis image) once stole window
     * focus and Espresso failed with a cryptic RootViewWithoutFocusException. Fail clearly instead.
     */
    @Before
    fun appWindowHasFocus() {
        val deadline = System.currentTimeMillis() + 10_000
        var focused = false
        while (!focused && System.currentTimeMillis() < deadline) {
            composeRule.runOnUiThread { focused = composeRule.activity.hasWindowFocus() }
            if (!focused) Thread.sleep(100)
        }
        check(focused) { "Forgeline never got window focus: another window (system dialog, ANR) is in front." }
    }

    @Test
    fun gets_past_the_splash_screen() {
        // Regression: the splash screen once waited forever for settings that only loaded once
        // the UI subscribed. Robolectric couldn't reproduce it; only a real system shows it.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Inbox") and isSelectable()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun browse_every_tab_then_back_to_the_inbox() {
        tab("Inbox").assertIsSelected()
        tab("Feed").performClick()
        composeRule.onNodeWithText("Follow the people you follow").assertIsDisplayed()
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("paperclip", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        pressBack()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Your inbox lives on GitHub")).fetchSemanticsNodes().isNotEmpty()
        }
        tab("Inbox").assertIsSelected()
    }

    @Test
    fun reach_credits_through_settings_and_come_back() {
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("Credits and licenses").performClick()
        composeRule.onNodeWithText("Read the license").assertIsDisplayed()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("Source code").assertIsDisplayed()
    }

    @Test
    fun sign_in_then_triage_the_inbox_and_read_the_feed() {
        // Runs on a real system image: token encryption needs the Android Keystore.
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithText("Personal access token").performTextInput("ghp_emulator")
        composeRule.onNodeWithText("Sign in with token").performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Heartbeat recovery escalates too early")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("I can reproduce this on every restart.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        pressBack()
        tab("Feed").performClick()
        composeRule.waitUntil(10_000) {
            // Feed headlines keep "owner/repo" on one line and end with the time.
            composeRule.onAllNodes(hasText("octocat opened an issue in paperclipai/\u2060paperclip", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Leave the app signed out for the other tests.
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Accounts").performClick()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onAllNodesWithText("Sign out")[1].performClick()
    }

    @Test
    fun theme_choice_survives_an_activity_recreation() {
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.onNodeWithText("Dark").performClick()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Dark").assertIsSelected()
        // Restore the default so test order doesn't matter.
        composeRule.onNodeWithText("System").performClick()
    }
}
