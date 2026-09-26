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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end journeys on a real Android system image; the JVM suite covers the details. */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = composeRule.onNode(hasText(label) and isSelectable())

    @Test
    fun browse_every_tab_then_back_to_the_inbox() {
        tab("Inbox").assertIsSelected()
        tab("Feed").performClick()
        composeRule.onNodeWithText("Your feed is on its way").assertIsDisplayed()
        tab("Trending").performClick()
        composeRule.onNodeWithText("Trending is coming soon").assertIsDisplayed()

        pressBack()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Nothing here yet")).fetchSemanticsNodes().isNotEmpty()
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
