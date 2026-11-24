package fr.arthurbrugiere.forgeline

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.testing.FakeStarApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The tour a first launch signed in nowhere opens on: offered once, never in the way of a link or an account. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class TourFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var settings: UserSettingsRepository

    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var starApi: StarApi

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun freshInstall() {
        hiltRule.inject()
        runBlocking { settings.update { UserSettings() } }
    }

    /** Settings and accounts are kept on disk, which the other app tests share. */
    @After
    fun reset() = runBlocking {
        accounts.accounts.first().forEach { accounts.signOut(it.id) }
        settings.update { UserSettings() }
    }

    private fun launch(intent: Intent = Intent(context, MainActivity::class.java), block: () -> Unit) =
        ActivityScenario.launch<MainActivity>(intent).use { block() }

    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and isSelectable())

    private fun waitForTabs() =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Trending") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForWelcome() =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText(WELCOME_LEAD)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun a_first_launch_signed_in_nowhere_opens_on_the_tour_and_skipping_lands_on_trending() {
        launch {
            waitForWelcome()
            // The tour shows the real Trending, live: the fake forge's repository is listed in it.
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
            // Nothing of the app's shell yet: no navigation bar under the tour.
            assertThat(composeRule.onAllNodes(hasContentDescription("Inbox") and isSelectable()).fetchSemanticsNodes()).isEmpty()

            composeRule.onNodeWithText("Skip").performClick()

            waitForTabs()
            tab("Trending").assertIsSelected()
            composeRule.onNodeWithText(WELCOME_LEAD).assertDoesNotExist()
        }
        assertThat(runBlocking { settings.settings.first() }.welcomeSeen).isTrue()
    }

    @Test
    fun the_period_picked_in_the_tour_is_the_one_trending_keeps() {
        launch {
            waitForWelcome()
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasText("This week") and isSelectable()).performClick()
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Model-Optimizer")).fetchSemanticsNodes().isNotEmpty() }

            composeRule.onNodeWithText("Skip").performClick()

            waitForTabs()
            composeRule.onNode(hasText("This week") and isSelectable()).assertIsSelected()
            composeRule.onNodeWithText("Model-Optimizer").assertIsDisplayed()
        }
    }

    /** The tour's Trending is the tab's own: one page, so signing in afterwards looks its stars up once. */
    @Test
    fun signing_in_after_the_tour_looks_trending_s_stars_up_once() {
        val stars = starApi as FakeStarApi
        launch {
            waitForWelcome()
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("Skip").performClick()
            waitForTabs()

            runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
            composeRule.waitUntilAsked { stars.tokensSeen.isNotEmpty() }
            // A second page, had the tour kept its own, asks at the same moment: give it the time to.
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.onNodeWithText("paperclip").assertIsDisplayed()

            assertThat(stars.tokensSeen).containsExactly("tok")
        }
    }

    @Test
    fun the_tour_is_offered_once() {
        launch {
            waitForWelcome()
            composeRule.onNodeWithText("Skip").performClick()
            waitForTabs()
        }

        launch {
            waitForTabs()
            tab("Trending").assertIsSelected()
            composeRule.onNodeWithText(WELCOME_LEAD).assertDoesNotExist()
        }
    }

    @Test
    fun a_repository_tapped_in_the_tour_opens_in_the_app() {
        launch {
            waitForWelcome()
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("paperclip")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText("paperclip").performClick()

            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Issues")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(WELCOME_LEAD).assertDoesNotExist()
            // Back from it is Trending, in the app.
            composeRule.onNode(hasContentDescription("Navigate up")).performClick()
            waitForTabs()
            tab("Trending").assertIsSelected()
        }
    }

    @Test
    fun the_tour_s_last_step_opens_sign_in_on_the_forge_picked() {
        launch {
            waitForWelcome()
            repeat(3) { composeRule.onNodeWithText("Next").performClick() }
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Bring your forges")).fetchSemanticsNodes().isNotEmpty() }

            composeRule.onNodeWithText("Codeberg").performClick()

            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Connect to Codeberg")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasContentDescription("Navigate up")).performClick()
            waitForTabs()
            tab("Trending").assertIsSelected()
        }
    }

    @Test
    fun someone_signed_in_never_meets_it() {
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }

        launch {
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Inbox") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
            tab("Inbox").assertIsSelected()
            composeRule.onNodeWithText(WELCOME_LEAD).assertDoesNotExist()
        }
    }

    @Test
    fun a_link_the_app_opens_on_is_shown_rather_than_the_welcome() {
        val link = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip".toUri()).setClass(context, MainActivity::class.java)

        launch(link) {
            composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Issues")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithText(WELCOME_LEAD).assertDoesNotExist()
        }
    }
}
