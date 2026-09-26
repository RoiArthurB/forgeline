package fr.arthurbrugiere.forgeline.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
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
    fun app_shell_light() = snapshot("app_shell_light", darkTheme = false) { ForgelineApp() }

    @Test
    fun app_shell_dark() = snapshot("app_shell_dark", darkTheme = true) { ForgelineApp() }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun app_shell_tablet() = snapshot("app_shell_tablet", darkTheme = false) { ForgelineApp() }

    @Test
    fun settings_light() = snapshot("settings_light", darkTheme = false) { SettingsPreview() }

    @Test
    fun settings_dark_amoled() = snapshot("settings_dark_amoled", darkTheme = true, amoledBlack = true) {
        SettingsPreview(UserSettings(themeMode = ThemeMode.DARK, amoledBlack = true))
    }

    @Composable
    private fun SettingsPreview(settings: UserSettings = UserSettings()) {
        SettingsScreen(
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
