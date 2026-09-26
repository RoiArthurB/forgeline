package fr.arthurbrugiere.forgeline.core.ui.format

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class FormattingTest {
    private fun compact(value: Int) = compactCount(value, Locale.ENGLISH)

    @Test
    fun small_counts_are_shown_in_full() {
        assertThat(compact(0)).isEqualTo("0")
        assertThat(compact(999)).isEqualTo("999")
    }

    @Test
    fun thousands_and_millions_match_github_style() {
        assertThat(compact(1_000)).isEqualTo("1k")
        assertThat(compact(1_234)).isEqualTo("1.2k")
        assertThat(compact(85_955)).isEqualTo("85.9k")
        assertThat(compact(123_456)).isEqualTo("123.4k")
        assertThat(compact(1_500_000)).isEqualTo("1.5M")
    }

    @Test
    fun counts_never_round_up_past_the_real_value() {
        assertThat(compact(1_999)).isEqualTo("1.9k")
        assertThat(compact(999_999)).isEqualTo("999.9k")
    }

    @Test
    fun the_decimal_separator_follows_the_locale() {
        assertThat(compactCount(1_234, Locale.FRENCH)).isEqualTo("1,2k")
    }

    @Test
    fun parses_forge_hex_colors() {
        assertThat(parseHexColor("#3178c6")).isEqualTo(Color(0xFF3178C6))
        assertThat(parseHexColor("#f1e05a")).isEqualTo(Color(0xFFF1E05A))
        assertThat(parseHexColor("#abc")).isEqualTo(Color(0xFFAABBCC))
    }

    @Test
    fun invalid_colors_are_ignored() {
        assertThat(parseHexColor(null)).isNull()
        assertThat(parseHexColor("blue")).isNull()
        assertThat(parseHexColor("#12345")).isNull()
    }
}
