package fr.arthurbrugiere.forgeline

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.model.ShareTap
import fr.arthurbrugiere.forgeline.core.model.StartTab
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.ui.copiedText
import fr.arthurbrugiere.forgeline.ui.sharedLink
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

/** What is chosen in Settings, followed through the whole app: stored, read at launch, and acted on by the screens. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class SettingsFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var settings: UserSettingsRepository

    @Before
    fun inject() = hiltRule.inject()

    @After
    fun reset() = runBlocking { settings.update { UserSettings() } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and isSelectable())

    private fun launch(intent: Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java), block: () -> Unit) =
        ActivityScenario.launch<MainActivity>(intent).use { block() }

    @Test
    fun the_app_opens_on_the_tab_chosen_and_back_returns_there() {
        runBlocking { settings.update { it.copy(startTab = StartTab.YOU) } }

        launch {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("You") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
            tab("You").assertIsSelected()
            composeRule.onNodeWithText("Connect an account").assertIsDisplayed()
        }
    }

    @Test
    fun untouched_and_signed_in_nowhere_it_still_opens_on_trending() {
        launch {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("Trending") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
            tab("Trending").assertIsSelected()
        }
    }

    @Test
    fun a_choice_made_in_settings_is_stored_and_acted_on_at_once() {
        launch {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("You") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
            tab("You").performClick()
            composeRule.onNodeWithText("Settings").performClick()
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Gestures"))
            composeRule.onNodeWithText("Gestures").performClick()
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Share button"))
            composeRule.onNodeWithText("A tap shares, a long press copies the link").performClick()
            composeRule.onNodeWithText("A tap copies the link, a long press shares").performClick()
            // The row says what is stored once it is. (Reading the store here would hold the main thread the write needs.)
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("A tap copies the link, a long press shares")).fetchSemanticsNodes().size == 1 &&
                    composeRule.onAllNodes(hasText("A tap shares, a long press copies the link")).fetchSemanticsNodes().isEmpty()
            }

            // On a repository, the button now copies on a tap.
            tab("Trending").performClick()
            waitFor("paperclip")
            composeRule.onNodeWithText("paperclip").performClick()
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasContentDescription("Copy link")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasContentDescription("Copy link")).performClick()

            assertThat(copiedText()).isEqualTo("https://github.com/paperclipai/paperclip")
            assertThat(sharedLink()).isNull()
        }
        // And it is what a later launch reads.
        assertThat(runBlocking { settings.settings.first().shareTap }).isEqualTo(ShareTap.COPY)
    }

    @Test
    fun with_open_at_what_is_new_switched_off_an_unread_conversation_opens_at_its_title() {
        runBlocking { settings.update { it.copy(openAtUnread = false) } }
        // What a notification carries: the thread is unread, last read after its 30th remark.
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/paperclipai/paperclip/pull/14129".toUri())
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra(fr.arthurbrugiere.forgeline.notifications.EXTRA_UNREAD, true)
            .putExtra(fr.arthurbrugiere.forgeline.notifications.EXTRA_LAST_READ_AT, java.time.Instant.parse("2026-09-26T09:30:30Z").toEpochMilli())

        launch(intent) {
            composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Remark 1 on the retry.")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasText("Keep install flags on retry - #14129")).assertIsDisplayed()
        }
    }
}
