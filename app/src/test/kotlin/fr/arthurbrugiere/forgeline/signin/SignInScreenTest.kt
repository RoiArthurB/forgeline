package fr.arthurbrugiere.forgeline.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
