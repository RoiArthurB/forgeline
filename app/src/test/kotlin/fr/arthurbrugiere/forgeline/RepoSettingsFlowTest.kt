package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoSettingsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** From a repository its reader administers to its settings, and a change made there. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class RepoSettingsFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var settings: FakeRepoSettingsApi

    @After
    fun signOut() = runBlocking { accounts.accounts.first().forEach { accounts.signOut(it.id) } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Presses [text] once it is enabled. Save only turns on once what was typed is in: a tap before that, which a busy
     * full run made happen, does nothing, and the test then waited for a change that was never asked for.
     */
    private fun pressWhenEnabled(text: String) {
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText(text) and isEnabled()).performClick()
    }

    /**
     * The settings are gone once their tabs are: the screen's title only says "Settings", so waiting on "Repository
     * settings" text passed at once and the next tap could land on the screen that was leaving.
     */
    private fun waitForSettingsToClose() =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Danger zone")).fetchSemanticsNodes().isEmpty() }

    private fun openRepository() {
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).performClick()
        waitFor("paperclip")
        composeRule.onNodeWithText("paperclip").performClick()
        waitFor("Issues")
    }

    @Test
    fun who_administers_a_repository_changes_its_settings_from_it() {
        hiltRule.inject()
        settings.writeLatencyMillis = WRITE_LATENCY_MILLIS
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        openRepository()
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Repository settings")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNode(hasContentDescription("Repository settings")).performClick()
        waitFor("Tools for octocats")
        composeRule.onNodeWithText("Features").performClick()
        composeRule.onNode(hasText("Wiki") and isToggleable()).performClick()
        composeRule.waitUntilAsked { settings.changes.size == 1 }
        // Said, with the way back.
        waitFor("Wiki switched on")
        // Regression: the switch's change landing closed whatever sheet had been opened meanwhile.
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("Description").performClick()
        waitFor("Save")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Faster tools")
        pressWhenEnabled("Save")

        // The sheet closes on what it saved, and the row says it.
        composeRule.waitUntilAsked { settings.changes.size == 2 }
        waitFor("Faster tools")
        assertThat(settings.changes).containsExactly(RepoSettingsChange.Switch(RepoSwitch.WIKI, true), RepoSettingsChange.Description("Faster tools")).inOrder()
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("Issues")
    }

    @Test
    fun the_settings_are_not_offered_signed_out() {
        hiltRule.inject()
        openRepository()

        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("Repository settings")).assertDoesNotExist()
    }

    @Test
    fun a_repository_deleted_from_its_settings_leaves_neither_them_nor_its_page_behind() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        openRepository()
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Repository settings")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("Repository settings")).performClick()
        waitFor("Tools for octocats")

        composeRule.onNodeWithText("Danger zone").performClick()
        composeRule.onNodeWithText("Delete this repository").performClick()
        waitFor("Delete for good")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("paperclipai/paperclip")
        composeRule.onNodeWithText("Delete for good").performClick()
        composeRule.waitForIdle()
        waitFor("paperclipai/paperclip is deleted")
        assertThat(settings.deleted.map { it.fullName }).containsExactly("paperclipai/paperclip")
        composeRule.onNodeWithText("Done").performClick()

        // Back where the repository was opened from.
        waitForSettingsToClose()
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).assertIsSelected()
        composeRule.onNode(hasContentDescription("Navigate up")).assertDoesNotExist()
    }

    @Test
    fun a_renamed_repository_s_page_stands_under_its_new_name() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        openRepository()
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Repository settings")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("Repository settings")).performClick()
        waitFor("Tools for octocats")

        // The forge takes its time to answer, as it does on a phone: nothing below may run ahead of it.
        settings.renameDelayMillis = 1_500
        composeRule.onNodeWithText("Danger zone").performClick()
        composeRule.onNodeWithText("Rename").performClick()
        waitFor("Save")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("clip")
        pressWhenEnabled("Save")
        composeRule.waitForIdle()

        // The settings are left for the page, asked for where the repository now is.
        waitForSettingsToClose()
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("clip")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(settings.renamed).containsExactly("clip")
        // One step back from the page is where the repository was opened from: the old page isn't under it.
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Navigate up")).fetchSemanticsNodes().isEmpty() }
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).assertIsSelected()
    }

    @Test
    fun a_website_is_published_from_the_settings() {
        hiltRule.inject()
        settings.writeLatencyMillis = WRITE_LATENCY_MILLIS
        // Regression: the site, read beside the settings, came after them on a busy CI runner, and "Pages" was tapped
        // before it was there.
        settings.pagesReadLatencyMillis = WRITE_LATENCY_MILLIS
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        openRepository()
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Repository settings")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasContentDescription("Repository settings")).performClick()
        waitFor("Tools for octocats")

        // The site is read on its own: its section is there once it is, not with the settings.
        waitFor("Pages")
        composeRule.onNodeWithText("Pages").performClick()
        waitFor("Publish a website")
        composeRule.onNodeWithText("Publish a website").performClick()
        waitFor("A workflow builds the site")
        composeRule.onAllNodes(hasText("Publish a website"))[1].performClick()
        composeRule.waitForIdle()

        composeRule.waitUntilAsked { settings.pagesChanges.isNotEmpty() }
        // The sheet closes on it, and the section says where the site now is.
        waitFor("https://paperclipai.example/paperclip/")
        assertThat(settings.pagesChanges).containsExactly(fr.arthurbrugiere.forgeline.core.model.PagesChange.Publish(fr.arthurbrugiere.forgeline.core.model.PagesBuild.Workflow))
    }
}
