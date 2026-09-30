package fr.arthurbrugiere.forgeline.actions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** The eight terminal colors (black, red, green, yellow, blue, magenta, cyan, white), tuned to 4.5:1 on the log's ground and on the error band. */
data class AnsiPalette(val colors: List<Color>, val dim: Color)

val AnsiLight = AnsiPalette(
    colors = listOf(
        Color(0xFF5A566B), Color(0xFFB8321E), Color(0xFF2B7642), Color(0xFF8A6100),
        Color(0xFF2F5FC4), Color(0xFF8E44AD), Color(0xFF1D7283), Color(0xFF5A566B),
    ),
    dim = Color(0xFF6A667A),
)

val AnsiDark = AnsiPalette(
    colors = listOf(
        Color(0xFF9D98B3), Color(0xFFFF8A78), Color(0xFF7BD88F), Color(0xFFF2C572),
        Color(0xFF8AB4FF), Color(0xFFD59BF6), Color(0xFF6FD3E3), Color(0xFFE6E1EC),
    ),
    dim = Color(0xFF9D98B3),
)

private val escape = Regex("\u001B\\[([0-9;]*)([A-Za-z])|\u001B")

/**
 * Renders a log line's ANSI SGR codes (colors, bold, dim, italic, underline) as styles in [palette]'s colors.
 * Background colors are ignored, and every other escape sequence (cursor moves, erasing) is dropped.
 */
fun ansiAnnotated(text: String, palette: AnsiPalette): AnnotatedString {
    if ('\u001B' !in text) return AnnotatedString(text)
    var color: Color? = null
    var bold = false
    var dim = false
    var italic = false
    var underline = false
    return buildAnnotatedString {
        fun emit(segment: String) {
            if (segment.isEmpty()) return
            val style = SpanStyle(
                color = if (dim) (color ?: palette.dim).copy(alpha = if (color != null) 0.7f else 1f) else color ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = if (underline) TextDecoration.Underline else null,
            )
            if (style == SpanStyle(color = Color.Unspecified)) append(segment) else withStyle(style) { append(segment) }
        }
        var from = 0
        for (match in escape.findAll(text)) {
            emit(text.substring(from, match.range.first))
            from = match.range.last + 1
            if (match.groupValues[2] != "m") continue
            val codes = match.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
            var i = 0
            while (i < codes.size) {
                when (val code = codes[i]) {
                    0 -> { color = null; bold = false; dim = false; italic = false; underline = false }
                    1 -> bold = true
                    2 -> dim = true
                    3 -> italic = true
                    4 -> underline = true
                    22 -> { bold = false; dim = false }
                    23 -> italic = false
                    24 -> underline = false
                    in 30..37 -> color = palette.colors[code - 30]
                    in 90..97 -> color = palette.colors[code - 90]
                    39 -> color = null
                    38, 48 -> {
                        // Extended colors: 5;n (256 colors) or 2;r;g;b. Only the foreground is used.
                        val extended = when (codes.getOrNull(i + 1)) {
                            5 -> codes.getOrNull(i + 2)?.let { xterm(it, palette) }.also { i += 2 }
                            2 -> codes.getOrNull(i + 4)?.let { Color(codes[i + 2], codes[i + 3], it) }.also { i += 4 }
                            else -> null
                        }
                        if (code == 38 && extended != null) color = extended
                    }
                }
                i++
            }
        }
        emit(text.substring(from))
    }
}

/** An xterm 256-color index: the 16 base colors, a 6x6x6 cube, then a gray ramp. */
private fun xterm(index: Int, palette: AnsiPalette): Color = when (index) {
    in 0..7 -> palette.colors[index]
    in 8..15 -> palette.colors[index - 8]
    in 16..231 -> {
        val n = index - 16
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(level(n / 36), level(n / 6 % 6), level(n % 6))
    }
    in 232..255 -> (8 + (index - 232) * 10).let { Color(it, it, it) }
    else -> palette.dim
}
