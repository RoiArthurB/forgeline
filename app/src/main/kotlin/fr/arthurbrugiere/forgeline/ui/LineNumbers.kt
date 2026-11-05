package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * How wide a column of line numbers up to [largest] must be in [style], at the reader's text size: the widest figure
 * measured, never less than [floor]. A width fixed in dp held at the usual size but broke "123" over two lines at the
 * largest one, which pushed the code out of step with its neighbours.
 */
@Composable
fun lineNumberWidth(largest: Int, style: TextStyle, floor: Dp): Dp {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    // Figures are as wide as each other in a monospaced or tabular face: the count of them is what matters.
    val figures = largest.coerceAtLeast(1).toString().length
    return remember(figures, style, density, floor) {
        val widest = measurer.measure("8".repeat(figures), style, softWrap = false, maxLines = 1, density = density).size.width
        maxOf(floor, with(density) { widest.toDp() })
    }
}
