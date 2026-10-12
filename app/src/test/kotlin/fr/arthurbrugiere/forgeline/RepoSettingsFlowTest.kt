package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun openRepository() {
        composeRule.onNode(hasContentDescription("Trending") and isSelectable()).performClick()
        waitFor("paperclip")
        composeRule.onNodeWithText("paperclip").performClick()
        waitFor("Issues")
    }

    @Test
    fun who_administers_a_repository_changes_its_settings_from_it() {
        hiltRule.inject()
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        openRepository()
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("Repository settings")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNode(hasContentDescription("Repository settings")).performClick()
        waitFor("Tools for octocats")
        composeRule.onNode(hasText("Wiki") and isToggleable()).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { settings.changes.size == 1 }
        // Regression: the switch's change landing closed whatever sheet had been opened meanwhile.
        composeRule.onNodeWithText("Description").performClick()
        waitFor("Save")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Faster tools")
        composeRule.onNodeWithText("Save").performClick()

        // The sheet closes on what it saved, and the row says it.
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { settings.changes.size == 2 }
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
}
