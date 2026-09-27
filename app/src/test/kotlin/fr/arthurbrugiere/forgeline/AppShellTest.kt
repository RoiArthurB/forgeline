package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class AppShellTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = composeRule.onNode(hasText(label) and isSelectable())

    @Test
    fun opens_on_the_inbox() {
        tab("Inbox").assertIsSelected()
        composeRule.onNodeWithText("Your inbox lives on GitHub").assertIsDisplayed()
    }

    @Test
    fun each_tab_shows_its_screen() {
        tab("Feed").performClick()
        composeRule.onNodeWithText("Follow the people you follow").assertIsDisplayed()

        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("+2,109 today").assertIsDisplayed()

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
    fun signing_in_from_the_inbox_opens_the_sign_in_screen() {
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.onNodeWithText("Connect to GitHub").assertIsDisplayed()
        // No OAuth client ID in test builds: only the token path is offered.
        composeRule.onNodeWithText("Sign in with GitHub").assertDoesNotExist()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("Your inbox lives on GitHub").assertIsDisplayed()
    }

    @Test
    fun credits_open_from_settings() {
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Credits and licenses"))
        composeRule.onNodeWithText("Credits and licenses").performClick()

        composeRule.onNodeWithText("Read the license").assertIsDisplayed()
    }

    @Test
    fun a_trending_repo_opens_with_its_readme() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclipai / paperclip")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("paperclipai / paperclip").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Open-source orchestration for teams of AI agents.")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("README").assertIsDisplayed()
    }

    @Test
    fun a_file_opens_from_the_code_tab() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclipai / paperclip")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("paperclipai / paperclip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Code")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("Code").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("package.json")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("package.json").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("\"name\": \"paperclip\"", substring = true)).fetchSemanticsNodes().isNotEmpty() }
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
