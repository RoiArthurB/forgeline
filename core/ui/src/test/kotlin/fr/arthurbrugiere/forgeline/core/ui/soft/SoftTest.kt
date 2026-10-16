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
 * Every text/background pair the Soft world draws must meet WCAG AA for normal text (4.5:1), in every theme, on every
 * meaning tint, and on the header band in each of its styles. Regression: muted labels on a tint once sat near 4.1:1.
 */
@RunWith(RobolectricTestRunner::class)
class SoftTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val palettes = mapOf("light" to SoftLight, "dark" to SoftDark, "amoled" to SoftDark.amoled())

    /** Every theme with every header style: the band and what is drawn on it change with both. */
    private val banded = palettes.flatMap { (theme, c) ->
        fr.arthurbrugiere.forgeline.core.model.HeaderStyle.entries.map { style -> "$theme/${style.name.lowercase()}" to c.withHeader(style) }
    }

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
    fun everything_on_the_header_band_is_readable_in_every_style() {
        banded.forEach { (name, c) ->
            val band = c.onBand()
            assertThat(band.ground).isEqualTo(c.band)
            assertReadable("$name title on the band", band.ink, c.band)
            assertReadable("$name unselected tab on the band", band.inkMuted, c.band)
            assertReadable("$name link on the band", band.accent, c.band)
            assertReadable("$name tag on the band's surface", band.inkMuted, band.surface)
            assertReadable("$name pressed action on the band", band.ink, band.track.compositeOver(c.band))
        }
    }

    @Test
    fun the_chosen_tab_s_underline_stands_out_on_a_dark_band() {
        // A non-text mark (3:1). On a white band it sits beside the bold label, which says the choice on its own.
        banded.filter { (_, c) -> c.bandIsDark }.forEach { (name, c) ->
            assertWithMessage("$name underline").that(contrast(c.thumb, c.band)).isAtLeast(3f)
        }
    }

    @Test
    fun a_band_that_melts_into_the_page_gets_its_rule() {
        assertThat(SoftLight.bandNeedsRule).isFalse()
        assertThat(SoftDark.bandNeedsRule).isFalse()
        assertThat(SoftDark.amoled().bandNeedsRule).isFalse()
        // True black on the true-black page, white on the light page: the hairline says where the band ends.
        assertThat(SoftDark.amoled().withHeader(fr.arthurbrugiere.forgeline.core.model.HeaderStyle.BLACK).bandNeedsRule).isTrue()
        assertThat(SoftLight.withHeader(fr.arthurbrugiere.forgeline.core.model.HeaderStyle.WHITE).bandNeedsRule).isTrue()
    }

    @Test
    fun tags_and_tiles_on_meaning_tints_are_readable() {
        palettes.forEach { (theme, c) ->
            c.fields.forEachIndexed { index, field ->
                assertReadable("$theme ink on tint $index", c.ink, field)
                assertReadable("$theme muted ink on tint $index", c.inkMuted, field)
            }
            assertReadable("$theme glyph and gain on marigold's tint", c.accent, c.accentSoft)
            assertReadable("$theme ink on marigold's tint", c.ink, c.accentSoft)
            assertReadable("$theme send arrow and filled button", c.onThumb, c.thumb)
        }
    }

    @Test
    fun markers_buttons_and_snackbars_are_readable() {
        palettes.forEach { (theme, c) ->
            assertReadable("$theme stopped-here marker", c.ink, c.accentSoft)
            assertReadable("$theme filled button", c.onThumb, c.thumb)
            assertReadable("$theme snackbar", c.ground, c.ink)
            assertReadable("$theme final-action button", c.ground, c.ink)
            assertReadable("$theme quiet button that can't be pressed", c.inkMuted, c.surface)
            assertReadable("$theme picked choice ring on the ground", c.accent, c.ground)
            assertReadable("$theme text on a pressed surface", c.ink, c.surface)
            // A focused row keeps this surface on screen, so its marigold text must stay readable on it.
            assertReadable("$theme accent on a pressed surface", c.accent, c.surface)
            assertReadable("$theme muted ink on a pressed surface", c.inkMuted, c.surface)
            assertReadable("$theme ink on a floating surface", c.ink, c.raised)
            assertReadable("$theme muted ink on a floating surface", c.inkMuted, c.raised)
            assertReadable("$theme accent on a floating surface", c.accent, c.raised)
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

    @Test
    fun forge_label_text_stays_readable_on_githubs_default_label_colors() {
        // Regression: text was picked by luminance > 0.5, which put white on mid-tone labels (question, #e99695).
        val defaults = listOf("d73a4a", "0075ca", "cfd3d7", "a2eeef", "7057ff", "008672", "e4e669", "d876e3", "ffffff", "e99695", "fbca04", "0e8a16", "5319e7", "1d76db", "b60205")
        defaults.forEach { hex ->
            val label = Color(0xFF000000 or hex.toLong(16))
            assertReadable("label #$hex", readableOn(label), label)
        }
    }
}
