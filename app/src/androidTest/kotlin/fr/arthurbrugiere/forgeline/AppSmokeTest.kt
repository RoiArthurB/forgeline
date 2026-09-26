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

    private fun tab(label: String) = composeRule.onNode(hasText(label) and isSelectable())

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
            composeRule.onAllNodes(hasText("Inbox") and isSelectable()).fetchSemanticsNodes().isNotEmpty()
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
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Credits and licenses"))
        composeRule.onNodeWithText("Credits and licenses").performClick()
        composeRule.onNodeWithText("Read the license").assertIsDisplayed()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun theme_choice_survives_an_activity_recreation() {
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Dark").performClick()

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText("Dark").assertIsSelected()
        // Restore the default so test order doesn't matter.
        composeRule.onNodeWithText("System").performClick()
    }
}
