package fr.arthurbrugiere.forgeline.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import fr.arthurbrugiere.forgeline.ui.assertTextFits
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class SignInScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun setContent(step: SignInStep = SignInStep.ChooseMethod, deviceFlowAvailable: Boolean = true) {
        composeRule.setContent {
            SignInScreen(
                state = SignInUiState(deviceFlowAvailable, "https://github.com/settings/tokens/new", step),
                onStartDeviceFlow = { events += "device" },
                onContinueOnGitHub = { code, uri -> events += "continue:$code:$uri" },
                onSubmitToken = { events += "token:$it" },
                onOpenUrl = { events += "open:$it" },
                onCancel = { events += "cancel" },
                onDismissError = { events += "dismiss" },
                onBack = { events += "back" },
                onChooseForge = { events += "forges" },
            )
        }
    }

    @Test
    fun device_flow_button_is_offered_when_available() {
        setContent()

        composeRule.onNodeWithText("Sign in with GitHub").performClick()

        assertThat(events).containsExactly("device")
    }

    @Test
    fun without_a_client_id_only_the_token_path_is_shown() {
        setContent(deviceFlowAvailable = false)

        composeRule.onNodeWithText("Sign in with GitHub").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in with token").assertIsDisplayed()
    }

    @Test
    fun submits_the_typed_token() {
        setContent()

        composeRule.onNode(hasContentDescription("Personal access token") and hasSetTextAction()).performTextInput("ghp_abc")
        composeRule.onNodeWithText("Sign in with token").performClick()

        assertThat(events).containsExactly("token:ghp_abc")
    }

    @Test
    fun opens_the_token_creation_page() {
        setContent()

        composeRule.onNodeWithText("Create a token on GitHub").performClick()

        assertThat(events).containsExactly("open:https://github.com/settings/tokens/new")
    }

    @Test
    fun shows_the_device_code_and_continues_on_github() {
        setContent(SignInStep.AwaitingAuthorization("WDJB-MJHT", "https://github.com/login/device"))

        composeRule.onNodeWithText("WDJB-MJHT").assertIsDisplayed()
        composeRule.onNodeWithText("Copy code and open GitHub").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(events).containsExactly("continue:WDJB-MJHT:https://github.com/login/device", "cancel").inOrder()
    }

    @Test
    fun explains_errors() {
        setContent(SignInStep.Failed(SignInError.INVALID_TOKEN))

        composeRule.onNodeWithText("GitHub didn't accept this token", substring = true).assertIsDisplayed()
    }

    @Test
    fun editing_the_token_clears_the_error() {
        setContent(SignInStep.Failed(SignInError.INVALID_TOKEN))

        composeRule.onNode(hasContentDescription("Personal access token") and hasSetTextAction()).performTextInput("x")

        assertThat(events).containsExactly("dismiss")
    }

    @Test
    fun signing_in_to_github_says_why_it_asks_for_private_repositories() {
        setContent()

        composeRule.onNodeWithText("private ones included", substring = true).assertIsDisplayed()
    }

    private fun setOther(state: SignInUiState) {
        composeRule.setContent {
            SignInScreen(
                state = state,
                onHostChange = { events += "host:$it" },
                onClientIdChange = { events += "client:$it" },
                onStartDeviceFlow = {}, onStartBrowserSignIn = { events += "browser" },
                onContinueOnGitHub = { _, _ -> }, onSubmitToken = { events += "token:$it" }, onOpenUrl = {}, onCancel = {}, onDismissError = {}, onBack = {},
            )
        }
    }

    @Test
    fun sourcehut_signs_in_with_a_token_made_on_meta_sr_ht() {
        setOther(
            SignInUiState(
                deviceFlowAvailable = false, personalAccessTokenUrl = "https://meta.sr.ht/oauth2/personal-token", forge = SignInForge.SOURCEHUT, forgeName = "SourceHut",
            ),
        )

        // No browser sign-in, no address: only the token, and where to make it.
        composeRule.onNodeWithText("Sign in with SourceHut").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Server address") and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("Create a token on SourceHut").assertExists()
        composeRule.onNodeWithText("Generate a personal access token on meta.sr.ht", substring = true).assertExists()
    }

    @Test
    fun tangled_signs_in_with_a_handle_and_an_app_password() {
        val submitted = mutableListOf<String>()
        composeRule.setContent {
            SignInScreen(
                state = SignInUiState(
                    deviceFlowAvailable = false, personalAccessTokenUrl = "https://bsky.app/settings/app-passwords", forge = SignInForge.TANGLED,
                    passwordSignInAvailable = true, forgeName = "Tangled",
                ),
                onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = { submitted += "token:$it" }, onOpenUrl = {},
                onCancel = {}, onDismissError = {}, onBack = {}, onSubmitPassword = { handle, password -> submitted += "$handle $password" },
            )
        }

        // No token field: a handle, an app password, and where to make one.
        composeRule.onNodeWithText("Personal access token").assertDoesNotExist()
        composeRule.onNodeWithText("Create an app password on Bluesky").assertExists()
        composeRule.onNode(hasContentDescription("Handle") and hasSetTextAction()).performTextInput("me.bsky.social")
        composeRule.onNode(hasContentDescription("App password") and hasSetTextAction()).performTextInput("abcd-efgh")
        composeRule.onNodeWithText("Sign in with Tangled").performScrollTo().performClick()

        assertThat(submitted).containsExactly("me.bsky.social abcd-efgh")
    }

    private val other = SignInUiState(
        deviceFlowAvailable = false, personalAccessTokenUrl = null, forge = SignInForge.OTHER, host = "gitlab.example.org", forgeName = "gitlab.example.org",
    )

    @Test
    fun a_forgejo_of_ones_own_asks_for_its_address_and_nothing_else() {
        setOther(other.copy(host = ""))

        composeRule.onNode(hasContentDescription("Server address") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText("git.example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Any Forgejo or Gitea server, gitea.com included, works with an access token.").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Application ID (optional)")).assertDoesNotExist()
        // The choice between gitlab.com and one's own server belongs under GitLab.
        composeRule.onNodeWithText("Your own server").assertDoesNotExist()
    }

    private val gitlab = SignInUiState(
        deviceFlowAvailable = false, personalAccessTokenUrl = "https://gitlab.com/-/user_settings/personal_access_tokens",
        forge = SignInForge.GITLAB, browserSignInAvailable = true, forgeName = "GitLab",
    )

    @Test
    fun gitlab_is_gitlab_com_unless_one_says_it_is_a_server_of_ones_own() {
        var chosen: Boolean? = null
        composeRule.setContent {
            SignInScreen(
                state = gitlab, onSelectGitLabServer = { chosen = it },
                onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = {}, onOpenUrl = {}, onCancel = {}, onDismissError = {}, onBack = {},
            )
        }

        // gitlab.com is chosen: no address is asked for, and it signs in through the browser.
        composeRule.onNodeWithText("gitlab.com").assertIsDisplayed()
        composeRule.onNodeWithText("gitlab.example.org").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in with GitLab").assertExists()

        composeRule.onNodeWithText("Your own server").performClick()

        assertThat(chosen).isTrue()
    }

    @Test
    fun a_gitlab_of_ones_own_asks_for_its_address_and_offers_its_application() {
        setOther(gitlab.copy(gitlabOwnServer = true, browserSignInAvailable = false, forgeName = "", personalAccessTokenUrl = null))

        composeRule.onNodeWithText("Connect to your server").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Server address") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText("gitlab.example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Any GitLab server works with an access token.").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Application ID (optional)")).assertExists()
        composeRule.onNodeWithText("Create a personal access token with the api and read_user scopes, then paste it here.").assertExists()

        composeRule.onNode(hasContentDescription("Server address") and hasSetTextAction()).performTextInput("gitlab.example.org")

        assertThat(events).containsExactly("host:gitlab.example.org")
    }

    @Test
    fun an_address_typed_under_gitlab_that_is_a_forgejo_is_said_so() {
        setOther(gitlab.copy(gitlabOwnServer = true, host = "git.example.org", forgeName = "git.example.org", otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.FORGEJO, browserSignInAvailable = false))

        composeRule.onNodeWithText("This is a Forgejo server.").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Application ID (optional)")).assertDoesNotExist()
    }

    @Test
    fun a_self_hosted_gitlab_is_named_and_offered_its_own_application() {
        setOther(other.copy(otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.GITLAB))

        composeRule.onNodeWithText("This is a GitLab server.").assertIsDisplayed()
        // The token it asks for is GitLab's, not Forgejo's.
        composeRule.onNodeWithText("Create a personal access token with the api and read_user scopes, then paste it here.").assertExists()
        composeRule.onNodeWithText("Sign in with gitlab.example.org").assertDoesNotExist()

        composeRule.onNode(hasContentDescription("Application ID (optional)") and hasSetTextAction()).performTextInput("app-id-123")

        assertThat(events).containsExactly("client:app-id-123")
    }

    @Test
    fun with_its_application_a_self_hosted_gitlab_signs_in_through_the_browser() {
        setOther(other.copy(otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.GITLAB, oauthClientId = "app-id-123", browserSignInAvailable = true))

        composeRule.onNodeWithText("Sign in with gitlab.example.org").performScrollTo().performClick()

        assertThat(events).containsExactly("browser")
    }

    @Test
    fun a_self_hosted_forgejo_is_named_and_asked_for_nothing_more() {
        setOther(other.copy(host = "git.example.org", forgeName = "git.example.org", otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.FORGEJO))

        composeRule.onNodeWithText("This is a Forgejo server.").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Application ID (optional)")).assertDoesNotExist()
    }

    @Test
    fun what_github_will_ask_for_is_said_beside_the_button_that_asks_it_delete_included() {
        setContent()

        composeRule.onNode(hasText("and for permission to delete them", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("deletes a repository only when you ask", substring = true)).assertIsDisplayed()
        // The token's scopes say which one is only for deleting.
        composeRule.onNode(hasText("plus delete_repo to delete repositories from Forgeline", substring = true)).assertIsDisplayed()
    }

    @Test
    fun a_filled_field_still_says_what_it_is() {
        setContent()

        // Regression: the field's name was its placeholder, and went once something was written. The label above it stays
        // (the screenshots show it); a screen reader hears it as the field's name, with what is written.
        val token = composeRule.onNode(hasContentDescription("Personal access token") and hasSetTextAction())
        token.performTextInput("ghp_abc")
        // The token is masked as it is written, and the field keeps its name.
        token.assertIsDisplayed().assertTextEquals("•••••••")
    }

    /** On a screen tall enough to hold it all: a target cut by the edge of a shorter one measures as less than it is. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_target_is_48dp_at_least() {
        setContent()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    private fun setForges(onSelect: (SignInForge) -> Unit = {}) {
        composeRule.setContent {
            SignInScreen(
                state = SignInUiState(true, "https://github.com/settings/tokens/new", choosingForge = true),
                onSelectForge = onSelect,
                onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = {}, onOpenUrl = {}, onCancel = {}, onDismissError = {},
                onBack = { events += "back" }, onChooseForge = { events += "forges" },
            )
        }
    }

    @Test
    fun signing_in_starts_by_listing_every_forge_and_opens_the_one_picked() {
        val picked = mutableListOf<SignInForge>()
        setForges { picked += it }

        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        // Nothing is asked before a forge is picked.
        composeRule.onNode(hasContentDescription("Personal access token")).assertDoesNotExist()
        composeRule.onNodeWithText("Other server").performScrollTo()
        composeRule.onNodeWithText("Your own Forgejo, Gitea or GitLab server").assertIsDisplayed()
        composeRule.onNodeWithText("Codeberg").performScrollTo().performClick()

        assertThat(picked).containsExactly(SignInForge.CODEBERG)
    }

    @Test
    fun back_from_the_list_of_forges_leaves_sign_in() {
        setForges()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        assertThat(events).containsExactly("back")
    }

    @Test
    fun back_from_a_forge_returns_to_the_list_of_forges() {
        setContent()

        composeRule.onNode(hasContentDescription("Navigate up")).performClick()

        assertThat(events).containsExactly("forges")
    }

    /** Regression: at twice the text size, the forges' tabs ran off the screen's edge and four of seven were out of sight. */
    @Test
    // Text is measured for real only with native graphics; the default fakes a width of a pixel a letter.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun at_large_text_every_forge_is_named_whole() {
        setForges()

        for (forge in listOf("GitHub", "GitLab", "Codeberg", "Gitea", "SourceHut", "Tangled", "Other server")) {
            composeRule.onNodeWithText(forge).performScrollTo()
            composeRule.assertTextFits(forge)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun every_forge_row_is_48dp_at_least() {
        setForges()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }
}
