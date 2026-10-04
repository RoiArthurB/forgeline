package fr.arthurbrugiere.forgeline.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
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

        composeRule.onNodeWithText("Personal access token").performTextInput("ghp_abc")
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

        composeRule.onNodeWithText("Personal access token").performTextInput("x")

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

    private val other = SignInUiState(
        deviceFlowAvailable = false, personalAccessTokenUrl = null, forge = SignInForge.OTHER, host = "gitlab.example.org", forgeName = "gitlab.example.org",
    )

    @Test
    fun a_forgejo_of_ones_own_asks_for_its_address_and_nothing_else() {
        setOther(other.copy(host = ""))

        composeRule.onNodeWithText("Server address, like git.example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Any Forgejo server works with an access token.").assertIsDisplayed()
        composeRule.onNodeWithText("Application ID (optional)").assertDoesNotExist()
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
        composeRule.onNodeWithText("Server address, like gitlab.example.org").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in with GitLab").assertExists()

        composeRule.onNodeWithText("Your own server").performClick()

        assertThat(chosen).isTrue()
    }

    @Test
    fun a_gitlab_of_ones_own_asks_for_its_address_and_offers_its_application() {
        setOther(gitlab.copy(gitlabOwnServer = true, browserSignInAvailable = false, forgeName = "", personalAccessTokenUrl = null))

        composeRule.onNodeWithText("Connect to your server").assertIsDisplayed()
        composeRule.onNodeWithText("Server address, like gitlab.example.org").assertIsDisplayed()
        composeRule.onNodeWithText("Any GitLab server works with an access token.").assertIsDisplayed()
        composeRule.onNodeWithText("Application ID (optional)").assertExists()
        composeRule.onNodeWithText("Create a personal access token with the api and read_user scopes, then paste it here.").assertExists()

        composeRule.onNodeWithText("Server address, like gitlab.example.org").performTextInput("gitlab.example.org")

        assertThat(events).containsExactly("host:gitlab.example.org")
    }

    @Test
    fun an_address_typed_under_gitlab_that_is_a_forgejo_is_said_so() {
        setOther(gitlab.copy(gitlabOwnServer = true, host = "git.example.org", forgeName = "git.example.org", otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.FORGEJO, browserSignInAvailable = false))

        composeRule.onNodeWithText("This is a Forgejo server.").assertIsDisplayed()
        composeRule.onNodeWithText("Application ID (optional)").assertDoesNotExist()
    }

    @Test
    fun a_self_hosted_gitlab_is_named_and_offered_its_own_application() {
        setOther(other.copy(otherType = fr.arthurbrugiere.forgeline.core.model.ForgeType.GITLAB))

        composeRule.onNodeWithText("This is a GitLab server.").assertIsDisplayed()
        // The token it asks for is GitLab's, not Forgejo's.
        composeRule.onNodeWithText("Create a personal access token with the api and read_user scopes, then paste it here.").assertExists()
        composeRule.onNodeWithText("Sign in with gitlab.example.org").assertDoesNotExist()

        composeRule.onNodeWithText("Application ID (optional)").performTextInput("app-id-123")

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
        composeRule.onNodeWithText("Application ID (optional)").assertDoesNotExist()
    }
}
