package fr.arthurbrugiere.forgeline.core.ui.format

import androidx.compose.ui.graphics.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** GitHub-style compact counts ("85.9k", "1.5M"), truncated so they never overstate. */
fun compactCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val format = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale))
    return when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> format.format((value / 100) / 10.0) + "k"
        else -> format.format((value / 100_000) / 10.0) + "M"
    }
}

/** Parses `#rgb` or `#rrggbb` as served by forges; anything else is ignored. */
fun parseHexColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#")?.takeIf { hex.startsWith("#") } ?: return null
    val full = when (digits.length) {
        3 -> digits.map { "$it$it" }.joinToString("")
        6 -> digits
        else -> return null
    }
    val rgb = full.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or rgb)
}
