package fr.arthurbrugiere.forgeline.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ThemeMode
import fr.arthurbrugiere.forgeline.core.model.HeaderStyle
import fr.arthurbrugiere.forgeline.core.ui.soft.Geist
import fr.arthurbrugiere.forgeline.core.ui.soft.bandIsDark
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftColors
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
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

    private class Provided(val scheme: ColorScheme, val typography: Typography, val soft: SoftColors)

    private fun provided(darkTheme: Boolean, amoledBlack: Boolean, headerStyle: HeaderStyle = HeaderStyle.INK): Provided {
        lateinit var provided: Provided
        composeRule.setContent {
            ForgelineTheme(darkTheme = darkTheme, amoledBlack = amoledBlack, headerStyle = headerStyle) {
                provided = Provided(MaterialTheme.colorScheme, MaterialTheme.typography, Soft.colors)
            }
        }
        composeRule.waitForIdle()
        return provided
    }

    @Test
    fun theme_mode_resolves_against_the_system_setting() {
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false)).isFalse()
        assertThat(ThemeMode.DARK.isDark(systemInDarkTheme = false)).isTrue()
        assertThat(ThemeMode.LIGHT.isDark(systemInDarkTheme = true)).isFalse()
    }

    @Test
    fun material_pieces_and_soft_screens_share_one_palette() {
        val light = provided(darkTheme = false, amoledBlack = false)

        assertThat(light.soft).isEqualTo(SoftLight)
        assertThat(light.scheme.background).isEqualTo(SoftLight.ground)
        assertThat(light.scheme.onSurface).isEqualTo(SoftLight.ink)
        assertThat(light.scheme.primary).isEqualTo(SoftLight.accent)
    }

    @Test
    fun material_text_uses_the_soft_fonts() {
        val typography = provided(darkTheme = false, amoledBlack = false).typography

        assertThat(typography.titleLarge.fontFamily).isEqualTo(Geist)
        assertThat(typography.bodyLarge.fontFamily).isEqualTo(Geist)
        assertThat(typography.labelLarge.fontFamily).isEqualTo(Geist)
    }

    @Test
    fun pure_black_reaches_both_palettes_in_dark_theme() {
        val dark = provided(darkTheme = true, amoledBlack = true)

        assertThat(dark.soft.ground).isEqualTo(Color.Black)
        assertThat(dark.scheme.background).isEqualTo(Color.Black)
        assertThat(dark.scheme.surface).isEqualTo(Color.Black)
    }

    @Test
    fun without_pure_black_dark_theme_keeps_its_charcoal_ground() {
        assertThat(provided(darkTheme = true, amoledBlack = false).soft).isEqualTo(SoftDark)
    }

    @Test
    fun pure_black_is_ignored_in_light_theme() {
        assertThat(provided(darkTheme = false, amoledBlack = true).soft).isEqualTo(SoftLight)
    }

    @Test
    fun the_header_band_is_ink_by_default_and_a_step_off_the_page_in_dark_theme() {
        val light = softColors(darkTheme = false, amoledBlack = false)
        val dark = softColors(darkTheme = true, amoledBlack = false)
        val amoled = softColors(darkTheme = true, amoledBlack = true)

        assertThat(light.band).isEqualTo(Color(0xFF16181A))
        // Dark and AMOLED: the band is lighter than the page, so it still reads as the top of the screen.
        assertThat(dark.band.luminance()).isGreaterThan(dark.ground.luminance())
        assertThat(amoled.band.luminance()).isGreaterThan(amoled.ground.luminance())
        listOf(light, dark, amoled).forEach { assertThat(it.bandIsDark).isTrue() }
    }

    @Test
    fun the_header_style_reaches_the_palette_the_screens_get() {
        assertThat(provided(darkTheme = true, amoledBlack = false, headerStyle = HeaderStyle.WHITE).soft.band).isEqualTo(Color(0xFFEDEDF0))
    }

    @Test
    fun each_header_style_changes_the_band_alone() {
        assertThat(softColors(darkTheme = false, amoledBlack = false, headerStyle = HeaderStyle.BLACK).band).isEqualTo(Color.Black)
        assertThat(softColors(darkTheme = true, amoledBlack = false, headerStyle = HeaderStyle.BLACK).band).isEqualTo(Color.Black)
        val white = softColors(darkTheme = true, amoledBlack = false, headerStyle = HeaderStyle.WHITE)
        assertThat(white.bandIsDark).isFalse()
        // Only the band changes: the page stays the theme's.
        assertThat(white.copy(band = SoftDark.band)).isEqualTo(SoftDark)
    }
}
