package fr.arthurbrugiere.forgeline.welcome

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.signin.SignInForge
import fr.arthurbrugiere.forgeline.trending.TrendingUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class TourScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var browsed = 0
    private val signedIn = mutableListOf<SignInForge>()

    private fun show(startAt: TourStep = TourStep.TRENDING) = composeRule.setContent {
        ForgelineTheme(darkTheme = false) {
            TourScreen(
                trending = TrendingUiState(updatedAtMillis = 0L), onTrendingPeriod = {}, onTrendingForge = {},
                onBrowse = { browsed++ }, onOpenRepo = {}, onSignIn = { signedIn += it }, startAt = startAt,
            )
        }
    }

    private fun shown(text: String) = composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun next_walks_the_steps_and_back_walks_them_back() {
        show()
        composeRule.onNodeWithText("See what’s rising").assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Clear your inbox with a swipe").assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Follow what people make").assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("Clear your inbox with a swipe").assertIsDisplayed()
    }

    @Test
    fun the_example_threads_answer_the_swipes_as_the_inbox_does() {
        show(TourStep.INBOX)
        composeRule.onNodeWithText("Crash when the config file is empty").assertIsDisplayed()

        // Left is done, as Settings has it by default: the thread leaves the list.
        composeRule.onNodeWithText("Crash when the config file is empty").performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) { !shown("Crash when the config file is empty") }

        // Right marks it read: it stays, read.
        composeRule.onNodeWithText("Retry the handshake on cold start").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Retry the handshake on cold start").assertIsDisplayed()
    }

    @Test
    fun the_last_step_signs_in_on_the_forge_picked_or_goes_browsing() {
        show(TourStep.SIGN_IN)

        composeRule.onNodeWithText("Codeberg").performClick()
        composeRule.onNodeWithText("Browse Trending").performClick()

        assertThat(signedIn).containsExactly(SignInForge.CODEBERG)
        assertThat(browsed).isEqualTo(1)
    }

    @Test
    fun skip_is_there_on_every_step_before_the_last() {
        show()

        composeRule.onNodeWithText("Skip").performClick()

        assertThat(browsed).isEqualTo(1)
    }
}
