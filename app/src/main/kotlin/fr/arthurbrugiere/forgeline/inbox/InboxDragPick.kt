package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** What the Inbox's thread rows are keyed by in its list, before the thread's own key. */
internal const val THREAD_ITEM_PREFIX = "thread-"

/**
 * A finger that picked a thread with a long press and goes on along the list: every thread between it and the one
 * the finger is on is picked too, or let go when the long press let its first one go. What was picked before stays.
 */
internal class DragPick {
    /** The thread the long press was on; null while no drag is on. */
    var anchor: String? = null
        private set
    private var adding = true
    private var before: Set<String> = emptySet()
    private var anchorIndex = -1

    /** The threads met so far, by their place in the list: those scrolled past are in the range as well. */
    private val met = HashMap<Int, String>()
    private var last: Set<String>? = null

    fun start(thread: String, picked: Set<String>) {
        anchor = thread
        adding = thread !in picked
        before = picked
        anchorIndex = -1
        met.clear()
        last = null
    }

    fun stop() {
        anchor = null
        met.clear()
    }

    /**
     * What is picked with the finger at [y] in the list, or null when that is what was picked already (or the drag
     * hasn't reached a thread yet).
     */
    fun pickAt(listState: LazyListState, y: Float): Set<String>? {
        val anchor = anchor ?: return null
        val visible = listState.layoutInfo.visibleItemsInfo
        visible.forEach { item -> (item.key as? String)?.takeIf { it.startsWith(THREAD_ITEM_PREFIX) }?.let { met[item.index] = it.removePrefix(THREAD_ITEM_PREFIX) } }
        if (anchorIndex < 0) anchorIndex = met.entries.firstOrNull { it.value == anchor }?.key ?: return null
        // Over a heading or past the last row, the nearest row above the finger counts.
        val under = visible.lastOrNull { it.offset <= y } ?: visible.firstOrNull() ?: return null
        val range = met.filterKeys { it in min(anchorIndex, under.index)..max(anchorIndex, under.index) }.values
        val picked = if (adding) before + range else before - range.toSet()
        return picked.takeIf { it != last }?.also { last = it }
    }
}

/**
 * Lets a long press go on as a drag that picks the threads it passes over ([DragPick]); the list scrolls by itself
 * while the finger holds near its top or bottom edge. Until a row's long press starts the drag, every touch goes on
 * to the rows and the list as before.
 */
internal fun Modifier.dragToPick(listState: LazyListState, drag: DragPick, onPick: (Set<String>) -> Unit): Modifier = pointerInput(listState, drag) {
    coroutineScope {
        val edge = 64.dp.toPx()
        val fastest = 24.dp.toPx()
        awaitEachGesture {
            // Seen before the rows and the list see it, so it can be kept from them once the drag is on.
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            drag.stop()
            var y = 0f
            var speed = 0f
            var scrolling: Job? = null
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (drag.anchor == null) continue
                val change = event.changes.firstOrNull { it.pressed } ?: break
                y = change.position.y
                drag.pickAt(listState, y)?.let(onPick)
                event.changes.forEach { it.consume() }
                speed = when {
                    y < edge -> -fastest * (edge - y) / edge
                    y > size.height - edge -> fastest * (y - (size.height - edge)) / edge
                    else -> 0f
                }.coerceIn(-fastest, fastest)
                if (speed != 0f && scrolling?.isActive != true) {
                    scrolling = launch {
                        while (isActive && speed != 0f) {
                            if (listState.scrollBy(speed) == 0f) break
                            drag.pickAt(listState, y)?.let(onPick)
                            delay(16)
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
            scrolling?.cancel()
            drag.stop()
        }
    }
}
