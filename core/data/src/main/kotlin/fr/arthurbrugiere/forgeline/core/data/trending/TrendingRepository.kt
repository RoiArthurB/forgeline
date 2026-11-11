package fr.arthurbrugiere.forgeline.core.data.trending

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkDao
import fr.arthurbrugiere.forgeline.core.data.reading.advance
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * One page mixing every forge's ranking, [repos] already merged and balanced between forges (see [balanceForges]).
 * [fetchedAtMillis] is the oldest of the forges' fetches, and [forges] lists the forges on the page, for showing each
 * row's forge when there are several. [unbalanced] is the same merge before balancing, every repository above the
 * period's floor: a page narrowed to one forge shows that forge's whole list.
 */
data class TrendingSnapshot(
    val repos: List<TrendingRepo>,
    val fetchedAtMillis: Long?,
    val forges: List<ForgeInstance> = emptyList(),
    val unbalanced: List<TrendingRepo> = repos,
)

sealed interface RefreshResult {
    data object Refreshed : RefreshResult

    /** The cache was recent enough; nothing fetched. */
    data object Fresh : RefreshResult

    data class Failed(val error: ForgeError) : RefreshResult
}

interface TrendingRepository {
    fun observe(period: TrendingPeriod): Flow<TrendingSnapshot>

    suspend fun refresh(period: TrendingPeriod, force: Boolean = false): RefreshResult

    /**
     * The repository read furthest down [period] during the current browse, or null once it has gone stale. A repo,
     * not a rank: the list reorders as it refreshes, and the reader stopped at a repo, not at a number.
     */
    suspend fun readThrough(period: TrendingPeriod, only: ForgeInstance? = null): RepoId?

    /**
     * Measures [forge]'s Trending on the phone (a forge the settings measure): one day more of history, and the lists
     * it gives. No network for the page itself: it reads what the last measurement left.
     */
    suspend fun measure(forge: ForgeInstance): RefreshResult

    /** When each measured forge (by host) was last measured. */
    fun observeMeasuredAt(): Flow<Map<String, Long>>

    /** Whether [forge]'s Trending can be measured on the phone: not GitHub's, nor a forge without a way to. */
    fun canMeasure(forge: ForgeInstance): Boolean

    /**
     * Records that [repo], at [rank] (0-based) in the list as shown, was read; only ever moves the mark further down.
     * A page narrowed to [only] one forge keeps its own mark: its ranks aren't the mixed page's.
     */
    suspend fun markReadThrough(period: TrendingPeriod, repo: RepoId, rank: Int, only: ForgeInstance? = null)
}

/**
 * GitHub's Trending and the lists published daily for Codeberg and gitlab.com, always (none needs an account), and that
 * of each other forge an account is signed in to. A forge signed in to can be measured on the phone instead, when the
 * settings ask for it. Each forge's ranking is cached and refreshed on its own; the page merges them with [mergeByShare].
 */
@Singleton
class DefaultTrendingRepository @Inject constructor(
    private val dao: TrendingDao,
    private val measurements: TrendingMeasurementDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val marks: ReadingMarkDao,
    private val clock: Clock,
    private val settings: UserSettingsRepository,
    @param:Computation private val computation: CoroutineDispatcher,
) : TrendingRepository {

    private val refreshLock = Mutex()

    /** The forges on the page, GitHub first: it wins ties in the merge. */
    private val forges: Flow<List<ForgeInstance>> = combine(accounts.accounts, settings.settings) { signedIn, settings ->
        val measured = measuredHere(signedIn.map { it.forge }, settings.measuredTrending)
        (PUBLISHED + signedIn.map { it.forge }).distinct().filter { forge ->
            if (forge in measured) clients.trendingMeter(forge) != null else clients.trending(forge) != null
        }
    }.distinctUntilChanged()

    /**
     * Forges measured on the phone: those the settings ask for, while signed in there, and that can be. Signing out
     * goes back to the published list. Regression: a forge that can't be measured, asked for, left Trending.
     */
    private fun measuredHere(signedIn: List<ForgeInstance>, hosts: Set<String>) = signedIn.filter { it.host in hosts && canMeasure(it) }.toSet()

    override fun canMeasure(forge: ForgeInstance): Boolean = clients.trendingMeter(forge) != null

    private suspend fun isMeasuredHere(forge: ForgeInstance) =
        forge in measuredHere(accounts.accounts.first().map { it.forge }, settings.settings.first().measuredTrending)

    override fun observe(period: TrendingPeriod): Flow<TrendingSnapshot> =
        combine(forges, dao.observeRepos(period.name), dao.observeFetches(period.name)) { forges, repos, fetches ->
            val byForge = repos.map { it.toModel() }.filter { it.id.forge.type.curatesTrending || it.periodStars >= period.minStars }.groupBy { it.id.forge }
            val times = fetches.filter { fetch -> forges.any { it.host == fetch.host } }.map { it.fetchedAtMillis }
            val merged = mergeByShare(forges.map { byForge[it].orEmpty() })
            TrendingSnapshot(balanceForges(merged), times.minOrNull(), forges, merged)
        }.flowOn(computation)

    /** Refreshes every forge's ranking at once. Fails only when none could be read: one forge down keeps the others. */
    override suspend fun refresh(period: TrendingPeriod, force: Boolean): RefreshResult = refreshLock.withLock {
        val results = coroutineScope {
            forges.first().map { forge -> async { refresh(forge, period, force) } }.awaitAll()
        }
        when {
            results.any { it == RefreshResult.Refreshed } -> RefreshResult.Refreshed
            results.any { it == RefreshResult.Fresh } -> RefreshResult.Fresh
            else -> results.firstOrNull() ?: RefreshResult.Fresh
        }
    }

    private suspend fun refresh(forge: ForgeInstance, period: TrendingPeriod, force: Boolean): RefreshResult {
        // Measured on the phone: the lists are what the last measurement left, nothing to fetch.
        if (isMeasuredHere(forge)) return RefreshResult.Fresh
        val api = clients.trending(forge) ?: return RefreshResult.Fresh
        val fetchedAt = dao.fetchedAt(forge.host, period.name)
        if (!force && fetchedAt != null && clock.millis() - fetchedAt < MAX_AGE.inWholeMilliseconds) {
            return RefreshResult.Fresh
        }
        return when (val result = api.trending(period)) {
            is ForgeResult.Failure -> RefreshResult.Failed(result.error)
            is ForgeResult.Success -> {
                // An empty scraped page means GitHub's markup changed, not that nothing trends: keep the cache. A
                // published list can be empty for real (a quiet day on gitlab.com).
                if (result.value.isEmpty() && forge == ForgeInstance.GitHub) return RefreshResult.Failed(ForgeError.Http(200, "No trending repositories found"))
                dao.replace(
                    forge.host,
                    period.name,
                    result.value.mapIndexed { rank, repo -> repo.copy(id = repo.id.copy(forge = forge)).toEntity(period, rank) },
                    clock.millis(),
                )
                RefreshResult.Refreshed
            }
        }
    }

    override suspend fun measure(forge: ForgeInstance): RefreshResult {
        val meter = clients.trendingMeter(forge) ?: return RefreshResult.Failed(ForgeError.Unsupported)
        val previous = measurements.measurement(forge.host)?.state
        return when (val result = meter.measure(accounts.tokenOn(forge), previous)) {
            is ForgeResult.Failure -> RefreshResult.Failed(result.error)
            is ForgeResult.Success -> {
                val now = clock.millis()
                measurements.upsert(TrendingMeasurementEntity(forge.host, result.value.state, now))
                // Empty lists are real here (too little history yet), unlike an empty scraped page.
                TrendingPeriod.entries.forEach { period ->
                    val repos = result.value.lists[period].orEmpty()
                    dao.replace(forge.host, period.name, repos.mapIndexed { rank, repo -> repo.copy(id = repo.id.copy(forge = forge)).toEntity(period, rank) }, now)
                }
                RefreshResult.Refreshed
            }
        }
    }

    override fun observeMeasuredAt(): Flow<Map<String, Long>> =
        measurements.observe().map { rows -> rows.associate { it.host to it.measuredAtMillis } }

    override suspend fun readThrough(period: TrendingPeriod, only: ForgeInstance?): RepoId? =
        marks.get(period.markList(only))?.takeIf { clock.millis() - it.markedAtMillis < MARK_MAX_AGE.inWholeMilliseconds }?.itemKey
            ?.let(RepoId::fromKey)

    override suspend fun markReadThrough(period: TrendingPeriod, repo: RepoId, rank: Int, only: ForgeInstance?) =
        marks.advance(period.markList(only), repo.key, rank.toLong(), clock.millis(), MARK_MAX_AGE.inWholeMilliseconds)

    private fun TrendingPeriod.markList(only: ForgeInstance?) = "trending:$name" + only?.let { ":${it.host}" }.orEmpty()

    private companion object {
        /** Forges whose Trending is on everyone's page: GitHub's and Tangled's own, and the lists published daily for the others. */
        val PUBLISHED = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab, ForgeInstance.Tangled)

        val MAX_AGE = 1.hours

        /** A daily browse: yesterday evening's place still counts this morning, last week's doesn't. */
        val MARK_MAX_AGE = 20.hours
    }
}

/**
 * The stars a repository must have gained over the period to be on the page at all, whichever forge it is on. Below
 * it, a list is "every project that got a star or two": on 2026-10-01, Codeberg's daily list ran down to +2 and
 * gitlab.com's whole list was +3 and +2s. GitHub's own Trending ends around +10 to +15 a day, so 5 a day keeps what
 * would stand on GitHub's page while leaving room for smaller forges (Codeberg's top ten that day gained 14 to 4).
 * An absolute floor rather than a share of the forge's stars: a share is meaningless on a quiet forge (5% of 30 stars
 * is one and a half) and far too strict on GitHub (5% of 7,000 is 350, which would empty its page).
 * A forge that picks its own Trending ([fr.arthurbrugiere.forgeline.core.model.ForgeType.curatesTrending], Tangled) is
 * shown whole: its few repositories were already chosen, and a floor on stars counted here left one of five.
 */
val TrendingPeriod.minStars: Int
    get() = when (this) {
        TrendingPeriod.DAILY -> 5
        TrendingPeriod.WEEKLY -> 10
        TrendingPeriod.MONTHLY -> 20
    }

/** The repositories each forge shows on a mixed page, before [balanceForges]' extra for an active forge. */
const val PER_FORGE = 10

/** The most a forge can show beyond [PER_FORGE], one more for each [ACTIVE_STEP] repositories its list has beyond it. */
const val MAX_ACTIVE_EXTRA = 2
const val ACTIVE_STEP = 5

/**
 * Gives each forge about the same room on a mixed page, so neither GitHub's 25 nor a small forge's long tail fills it:
 * each shows its first [PER_FORGE] of [page], plus one for every [ACTIVE_STEP] more it has (at most [MAX_ACTIVE_EXTRA]).
 * A forge with fewer shows what it has; the page isn't padded with another forge's tail. Keeps [page]'s order, and
 * leaves a page with a single forge on it as it is: there's nothing to balance it against.
 */
fun balanceForges(page: List<TrendingRepo>): List<TrendingRepo> {
    val sizes = page.groupingBy { it.id.forge }.eachCount()
    if (sizes.size < 2) return page
    val quotas = sizes.mapValues { (_, size) -> PER_FORGE + ((size - PER_FORGE) / ACTIVE_STEP).coerceIn(0, MAX_ACTIVE_EXTRA) }
    val shown = mutableMapOf<ForgeInstance, Int>()
    return page.filter { repo ->
        val rank = shown.merge(repo.id.forge, 1, Int::plus)!!
        rank <= quotas.getValue(repo.id.forge)
    }
}

/**
 * Merges each forge's ranking into one page, by each repository's share of its forge's stars gained over the list
 * (`periodStars / sum`), so 30 stars on Codeberg can stand with 2,400 on GitHub. Like a merge sort's merge step: each
 * forge's own order is kept, and the head with the larger share goes next; the forge listed first wins ties.
 *
 * A forge listing fewer than [MIN_SHARE_ENTRIES] repositories has its shares scaled down in proportion
 * (`size / MIN_SHARE_ENTRIES`). Regression: a share is only comparable when the list it is taken over is long enough.
 * Three projects with 3, 2 and 2 stars "held" 43%, 29% and 29% of their forge and stood above a repository that
 * gained over a thousand; a forge's lists are that short on quiet days and while its history builds up.
 *
 * And a forge whose whole list gained fewer than [MIN_FORGE_STARS] is weighed as if it had gained that many: with 17
 * stars gained across gitlab.com's list, the first project's 3 stars were 18% of its forge and led the page.
 */

/** The list length from which a forge's shares count in full. Every list GitHub gives is longer. */
const val MIN_SHARE_ENTRIES = 10

/** The stars a forge's list must have gained in all for its shares to count in full. */
const val MIN_FORGE_STARS = 100L

fun mergeByShare(rankings: List<List<TrendingRepo>>): List<TrendingRepo> {
    val totals = rankings.map { list -> list.sumOf { it.periodStars.toLong() } }
    val next = IntArray(rankings.size)
    fun share(forge: Int): Double {
        val total = totals[forge]
        if (total == 0L) return 0.0
        val weight = minOf(1.0, rankings[forge].size.toDouble() / MIN_SHARE_ENTRIES)
        return rankings[forge][next[forge]].periodStars.toDouble() / maxOf(total, MIN_FORGE_STARS) * weight
    }
    return buildList {
        while (true) {
            val forge = rankings.indices.filter { next[it] < rankings[it].size }.maxWithOrNull(
                compareBy<Int> { share(it) }.thenByDescending { it },
            ) ?: break
            add(rankings[forge][next[forge]++])
        }
    }
}
