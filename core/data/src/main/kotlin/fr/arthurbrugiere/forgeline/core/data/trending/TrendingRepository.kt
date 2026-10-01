package fr.arthurbrugiere.forgeline.core.data.trending

import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkDao
import fr.arthurbrugiere.forgeline.core.data.reading.advance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineDispatcher
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours

/**
 * One page mixing every forge's ranking, [repos] already merged. [fetchedAtMillis] is the oldest of the forges' fetches,
 * and [forges] lists the forges on the page, for showing each row's forge when there are several.
 */
data class TrendingSnapshot(val repos: List<TrendingRepo>, val fetchedAtMillis: Long?, val forges: List<ForgeInstance> = emptyList())

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

    /**
     * Records that [repo], at [rank] (0-based) in the list as shown, was read; only ever moves the mark further down.
     * A page narrowed to [only] one forge keeps its own mark: its ranks aren't the mixed page's.
     */
    suspend fun markReadThrough(period: TrendingPeriod, repo: RepoId, rank: Int, only: ForgeInstance? = null)
}

/**
 * GitHub's Trending, always (it needs no account), and that of each other forge an account is signed in to: its
 * published list when it has one, or the phone's own measurements when the settings ask for them. Each forge's
 * ranking is cached and refreshed on its own; the page merges them with [mergeByShare].
 */
@Singleton
class DefaultTrendingRepository @Inject constructor(
    private val dao: TrendingDao,
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
        (listOf(ForgeInstance.GitHub) + signedIn.map { it.forge }).distinct().filter { forge ->
            if (forge.host in settings.measuredTrending) clients.trendingMeter(forge) != null else clients.trending(forge) != null
        }
    }.distinctUntilChanged()

    override fun observe(period: TrendingPeriod): Flow<TrendingSnapshot> =
        combine(forges, dao.observeRepos(period.name), dao.observeFetches(period.name)) { forges, repos, fetches ->
            val byForge = repos.map { it.toModel() }.groupBy { it.id.forge }
            val times = fetches.filter { fetch -> forges.any { it.host == fetch.host } }.map { it.fetchedAtMillis }
            TrendingSnapshot(mergeByShare(forges.map { byForge[it].orEmpty() }), times.minOrNull(), forges)
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
        if (forge.host in settings.settings.first().measuredTrending) return RefreshResult.Fresh
        val api = clients.trending(forge) ?: return RefreshResult.Fresh
        val fetchedAt = dao.fetchedAt(forge.host, period.name)
        if (!force && fetchedAt != null && clock.millis() - fetchedAt < MAX_AGE.inWholeMilliseconds) {
            return RefreshResult.Fresh
        }
        return when (val result = api.trending(period)) {
            is ForgeResult.Failure -> RefreshResult.Failed(result.error)
            is ForgeResult.Success -> {
                // An empty page means the markup changed, not that nothing trends: keep the cache.
                if (result.value.isEmpty()) return RefreshResult.Failed(ForgeError.Http(200, "No trending repositories found"))
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
        val previous = dao.measurement(forge.host)?.state
        return when (val result = meter.measure(accounts.tokenOn(forge), previous)) {
            is ForgeResult.Failure -> RefreshResult.Failed(result.error)
            is ForgeResult.Success -> {
                val now = clock.millis()
                dao.upsertMeasurement(TrendingMeasurementEntity(forge.host, result.value.state, now))
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
        dao.observeMeasurements().map { rows -> rows.associate { it.host to it.measuredAtMillis } }

    override suspend fun readThrough(period: TrendingPeriod, only: ForgeInstance?): RepoId? =
        marks.get(period.markList(only))?.takeIf { clock.millis() - it.markedAtMillis < MARK_MAX_AGE.inWholeMilliseconds }?.itemKey
            ?.let(RepoId::fromKey)

    override suspend fun markReadThrough(period: TrendingPeriod, repo: RepoId, rank: Int, only: ForgeInstance?) =
        marks.advance(period.markList(only), repo.key, rank.toLong(), clock.millis(), MARK_MAX_AGE.inWholeMilliseconds)

    private fun TrendingPeriod.markList(only: ForgeInstance?) = "trending:$name" + only?.let { ":${it.host}" }.orEmpty()

    private companion object {
        val MAX_AGE = 1.hours

        /** A daily browse: yesterday evening's place still counts this morning, last week's doesn't. */
        val MARK_MAX_AGE = 20.hours
    }
}

/**
 * Merges each forge's ranking into one page, by each repository's share of its forge's stars gained over the list
 * (`periodStars / sum`), so 30 stars on Codeberg can stand with 2,400 on GitHub. Like a merge sort's merge step: each
 * forge's own order is kept, and the head with the larger share goes next; the forge listed first wins ties.
 */
fun mergeByShare(rankings: List<List<TrendingRepo>>): List<TrendingRepo> {
    val totals = rankings.map { list -> list.sumOf { it.periodStars.toLong() } }
    val next = IntArray(rankings.size)
    fun share(forge: Int): Double {
        val total = totals[forge]
        return if (total == 0L) 0.0 else rankings[forge][next[forge]].periodStars.toDouble() / total
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
