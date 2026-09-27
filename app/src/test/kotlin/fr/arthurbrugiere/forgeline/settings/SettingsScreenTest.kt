package fr.arthurbrugiere.forgeline.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.session.SessionState
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import fr.arthurbrugiere.forgeline.PHONE

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var themeMode: ThemeMode? = null
    private var dynamicColor: Boolean? = null
    private var amoledBlack: Boolean? = null
    private var sourceCodeOpened = false
    private var creditsOpened = false
    private var backPressed = false

    private var signedOut = false
    private var interval: InboxCheckInterval? = null

    private fun setContent(settings: UserSettings = UserSettings(), session: SessionState = SessionState.SignedOut) {
        composeRule.setContent {
            SettingsScreen(
                session = session,
                onSignIn = {},
                onSignOut = { signedOut = true },
                settings = settings,
                versionName = "1.2.3",
                onThemeModeChange = { themeMode = it },
                onDynamicColorChange = { dynamicColor = it },
                onAmoledBlackChange = { amoledBlack = it },
                onOpenCredits = { creditsOpened = true },
                onInboxCheckIntervalChange = { interval = it },
                onOpenSourceCode = { sourceCodeOpened = true },
                onBack = { backPressed = true },
            )
        }
    }

    @Test
    fun shows_current_settings() {
        setContent(UserSettings(themeMode = ThemeMode.DARK, dynamicColor = false, amoledBlack = true))

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("Material You").assertIsOff()
        composeRule.onNodeWithText("Pure black").assertIsOn()
    }

    @Test
    fun selecting_a_theme_reports_it() {
        setContent()

        composeRule.onNodeWithText("Light").performClick()

        assertThat(themeMode).isEqualTo(ThemeMode.LIGHT)
    }

    @Test
    fun tapping_a_switch_row_toggles_it() {
        setContent()

        composeRule.onNodeWithText("Material You").performClick()
        composeRule.onNodeWithText("Pure black").performClick()

        assertThat(dynamicColor).isFalse()
        assertThat(amoledBlack).isTrue()
    }

    @Test
    fun about_section_shows_version_and_opens_source_code() {
        setContent()

        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("1.2.3"))
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
        list.performScrollToNode(hasText("Source code"))
        composeRule.onNodeWithText("Source code").performClick()

        assertThat(sourceCodeOpened).isTrue()
    }

    @Test
    fun credits_row_opens_credits() {
        setContent()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Credits and licenses"))
        composeRule.onNodeWithText("Credits and licenses").performClick()

        assertThat(creditsOpened).isTrue()
    }

    @Test
    fun signing_out_asks_for_confirmation() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", null, null))
        setContent(session = SessionState.SignedIn(account))

        composeRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onNodeWithText("Sign out of @octocat?").assertIsDisplayed()
        assertThat(signedOut).isFalse()

        composeRule.onAllNodesWithText("Sign out")[1].performClick()

        assertThat(signedOut).isTrue()
    }

    @Test
    fun the_inbox_check_interval_is_chosen_from_a_dialog() {
        setContent()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Check for new notifications"))
        composeRule.onNodeWithText("Every hour").performClick()
        composeRule.onNodeWithText("Every 15 minutes").performClick()

        assertThat(interval).isEqualTo(InboxCheckInterval.MIN_15)
    }

    @Test
    fun navigate_up_goes_back() {
        setContent()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        assertThat(backPressed).isTrue()
    }
}
