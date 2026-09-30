package fr.arthurbrugiere.forgeline.forge.forgejo.trending

import fr.arthurbrugiere.forgeline.forge.forgejo.TrendingEntry
import fr.arthurbrugiere.forgeline.forge.forgejo.TrendingFile
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * What the job remembers between runs, published next to the lists: each tracked repository's star count on each
 * recorded day, and the new forks each repository got each day. Keyed by Codeberg's numeric id, so renames keep their
 * history. Arrays line up with [days], oldest first.
 */
@Serializable
data class TrendingState(
    val days: List<String> = emptyList(),
    val repos: Map<String, TrackedRepo> = emptyMap(),
    val newForks: Map<String, List<Int>> = emptyMap(),
    val lastRunAt: String? = null,
)

@Serializable
data class TrackedRepo(val createdAt: String, val stars: List<Int?>)

/** One run's reading of Codeberg: the top repositories by stars, and the newest forks. */
data class Crawl(val at: Instant, val top: List<CrawledRepo>, val forks: List<CrawledFork>)

data class CrawledRepo(
    val id: Long,
    val owner: String,
    val name: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val forks: Int,
    val createdAt: Instant,
    val ownerAvatarUrl: String?,
)

data class CrawledFork(val createdAt: Instant, val parentId: Long)

const val HISTORY_DAYS = 31
const val LIST_SIZE = 25
const val MIN_PERIOD_STARS = 2

private val PERIODS = listOf(1, 7, 30)

/**
 * Adds [crawl] to the history. A second run on the same day replaces that day's star counts and adds the forks
 * created since the first. Days older than [HISTORY_DAYS] and repositories with nothing left in the window drop out.
 */
fun TrendingState.record(crawl: Crawl): TrendingState {
    val today = crawl.at.atZone(ZoneOffset.UTC).toLocalDate().toString()
    val sameDay = days.lastOrNull() == today
    val width = if (sameDay) days.size else days.size + 1
    val keep = (width - HISTORY_DAYS).coerceAtLeast(0)
    fun <T> List<T>.today(value: T): List<T> = (if (sameDay) dropLast(1) else this).plus(value).drop(keep)

    val seen = crawl.top.associateBy { it.id.toString() }
    val repos = (repos.keys + seen.keys).associateWith { id ->
        val old = repos[id]
        val stars = (old?.stars ?: List(days.size) { null }).today(seen[id]?.stars)
        TrackedRepo(old?.createdAt ?: seen.getValue(id).createdAt.toString(), stars)
    }.filterValues { repo -> repo.stars.any { it != null } }

    // Forks created since the last run, or over the last day on the first one.
    val since = lastRunAt?.let(Instant::parse) ?: crawl.at.minus(1, ChronoUnit.DAYS)
    val counted = crawl.forks.filter { it.createdAt > since && it.createdAt <= crawl.at }.groupingBy { it.parentId.toString() }.eachCount()
    val newForks = (newForks.keys + counted.keys).associateWith { id ->
        val old = newForks[id] ?: List(days.size) { 0 }
        val todayCount = (if (sameDay) old.lastOrNull() ?: 0 else 0) + (counted[id] ?: 0)
        old.today(todayCount)
    }.filterValues { counts -> counts.any { it > 0 } }

    return TrendingState((if (sameDay) days else days + today).drop(keep), repos, newForks, crawl.at.toString())
}

/**
 * The daily, weekly and monthly lists from a recorded state and today's [crawl]. Stars gained over a period are only
 * ever exact: today's count minus the count at the period's start (the latest recorded day on or before it), or, for
 * a repository created within the period, all its stars. Anything else waits until it has a starting point.
 */
fun TrendingState.lists(crawl: Crawl): TrendingFile {
    val (daily, weekly, monthly) = PERIODS.map { periodDays -> list(crawl, periodDays) }
    return TrendingFile(crawl.at.toString(), daily, weekly, monthly)
}

private fun TrendingState.list(crawl: Crawl, periodDays: Int): List<TrendingEntry> {
    val startDay = crawl.at.atZone(ZoneOffset.UTC).toLocalDate().minusDays(periodDays.toLong())
    val startInstant = crawl.at.minus(periodDays.toLong(), ChronoUnit.DAYS)
    val parsedDays = days.map(LocalDate::parse)
    val baseline = parsedDays.indexOfLast { it <= startDay }.takeIf { it >= 0 }
    val inPeriod = parsedDays.indices.filter { parsedDays[it] > startDay }
    return crawl.top.mapNotNull { repo ->
        val id = repo.id.toString()
        val before = baseline?.let { repos[id]?.stars?.getOrNull(it) }
        val gained = when {
            before != null -> (repo.stars - before).coerceAtLeast(0)
            repo.createdAt >= startInstant -> repo.stars
            else -> return@mapNotNull null
        }
        if (gained < MIN_PERIOD_STARS) return@mapNotNull null
        val forks = newForks[id]?.let { counts -> inPeriod.sumOf { counts.getOrElse(it) { 0 } } } ?: 0
        TrendingEntry(repo.owner, repo.name, repo.description, repo.language, repo.stars, repo.forks, gained, forks, repo.ownerAvatarUrl)
    }
        .sortedWith(compareByDescending<TrendingEntry> { it.periodStars }.thenByDescending { it.periodForks }.thenByDescending { it.stars })
        .take(LIST_SIZE)
}
