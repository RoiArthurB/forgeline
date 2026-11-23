package fr.arthurbrugiere.forgeline.owner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.testing.organizationSettings
import fr.arthurbrugiere.forgeline.core.testing.ownerSettings
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class OwnerSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val changes = mutableListOf<OwnerSettingsChange>()

    private fun setContent(state: OwnerSettingsUiState) = composeRule.setContent {
        ForgelineTheme {
            OwnerSettingsScreen(
                state = state,
                onBack = { events += "back" },
                onRefresh = { events += "refresh" },
                onChange = { changes += it },
                onOpenUrl = { events += "url:$it" },
                onRefusalShown = {},
                onErrorShown = {},
                onSignIn = { events += "sign-in" },
                onLoadBlocked = { events += "load-blocked" },
                onSetBlocked = { login, blocked -> events += "${if (blocked) "block" else "unblock"}:$login" },
            )
        }
    }

    private fun profile(settings: OwnerSettings? = ownerSettings(), forge: ForgeInstance = ForgeInstance.GitHub, error: ForgeError? = null) =
        OwnerSettingsUiState(forge, "mona", Owner.Me, settings = settings, error = error)

    @Test
    fun a_profile_says_what_it_says_about_its_person() {
        setContent(profile())

        composeRule.onNodeWithText("Profile settings").assertIsDisplayed()
        composeRule.onNodeWithText("Mona Lisa").assertIsDisplayed()
        composeRule.onNodeWithText("Paris").assertIsDisplayed()
        composeRule.onNodeWithText("mastodon.social/@mona").assertIsDisplayed()
    }

    @Test
    fun a_text_is_written_in_a_sheet_and_saved_from_it() {
        setContent(profile())

        composeRule.onNodeWithText("Location").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Lyon")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(changes).containsExactly(OwnerSettingsChange.Text(OwnerText.LOCATION, "Lyon"))
    }

    @Test
    fun links_are_written_one_by_one_up_to_what_the_forge_keeps() {
        setContent(profile())

        composeRule.onNodeWithText("Links").performClick()
        composeRule.onNodeWithText("Add a link").performClick()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement("https://bsky.app/profile/mona.example")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(changes).containsExactly(OwnerSettingsChange.Links(listOf("https://mastodon.social/@mona", "https://bsky.app/profile/mona.example")))
    }

    @Test
    fun a_switch_is_the_forge_s_as_soon_as_it_is_turned() {
        setContent(profile())

        composeRule.onNodeWithText("Preferences").performClick()
        composeRule.onNode(hasText("Available for hire") and isToggleable()).performClick()

        assertThat(changes).containsExactly(OwnerSettingsChange.Switch(OwnerSwitch.HIREABLE, true))
    }

    @Test
    fun an_organisation_s_rules_and_choices_read_in_their_own_group() {
        setContent(OwnerSettingsUiState(ForgeInstance.GitHub, "octo", Owner.Organization("octo"), settings = organizationSettings()))

        composeRule.onNodeWithText("Organization settings").assertIsDisplayed()
        composeRule.onNodeWithText("Display name").assertIsDisplayed()
        composeRule.onNodeWithText("Members and visibility").performClick()
        composeRule.onNode(hasText("Members create public repositories") and isToggleable()).assertIsOn()
        composeRule.onNodeWithText("Members' base permission").performClick()
        composeRule.onNodeWithText("Write").performClick()

        assertThat(changes).containsExactly(OwnerSettingsChange.Choice(OwnerChoice.DEFAULT_REPO_PERMISSION, "write"))
    }

    @Test
    fun a_profile_with_only_preferences_shows_them_and_where_the_rest_is() {
        // GitLab lets only its administrators change a profile's words through the API.
        val gitLab = OwnerSettings(switches = linkedMapOf(OwnerSwitch.DIFFS_FILE_BY_FILE to false, OwnerSwitch.SHOW_WHITESPACE to true))
        setContent(profile(gitLab, ForgeInstance.GitLab))

        composeRule.onNodeWithText("Show one file at a time").assertIsDisplayed()
        composeRule.onNodeWithText("More settings on GitLab").performClick()

        assertThat(events).containsExactly("url:https://gitlab.com/-/user_settings/profile")
    }

    @Test
    fun a_sign_in_without_the_permission_is_asked_to_sign_in_again() {
        setContent(profile(settings = null, error = ForgeError.Unauthorized))

        composeRule.onNodeWithText("Sign in to GitHub again to let Forgeline change these settings.").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).containsExactly("sign-in")
    }

    @Test
    fun each_forge_s_own_settings_page_is_where_the_rest_is() {
        assertThat(ownerSettingsUrl(ForgeInstance.GitHub, Owner.Organization("octo"))).isEqualTo("https://github.com/organizations/octo/settings/profile")
        assertThat(ownerSettingsUrl(ForgeInstance.Codeberg, Owner.Me)).isEqualTo("https://codeberg.org/user/settings")
        assertThat(ownerSettingsUrl(ForgeInstance.Codeberg, Owner.Organization("forgejo"))).isEqualTo("https://codeberg.org/org/forgejo/settings")
        assertThat(ownerSettingsUrl(ForgeInstance.GitLab, Owner.Organization("group/sub"))).isEqualTo("https://gitlab.com/groups/group/sub/-/edit")
        assertThat(ownerSettingsUrl(ForgeInstance.SourceHut, Owner.Me)).isEqualTo("https://meta.sr.ht/profile")
    }

    private val busy = fr.arthurbrugiere.forgeline.core.model.OwnerStatus(":coffee:", "Writing", busy = true)

    @Test
    fun a_status_is_said_and_rewritten_in_its_sheet() {
        setContent(profile(ownerSettings().copy(status = busy)))

        composeRule.onNodeWithText(":coffee: Writing · Busy").performClick()
        composeRule.onNodeWithText("Writing").performTextReplacement("Reading")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(changes).containsExactly(OwnerSettingsChange.Status(busy.copy(message = "Reading")))
    }

    @Test
    fun a_status_is_cleared_from_its_sheet() {
        setContent(profile(ownerSettings().copy(status = busy)))

        composeRule.onNodeWithText("Status").performClick()
        composeRule.onNodeWithText("Clear status").performClick()

        assertThat(changes).containsExactly(OwnerSettingsChange.Status(fr.arthurbrugiere.forgeline.core.model.OwnerStatus()))
    }

    @Test
    fun a_forge_without_a_status_offers_none() {
        setContent(profile())

        composeRule.onNodeWithText("Status").assertDoesNotExist()
    }

    @Test
    fun the_blocked_are_listed_unblocked_and_someone_blocked_by_name() {
        val blocked = fr.arthurbrugiere.forgeline.repo.Loadable.Loaded(listOf(fr.arthurbrugiere.forgeline.core.model.UserSummary("spammer", null, false)))
        setContent(profile().copy(blocksSupported = true, blocked = blocked))

        composeRule.onNodeWithText("Blocked").performClick()
        composeRule.onNodeWithText("@spammer").assertIsDisplayed()
        composeRule.onNodeWithText("Unblock").performClick()
        composeRule.onNodeWithText("Block someone").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("troll")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("load-blocked", "unblock:spammer", "block:troll").inOrder()
    }

    @Test
    fun a_forge_without_blocks_has_no_such_tab() {
        setContent(profile())

        composeRule.onNodeWithText("Blocked").assertDoesNotExist()
    }
}
