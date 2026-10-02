package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
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

    // Every tab shows its label under its icon and is named once for screen readers.
    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and isSelectable())

    @Test
    fun every_tab_shows_its_label_not_only_the_selected_one() {
        // The labels live under a cleared subtree (the tab is named once), so look in the unmerged tree.
        listOf("Inbox", "Feed", "Trending", "You").forEach { label ->
            composeRule.onNode(hasText(label) and hasAnyAncestor(isSelectable()), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun signed_in_nowhere_opens_on_trending_where_there_is_something_to_read() {
        // The inbox is a sign-in wall until an account exists; Trending works signed out.
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        tab("Trending").assertIsSelected()
        composeRule.onNodeWithText("Your notifications, in one place").assertDoesNotExist()
    }

    @Test
    fun the_signed_out_walls_name_every_forge_and_lead_back_to_trending() {
        tab("Inbox").performClick()
        // Codeberg and self-hosted Forgejo work too: the wall must not say GitHub alone.
        composeRule.onNodeWithText("GitHub, Codeberg or your own Forgejo", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Browse Trending").performClick()
        tab("Trending").assertIsSelected()

        tab("Feed").performClick()
        composeRule.onNodeWithText("Browse Trending").performClick()
        tab("Trending").assertIsSelected()
    }

    @Test
    fun each_tab_shows_its_screen() {
        tab("Feed").performClick()
        composeRule.onNodeWithText("Follow the people you follow").assertIsDisplayed()

        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("+2,109 today"), useUnmergedTree = true).assertIsDisplayed()

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
        tab("Inbox").performClick()
        composeRule.onNodeWithText("Sign in").performClick()

        composeRule.onNodeWithText("Connect to GitHub").assertIsDisplayed()
        // No OAuth client ID in test builds: only the token path is offered.
        composeRule.onNodeWithText("Sign in with GitHub").assertDoesNotExist()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("Your notifications, in one place").assertIsDisplayed()
    }

    @Test
    fun credits_open_from_settings() {
        tab("You").performClick()
        composeRule.onNodeWithText("Settings").performClick()
        // Credits live on Settings' About page.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("About"))
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("Credits and licenses").performClick()

        composeRule.onNodeWithText("Read the license").assertIsDisplayed()
    }

    @Test
    fun a_trending_repo_opens_with_its_readme() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("paperclip").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Open-source orchestration for teams of AI agents.")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("README").assertIsDisplayed()
    }

    @Test
    fun a_file_opens_from_the_code_tab() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("paperclip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Code")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("Code").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("package.json")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("package.json").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("\"name\": \"paperclip\"", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun an_issue_opens_from_the_repo_with_its_conversation() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("paperclip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Issues")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("Issues").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Heartbeat recovery escalates too early")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("I can reproduce this on every restart.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(" - #14127", substring = true).assertIsDisplayed()
    }

    @Test
    fun system_back_from_a_tab_returns_to_home_which_is_trending_when_signed_out() {
        tab("Feed").performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        // Navigation 3 finishes the pop animation before invoking onBack.
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        tab("Trending").assertIsSelected()
    }

    @Test
    fun search_opens_from_every_tab_and_leads_to_results() {
        for (label in listOf("Inbox", "Feed", "Trending", "You")) {
            tab(label).performClick()
            composeRule.onNode(hasContentDescription("Search")).assertIsDisplayed()
        }

        composeRule.onNode(hasContentDescription("Search")).performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("paperclip")
        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("paperclipai/paperclip")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("paperclipai/paperclip").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText("Open-source orchestration for teams of AI agents.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun opening_an_issue_signed_out_leads_to_signing_in() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("paperclip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Issues")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Issues").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("New issue")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("New issue").performClick()

        composeRule.onNodeWithText("Connect to GitHub").assertIsDisplayed()
    }

    private fun openIssuesTab() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("paperclip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Issues")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Issues").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Heartbeat recovery escalates too early")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun a_repository_s_pinned_and_closed_issues_can_be_read() {
        openIssuesTab()
        composeRule.onNodeWithText("Read this before reporting a bug").assertIsDisplayed()
        composeRule.onNodeWithText("Installer ignores the proxy setting").assertDoesNotExist()

        composeRule.onNodeWithText("Closed").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Installer ignores the proxy setting")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Heartbeat recovery escalates too early").assertDoesNotExist()
        composeRule.onNodeWithText("Read this before reporting a bug").assertDoesNotExist()
    }

    @Test
    fun a_repository_s_issues_can_be_searched() {
        openIssuesTab()

        composeRule.onNode(hasSetTextAction() and hasText("Search issues")).performTextInput("nothing like this")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Nothing matches these words.")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("Clear")).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Heartbeat recovery escalates too early")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNode(hasSetTextAction()).performTextInput("heartbeat")
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Heartbeat recovery escalates too early")).fetchSemanticsNodes().isNotEmpty() }
        // A search's results stand alone: nothing pinned above them.
        composeRule.onNodeWithText("Read this before reporting a bug").assertDoesNotExist()
    }

    @Test
    fun a_release_opens_from_the_repository_with_its_notes_and_files() {
        tab("Trending").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("paperclip").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Issues")).fetchSemanticsNodes().isNotEmpty() }
        // The Releases tab sits past the edge of the tab strip.
        composeRule.onNodeWithText("Releases").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("September")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("September").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Heartbeats recover on their own.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Latest").assertIsDisplayed()
        composeRule.onNodeWithText("paperclip-linux-amd64").assertExists()
        composeRule.onNodeWithText("5.8 MB · 144 downloads").assertExists()
        composeRule.onNodeWithText("Source code (zip)").assertExists()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.onNodeWithText("September").assertIsDisplayed()
    }
}
