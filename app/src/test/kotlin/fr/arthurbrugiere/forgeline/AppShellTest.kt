package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class AppShellTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = composeRule.onNode(hasText(label) and isSelectable())

    @Test
    fun opens_on_the_inbox() {
        tab("Inbox").assertIsSelected()
        composeRule.onNodeWithText("Nothing here yet").assertIsDisplayed()
    }

    @Test
    fun each_tab_shows_its_screen() {
        tab("Feed").performClick()
        composeRule.onNodeWithText("Your feed is on its way").assertIsDisplayed()

        tab("Trending").performClick()
        composeRule.onNodeWithText("Trending is coming soon").assertIsDisplayed()

        tab("You").performClick()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun settings_open_from_you_and_back_returns() {
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
        tab("You").assertIsSelected()
    }

    @Test
    fun system_back_from_a_tab_returns_to_the_inbox() {
        tab("Trending").performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        // Navigation 3 finishes the pop animation before invoking onBack.
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        tab("Inbox").assertIsSelected()
    }
}
