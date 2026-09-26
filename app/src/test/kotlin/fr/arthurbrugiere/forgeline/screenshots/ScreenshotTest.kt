package fr.arthurbrugiere.forgeline.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.signin.SignInScreen
import fr.arthurbrugiere.forgeline.signin.SignInStep
import fr.arthurbrugiere.forgeline.signin.SignInUiState
import fr.arthurbrugiere.forgeline.you.YouScreen
import fr.arthurbrugiere.forgeline.core.model.UserSettings
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.settings.SettingsScreen
import fr.arthurbrugiere.forgeline.ui.ForgelineApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class ScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun snapshot(
        name: String,
        darkTheme: Boolean,
        amoledBlack: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            // Ember palette keeps screenshots independent of the emulated wallpaper.
            ForgelineTheme(darkTheme = darkTheme, dynamicColor = false, amoledBlack = amoledBlack, content = content)
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun app_shell_light() = snapshot("app_shell_light", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}) }

    @Test
    fun app_shell_dark() = snapshot("app_shell_dark", darkTheme = true) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}) }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun app_shell_tablet() = snapshot("app_shell_tablet", darkTheme = false) { ForgelineApp(session = SessionState.SignedOut, onSignOut = {}) }

    @Test
    fun settings_light() = snapshot("settings_light", darkTheme = false) { SettingsPreview() }

    @Test
    fun settings_dark_amoled() = snapshot("settings_dark_amoled", darkTheme = true, amoledBlack = true) {
        SettingsPreview(UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true))
    }

    @Test
    fun sign_in_light() = snapshot("sign_in_light", darkTheme = false) {
        SignInScreen(
            state = SignInUiState(true, "https://github.com/settings/tokens/new"),
            onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = {}, onOpenUrl = {},
            onCancel = {}, onDismissError = {}, onBack = {},
        )
    }

    @Test
    fun sign_in_device_code_dark() = snapshot("sign_in_device_code_dark", darkTheme = true) {
        SignInScreen(
            state = SignInUiState(true, "", SignInStep.AwaitingAuthorization("WDJB-MJHT", "https://github.com/login/device")),
            onStartDeviceFlow = {}, onContinueOnGitHub = { _, _ -> }, onSubmitToken = {}, onOpenUrl = {},
            onCancel = {}, onDismissError = {}, onBack = {},
        )
    }

    @Test
    fun you_signed_in_light() = snapshot("you_signed_in_light", darkTheme = false) {
        YouScreen(SessionState.SignedIn(octocat), onSignIn = {}, onOpenSettings = {})
    }

    private val octocat = Account("id", ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null))

    @Composable
    private fun SettingsPreview(settings: UserSettings = UserSettings()) {
        SettingsScreen(
            session = SessionState.SignedIn(octocat),
            onSignIn = {},
            onSignOut = {},
            settings = settings,
            versionName = "0.1.0",
            onThemeModeChange = {},
            onDynamicColorChange = {},
            onAmoledBlackChange = {},
            onOpenCredits = {},
            onOpenSourceCode = {},
            onBack = {},
        )
    }
}
