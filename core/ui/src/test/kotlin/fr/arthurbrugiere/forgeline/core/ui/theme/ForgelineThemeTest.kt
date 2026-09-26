package fr.arthurbrugiere.forgeline.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression: a screenshot once rendered "pure black" as brown because the setting reached the
 * screen but not the theme. These tests check what the composable actually provides.
 */
@RunWith(RobolectricTestRunner::class)
class ForgelineThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun provided(darkTheme: Boolean, dynamicColor: Boolean, amoledBlack: Boolean): ColorScheme {
        lateinit var scheme: ColorScheme
        composeRule.setContent {
            ForgelineTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, amoledBlack = amoledBlack) {
                scheme = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        return scheme
    }

    @Test
    fun pure_black_reaches_the_provided_scheme_in_dark_theme() {
        val scheme = provided(darkTheme = true, dynamicColor = false, amoledBlack = true)

        assertThat(scheme.background).isEqualTo(Color.Black)
        assertThat(scheme.surface).isEqualTo(Color.Black)
    }

    @Test
    fun pure_black_also_applies_on_top_of_material_you() {
        val scheme = provided(darkTheme = true, dynamicColor = true, amoledBlack = true)

        assertThat(scheme.background).isEqualTo(Color.Black)
    }

    @Test
    fun without_pure_black_dark_theme_keeps_its_tinted_background() {
        val scheme = provided(darkTheme = true, dynamicColor = false, amoledBlack = false)

        assertThat(scheme.background).isEqualTo(EmberDarkColors.background)
    }

    @Test
    fun light_theme_provides_the_light_palette() {
        val scheme = provided(darkTheme = false, dynamicColor = false, amoledBlack = true)

        assertThat(scheme).isEqualTo(EmberLightColors)
    }
}
