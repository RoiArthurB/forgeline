package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import kotlinx.coroutines.delay

/** How long a row stays fully on screen before it counts as read: long enough that flinging past doesn't count. */
const val READ_DWELL_MILLIS = 800L

private const val READ_TICK_MILLIS = 200L

/**
 * Reports the furthest row read: of the rows [positionOf] knows (it gives how far down the list a row is; null for
 * headers and the like), the one furthest along that stayed fully on screen for [READ_DWELL_MILLIS]. Reported only
 * when the reader gets further.
 */
@Composable
fun ReportReading(listState: LazyListState, resetKey: Any?, positionOf: (key: Any) -> Long?, onRead: (key: Any) -> Unit) {
    val position by rememberUpdatedState(positionOf)
    val report by rememberUpdatedState(onRead)
    LaunchedEffect(listState, resetKey) {
        // How long each fully visible row has been on screen, in ticks of this loop.
        val onScreen = mutableMapOf<Any, Long>()
        var furthest = Long.MIN_VALUE
        while (true) {
            delay(READ_TICK_MILLIS)
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo
                .filter { it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset }
                .map { it.key }
                .toSet()
            onScreen.keys.retainAll(visible)
            visible.forEach { onScreen[it] = (onScreen[it] ?: 0L) + READ_TICK_MILLIS }
            val read = onScreen.filterValues { it >= READ_DWELL_MILLIS }.keys
                .mapNotNull { key -> position(key)?.let { key to it } }
                .maxByOrNull { it.second }
                ?: continue
            if (read.second > furthest) {
                furthest = read.second
                report(read.first)
            }
        }
    }
}

/** Where the last visit's reading stopped: a quiet hairline with its name, nothing to press. */
@Composable
fun LeftOffMark(modifier: Modifier = Modifier) {
    val colors = Soft.colors
    Row(
        modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(colors.thumb, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.reading_left_off), style = Soft.type.label, color = colors.inkMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(colors.inkMuted.copy(alpha = 0.25f)))
    }
}
