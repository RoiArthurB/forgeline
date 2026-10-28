package fr.arthurbrugiere.forgeline.core.model

import java.time.LocalDate

/**
 * How much someone did each day of about the last year, as their forge counts it (commits, issues, reviews and the
 * like; each forge counts its own way). Days left out had nothing.
 */
data class Contributions(val days: Map<LocalDate, Int>) {
    val total: Int get() = days.values.sum()

    /** The day with the most, the latest of equals; null when nothing was done. */
    val busiest: Map.Entry<LocalDate, Int>? get() = days.entries.filter { it.value > 0 }.maxWithOrNull(compareBy({ it.value }, { it.key }))
}
