package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ReportReadingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val read = mutableListOf<Any>()
    private lateinit var listState: LazyListState

    private fun setContent() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            listState = rememberLazyListState()
            ReportReading(listState, resetKey = null, positionOf = { (it as? Int)?.toLong() }, onRead = { read += it })
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items((0 until 100).toList(), key = { it }) { Text("Row $it", Modifier.height(100.dp)) }
            }
        }
    }

    @Test
    fun a_row_counts_as_read_once_it_stayed_on_screen_a_moment() {
        setContent()

        composeRule.mainClock.advanceTimeBy(READ_DWELL_MILLIS / 2)
        assertThat(read).isEmpty()
        composeRule.mainClock.advanceTimeBy(READ_DWELL_MILLIS)

        // The furthest row fully on screen.
        assertThat(read).isNotEmpty()
        assertThat(read.last() as Int).isGreaterThan(0)
    }

    @Test
    fun flinging_past_rows_does_not_read_them() {
        // Regression: rows only glimpsed while scrolling counted as read, so the mark jumped far down.
        setContent()
        composeRule.mainClock.advanceTimeBy(READ_DWELL_MILLIS * 2)
        val before = read.last() as Int

        // Scroll through rows 10 to 60 without stopping on any, then rest at 80.
        for (row in 10..60 step 10) {
            composeRule.runOnIdle { runBlocking { listState.scrollToItem(row) } }
            composeRule.mainClock.advanceTimeBy(READ_DWELL_MILLIS / 4)
        }
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(80) } }
        composeRule.mainClock.advanceTimeBy(READ_DWELL_MILLIS * 2)

        assertThat(read.map { it as Int }.filter { it > before }.none { it in 10..79 }).isTrue()
        assertThat(read.last() as Int).isAtLeast(80)
    }
}
