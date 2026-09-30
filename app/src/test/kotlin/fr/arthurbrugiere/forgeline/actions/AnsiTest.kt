package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLight
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDark
import fr.arthurbrugiere.forgeline.core.ui.soft.amoled
import fr.arthurbrugiere.forgeline.core.ui.soft.contrastRatio
import org.junit.Test

class AnsiTest {
    private val red = AnsiLight.colors[1]
    private val green = AnsiLight.colors[2]

    @Test
    fun plain_text_stays_plain() {
        val text = ansiAnnotated("npm ci", AnsiLight)

        assertThat(text.text).isEqualTo("npm ci")
        assertThat(text.spanStyles).isEmpty()
    }

    @Test
    fun colors_apply_until_reset_and_codes_disappear() {
        // From a real vitest summary line.
        val text = ansiAnnotated("\u001B[1m\u001B[31m1 failed\u001B[39m\u001B[22m | \u001B[32m76 passed\u001B[39m", AnsiLight)

        assertThat(text.text).isEqualTo("1 failed | 76 passed")
        val failed = text.spanStyles.single { text.text.substring(it.start, it.end) == "1 failed" }.item
        assertThat(failed.color).isEqualTo(red)
        assertThat(failed.fontWeight).isEqualTo(FontWeight.Bold)
        assertThat(text.spanStyles.single { text.text.substring(it.start, it.end) == "76 passed" }.item.color).isEqualTo(green)
        assertThat(text.spanStyles.none { text.text.substring(it.start, it.end).contains("|") }).isTrue()
    }

    @Test
    fun bright_and_extended_colors_are_read() {
        val text = ansiAnnotated("\u001B[91mbright\u001B[0m \u001B[38;5;196mxterm\u001B[0m \u001B[38;2;10;20;30mrgb", AnsiLight)

        assertThat(text.text).isEqualTo("bright xterm rgb")
        val colors = text.spanStyles.associate { text.text.substring(it.start, it.end) to it.item.color }
        assertThat(colors["bright"]).isEqualTo(red)
        assertThat(colors["xterm"]).isEqualTo(Color(255, 0, 0))
        assertThat(colors["rgb"]).isEqualTo(Color(10, 20, 30))
    }

    @Test
    fun other_escape_sequences_are_dropped() {
        val text = ansiAnnotated("\u001B[2Kprogress\u001B[1G done\u001B", AnsiLight)

        assertThat(text.text).isEqualTo("progress done")
    }

    @Test
    fun a_background_color_does_not_color_the_text() {
        val text = ansiAnnotated("\u001B[41;30mFAIL\u001B[0m", AnsiLight)

        assertThat(text.spanStyles.single().item.color).isEqualTo(AnsiLight.colors[0])
    }

    @Test
    fun every_log_colour_reads_on_the_log_and_on_the_error_band() {
        // Regression: light green, cyan and dim fell to 4.2-4.4:1 on the error band behind ERROR lines.
        val grounds = listOf(
            "light ground" to (AnsiLight to SoftLight.ground), "light error band" to (AnsiLight to SoftLight.fields[0]),
            "dark ground" to (AnsiDark to SoftDark.ground), "dark error band" to (AnsiDark to SoftDark.fields[0]),
            "amoled ground" to (AnsiDark to SoftDark.amoled().ground), "amoled error band" to (AnsiDark to SoftDark.amoled().fields[0]),
        )
        grounds.forEach { (name, pair) ->
            val (palette, ground) = pair
            (palette.colors + palette.dim).forEachIndexed { index, color ->
                val ratio = contrastRatio(color, ground)
                assertWithMessage("colour $index on the $name: %s:1".format("%.2f".format(ratio))).that(ratio).isAtLeast(4.5f)
            }
        }
    }
}
