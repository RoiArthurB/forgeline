package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** How much of what is written in is kept in sight, from its end: its last lines and what stands under them. */
private val KeptInSight = 300.dp

/**
 * Keeps a place to write in sight while the keyboard is up: when it gets the keyboard, as the keyboard rises, and
 * when it grows (suggestions coming under a field), the list or page it is in scrolls to show its end.
 *
 * Regression: the comment box at the end of a conversation stayed under the keyboard that came up for it.
 *
 * [keyboardHeight] is the keyboard's, in pixels; tests give their own.
 */
@Composable
fun Modifier.staysAboveKeyboard(keyboardHeight: Int = WindowInsets.ime.getBottom(LocalDensity.current)): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val kept = with(LocalDensity.current) { KeptInSight.toPx() }
    LaunchedEffect(focused, keyboardHeight, size) {
        if (focused && keyboardHeight > 0 && size.height > 0) {
            // Its end, not its start: a long text's last lines and the actions under them are what is being used.
            requester.bringIntoView(Rect(0f, (size.height - kept).coerceAtLeast(0f), size.width.toFloat(), size.height.toFloat()))
        }
    }
    return this.bringIntoViewRequester(requester).onSizeChanged { size = it }.onFocusChanged { focused = it.hasFocus }
}
