package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** From a repository with a wiki to its first page, then to another page from the list, and back. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class WikiFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String) =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun a_repository_s_wiki_opens_in_the_app_and_its_pages_follow_one_another() {
        hiltRule.inject()
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).performClick()
        waitFor("paperclip")
        composeRule.onNodeWithText("paperclip").performClick()
        waitFor("Issues")

        composeRule.onNodeWithText("Wiki").performClick()
        waitFor("Wiki of paperclipai/paperclip")
        waitFor("Welcome to the Paperclip wiki.")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("All pages (2)"))
        composeRule.onNodeWithText("All pages (2)").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Install"))
        composeRule.onNodeWithText("Install").performClick()
        waitFor("Run the installer, then sign in.")

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("Welcome to the Paperclip wiki.")
    }
}
