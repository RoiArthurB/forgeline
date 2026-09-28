package fr.arthurbrugiere.forgeline.core.ui.soft

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every text/background pair the Soft world draws must meet WCAG AA for normal text (4.5:1), in both themes
 * and on every field tint. Regression: muted labels on the switch track over the tints once sat near 4.1:1.
 */
@RunWith(RobolectricTestRunner::class)
class SoftTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val palettes = mapOf("light" to SoftLight, "dark" to SoftDark, "amoled" to SoftDark.copy(ground = Color.Black))

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance() + 0.05f
        val b = background.luminance() + 0.05f
        return maxOf(a, b) / minOf(a, b)
    }

    private fun assertReadable(name: String, foreground: Color, background: Color) {
        val ratio = contrast(foreground, background)
        assertWithMessage("$name: ${"%.2f".format(ratio)}:1").that(ratio).isAtLeast(4.5f)
    }

    @Test
    fun text_on_the_ground_is_readable() {
        palettes.forEach { (theme, c) ->
            assertReadable("$theme ink", c.ink, c.ground)
            assertReadable("$theme muted ink", c.inkMuted, c.ground)
            assertReadable("$theme accent", c.accent, c.ground)
        }
    }

    @Test
    fun header_fields_and_the_period_switch_are_readable() {
        palettes.forEach { (theme, c) ->
            c.fields.forEachIndexed { index, field ->
                assertReadable("$theme title on field $index", c.ink, field)
                assertReadable("$theme unselected period on field $index", c.inkMuted, c.track.compositeOver(field))
            }
            assertReadable("$theme selected period", c.onThumb, c.thumb)
        }
    }

    @Test
    fun markers_buttons_and_snackbars_are_readable() {
        palettes.forEach { (theme, c) ->
            assertReadable("$theme stopped-here pill", c.ink, c.fields[0])
            assertReadable("$theme filled button", c.onThumb, c.thumb)
            assertReadable("$theme snackbar", c.ground, c.ink)
            assertReadable("$theme text on a pressed surface", c.ink, c.surface)
            // A focused row keeps this surface on screen, so its ember rank and figures must stay readable on it.
            assertReadable("$theme accent on a pressed surface", c.accent, c.surface)
            assertReadable("$theme muted ink on a pressed surface", c.inkMuted, c.surface)
        }
    }

    @Test
    fun the_theme_hands_ink_to_material_pieces_so_ripples_match_the_palette() {
        var content = Color.Unspecified
        lateinit var soft: SoftColors
        composeRule.setContent {
            SoftTheme(SoftDark) {
                content = LocalContentColor.current
                soft = Soft.colors
            }
        }
        composeRule.waitForIdle()

        assertThat(content).isEqualTo(SoftDark.ink)
        assertThat(soft).isEqualTo(SoftDark)
    }
}
