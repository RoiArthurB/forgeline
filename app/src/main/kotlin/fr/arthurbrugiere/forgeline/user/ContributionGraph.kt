package fr.arthurbrugiere.forgeline.user

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Contributions
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

/** How many weeks the graph spans: a year, ending with the current one. */
private const val WEEKS = 53

/** The squares and the space between them. */
private val Cell = 11.dp
private val Gap = 3.dp

/**
 * A year of someone's activity, a square a day and a column a week, the busier the more marigold, as forges draw it.
 * It opens on the latest weeks; the earlier ones are a scroll to the left.
 */
@Composable
internal fun ContributionGraph(contributions: Contributions, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    // The app's own language when it has one (Android 13 and later), else the phone's.
    val locale = LocalConfiguration.current.locales[0]
    // Weeks start on Monday, as in most of the world; the last column is the current week.
    val lastWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val firstWeek = lastWeek.minusWeeks(WEEKS - 1L)
    val levels = remember(contributions) { Levels.of(contributions.days.values) }
    // Marigold as a fill (accent is its text shade, a deep amber in light).
    val shades = listOf(colors.surface) + listOf(0.3f, 0.52f, 0.76f, 1f).map { colors.thumb.copy(alpha = it).compositeOver(colors.surface) }
    val measurer = rememberTextMeasurer()
    val monthStyle = Soft.type.meta.copy(color = colors.inkMuted)
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    val total = contributions.total
    val summary = pluralStringResource(R.plurals.user_contributions, total, compactCount(total))
    val busiest = contributions.busiest?.let { (day, count) ->
        stringResource(R.string.user_contributions_busiest, day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)), count)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = listOfNotNull(summary, busiest).joinToString(". ") },
    ) {
        Text(summary, style = Soft.type.section, color = colors.ink)
        val monthRow = 18.dp
        Canvas(
            Modifier
                .horizontalScroll(scroll)
                .size(width = Cell * WEEKS + Gap * (WEEKS - 1), height = monthRow + Cell * 7 + Gap * 6),
        ) {
            val cell = Cell.toPx()
            val step = cell + Gap.toPx()
            val top = monthRow.toPx()
            var lastMonth = -1
            for (week in 0 until WEEKS) {
                val monday = firstWeek.plusWeeks(week.toLong())
                // A month is named over the first week that begins in it.
                if (monday.monthValue != lastMonth) {
                    if (lastMonth != -1 || monday.dayOfMonth <= 7) {
                        drawText(measurer, monday.month.getDisplayName(TextStyle.SHORT, locale), Offset(week * step, 0f), monthStyle)
                    }
                    lastMonth = monday.monthValue
                }
                for (day in 0 until 7) {
                    val date = monday.plusDays(day.toLong())
                    if (date.isAfter(today)) continue
                    drawRoundRect(
                        color = shades[levels.of(contributions.days[date] ?: 0)],
                        topLeft = Offset(week * step, top + day * step),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(cell * 0.22f),
                    )
                }
            }
        }
    }
}

/** Four shades above nothing, split at the quartiles of the busy days, so one huge day doesn't wash the rest out. */
internal class Levels private constructor(private val bounds: List<Int>) {
    fun of(count: Int): Int = if (count <= 0) 0 else 1 + bounds.count { count > it }.coerceAtMost(3)

    companion object {
        fun of(counts: Collection<Int>): Levels {
            val busy = counts.filter { it > 0 }.sorted()
            if (busy.isEmpty()) return Levels(emptyList())
            return Levels(listOf(0.25, 0.5, 0.75).map { busy[((busy.size - 1) * it).toInt()] })
        }
    }
}
