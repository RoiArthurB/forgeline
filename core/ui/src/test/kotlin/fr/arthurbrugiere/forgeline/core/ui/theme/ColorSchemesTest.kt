package fr.arthurbrugiere.forgeline.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ColorSchemesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theme_mode_resolves_against_the_system_setting() {
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false)).isFalse()
        assertThat(ThemeMode.DARK.isDark(systemInDarkTheme = false)).isTrue()
        assertThat(ThemeMode.LIGHT.isDark(systemInDarkTheme = true)).isFalse()
    }

    @Test
    fun without_dynamic_color_the_ember_palette_is_used() {
        assertThat(forgelineColorScheme(context, darkTheme = false, dynamicColor = false, amoledBlack = false))
            .isEqualTo(EmberLightColors)
        assertThat(forgelineColorScheme(context, darkTheme = true, dynamicColor = false, amoledBlack = false))
            .isEqualTo(EmberDarkColors)
    }

    @Test
    fun dynamic_color_differs_from_the_ember_palette() {
        val scheme = forgelineColorScheme(context, darkTheme = false, dynamicColor = true, amoledBlack = false)

        assertThat(scheme).isNotEqualTo(EmberLightColors)
    }

    @Test
    fun amoled_black_turns_backgrounds_pure_black_in_dark_theme() {
        val scheme = forgelineColorScheme(context, darkTheme = true, dynamicColor = false, amoledBlack = true)

        assertThat(scheme.background).isEqualTo(Color.Black)
        assertThat(scheme.surface).isEqualTo(Color.Black)
        assertThat(scheme.surfaceContainerLowest).isEqualTo(Color.Black)
        assertThat(scheme.primary).isEqualTo(EmberDarkColors.primary)
    }

    @Test
    fun amoled_black_is_ignored_in_light_theme() {
        val scheme = forgelineColorScheme(context, darkTheme = false, dynamicColor = false, amoledBlack = true)

        assertThat(scheme).isEqualTo(EmberLightColors)
    }
}
