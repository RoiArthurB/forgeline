package fr.arthurbrugiere.forgeline.settings

import fr.arthurbrugiere.forgeline.core.model.ForgeType
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
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
import fr.arthurbrugiere.forgeline.core.model.FeedKind
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
    private var amoledBlack: Boolean? = null
    private var sourceCodeOpened = false
    private var creditsOpened = false
    private var backPressed = false

    private var signedOut = false
    private var signedOutOf: Account? = null
    private var interval: InboxCheckInterval? = null
    private val feedChanges = mutableListOf<Pair<FeedKind, Boolean>>()

    private var openedSection: SettingsSection? = null

    private fun setContent(
        settings: UserSettings = UserSettings(),
        session: SessionState = SessionState.SignedOut,
        section: SettingsSection? = null,
    ) {
        composeRule.setContent {
            SettingsScreen(
                session = session,
                onSignIn = {},
                onSignOut = { signedOut = true; signedOutOf = it },
                settings = settings,
                versionName = "1.2.3",
                onThemeModeChange = { themeMode = it },
                onAmoledBlackChange = { amoledBlack = it },
                onOpenCredits = { creditsOpened = true },
                onInboxCheckIntervalChange = { interval = it },
                onFeedKindChange = { kind, shown -> feedChanges += kind to shown },
                onOpenSourceCode = { sourceCodeOpened = true },
                onBack = { backPressed = true },
                section = section,
                onOpenSection = { openedSection = it },
            )
        }
    }

    @Test
    fun every_account_is_listed_and_signs_out_on_its_own() {
        val github = Account(Account.idFor(ForgeInstance.GitHub, "octocat"), ForgeInstance.GitHub, ForgeUser("octocat", null, null))
        val codeberg = Account(Account.idFor(ForgeInstance.Codeberg, "alice"), ForgeInstance.Codeberg, ForgeUser("alice", null, null))
        setContent(session = SessionState.SignedIn(github, listOf(github, codeberg)), section = SettingsSection.ACCOUNTS)

        composeRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeRule.onNodeWithText("@alice").assertIsDisplayed()
        // Each account says its forge in words under its login, beside the logo on its avatar.
        composeRule.onNodeWithText("Codeberg").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("Add an account").assertIsDisplayed()

        composeRule.onAllNodesWithText("Sign out")[1].performClick()
        composeRule.onAllNodesWithText("Sign out").onLast().performClick()

        assertThat(signedOutOf).isEqualTo(codeberg)
    }

    @Test
    fun shows_current_settings() {
        setContent(UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true), section = SettingsSection.APPEARANCE)

        composeRule.onNodeWithText("Dark").assertIsSelected()
        composeRule.onNodeWithText("Pure black").assertIsOn()
    }

    @Test
    fun selecting_a_theme_reports_it() {
        setContent(section = SettingsSection.APPEARANCE)

        composeRule.onNodeWithText("Light").performClick()

        assertThat(themeMode).isEqualTo(ThemeMode.LIGHT)
    }

    @Test
    fun tapping_a_switch_row_toggles_it() {
        setContent(section = SettingsSection.APPEARANCE)

        composeRule.onNodeWithText("Pure black").performClick()

        assertThat(amoledBlack).isTrue()
    }

    @Test
    fun about_section_shows_version_and_opens_source_code() {
        setContent(section = SettingsSection.ABOUT)

        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("1.2.3"))
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
        list.performScrollToNode(hasText("Source code"))
        composeRule.onNodeWithText("Source code").performClick()

        assertThat(sourceCodeOpened).isTrue()
    }

    @Test
    fun credits_row_opens_credits() {
        setContent(section = SettingsSection.ABOUT)

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Credits and licenses"))
        composeRule.onNodeWithText("Credits and licenses").performClick()

        assertThat(creditsOpened).isTrue()
    }

    @Test
    fun signing_out_asks_for_confirmation() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", null, null))
        setContent(session = SessionState.SignedIn(account), section = SettingsSection.ACCOUNTS)

        composeRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeRule.onNodeWithText("Sign out").performClick()
        composeRule.onNodeWithText("Sign out of @octocat?").assertIsDisplayed()
        assertThat(signedOut).isFalse()

        composeRule.onAllNodesWithText("Sign out")[1].performClick()

        assertThat(signedOut).isTrue()
    }

    @Test
    fun the_inbox_check_interval_is_chosen_from_a_dialog() {
        setContent(section = SettingsSection.INBOX)

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Check for new notifications"))
        composeRule.onNodeWithText("Every hour").performClick()
        composeRule.onNodeWithText("Every 15 minutes").performClick()

        assertThat(interval).isEqualTo(InboxCheckInterval.MIN_15)
    }

    @Test
    fun feed_activity_kinds_are_toggled_from_a_checklist() {
        setContent(section = SettingsSection.FEED)

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Feed activity"))
        composeRule.onNodeWithText("Showing 8 of 13").performClick()
        composeRule.onNodeWithText("Stars").assertIsOn()
        composeRule.onNodeWithText("Pushes").assertIsOff()
        composeRule.onNodeWithText("Stars").performClick()
        composeRule.onNodeWithText("Pushes").performClick()

        assertThat(feedChanges).containsExactly(FeedKind.STARS to false, FeedKind.PUSHES to true).inOrder()
    }

    @Test
    fun navigate_up_goes_back() {
        setContent()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        assertThat(backPressed).isTrue()
    }

    @Test
    fun the_main_list_names_each_page_with_what_it_holds_and_opens_it() {
        val account = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", null, null))
        setContent(UserSettings(themeMode = ThemeMode.DARK), session = SessionState.SignedIn(account))

        composeRule.onNodeWithText("Accounts").assertIsDisplayed()
        composeRule.onNodeWithText("@octocat").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("Checks: Every hour").assertIsDisplayed()
        composeRule.onNodeWithText("Showing 8 of 13").assertIsDisplayed()
        // Settings themselves live on the pages.
        composeRule.onNodeWithText("Pure black").assertDoesNotExist()

        composeRule.onNodeWithText("Appearance").performClick()

        assertThat(openedSection).isEqualTo(SettingsSection.APPEARANCE)
    }

    @Test
    fun a_page_is_titled_by_its_section() {
        setContent(section = SettingsSection.INBOX)

        composeRule.onNodeWithText("Inbox & notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").assertDoesNotExist()
    }

    @Test
    fun each_forgejo_server_can_have_its_trending_measured_on_the_phone() {
        val home = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        val github = Account(Account.idFor(ForgeInstance.GitHub, "me"), ForgeInstance.GitHub, ForgeUser("me", null, null))
        val homeAccount = Account(Account.idFor(home, "me"), home, ForgeUser("me", null, null))
        val changes = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            SettingsScreen(
                session = SessionState.SignedIn(github, listOf(github, homeAccount)),
                onSignIn = {}, onSignOut = {}, settings = UserSettings(), versionName = "1.2.3",
                onThemeModeChange = {}, onAmoledBlackChange = {}, onOpenCredits = {}, onOpenSourceCode = {}, onBack = {},
                section = SettingsSection.TRENDING,
                onTrendingMeasuredChange = { host, on -> changes += host to on },
            )
        }

        // GitHub has its own list: only the Forgejo server is offered.
        composeRule.onNodeWithText("Measure git.example.org here").performClick()
        composeRule.onNodeWithText("Measure GitHub here").assertDoesNotExist()

        assertThat(changes).containsExactly("git.example.org" to true)
    }

    @Test
    fun a_measured_server_says_when_it_was_last_measured() {
        val home = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        val account = Account(Account.idFor(home, "me"), home, ForgeUser("me", null, null))
        composeRule.setContent {
            SettingsScreen(
                session = SessionState.SignedIn(account),
                onSignIn = {}, onSignOut = {}, settings = UserSettings(measuredTrending = setOf(home.host)), versionName = "1.2.3",
                onThemeModeChange = {}, onAmoledBlackChange = {}, onOpenCredits = {}, onOpenSourceCode = {}, onBack = {},
                section = SettingsSection.TRENDING,
                measuredAt = mapOf(home.host to 0L),
                nowMillis = 3 * 3_600_000L,
            )
        }

        composeRule.onNodeWithText("Measured on this phone, last", substring = true).assertIsDisplayed()
    }

    @Test
    fun with_only_github_there_is_nothing_to_measure() {
        setContent(session = SessionState.SignedIn(Account("id", ForgeInstance.GitHub, ForgeUser("me", null, null))), section = SettingsSection.TRENDING)

        composeRule.onNodeWithText("Nothing to choose yet").assertIsDisplayed()
    }
}
