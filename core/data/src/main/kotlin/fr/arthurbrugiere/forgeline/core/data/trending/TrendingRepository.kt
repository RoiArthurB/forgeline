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
 * period's floors ([isTrending]): a page narrowed to one forge shows that forge's whole list.
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
 * settings ask for it. Each forge's ranking is cached and refreshed on its own; the page ranks them together with [rankByGrowth].
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
            val byForge = repos.map { it.toModel() }.groupBy { it.id.forge }
            val times = fetches.filter { fetch -> forges.any { it.host == fetch.host } }.map { it.fetchedAtMillis }
            val merged = rankByGrowth(forges.map { byForge[it].orEmpty() }, period)
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
 * How fast [this] grew over the period, against its own size: the stars it gained over those it had before, plus
 * [GROWTH_PRIOR]. Seven stars a day on a 7,000-star repository is 0.1%, its daily noise; twelve on a 1,300-star one is
 * nearly 1%. The prior keeps a brand-new repository's first handful from reading as a 100% jump (+5 on nothing is 10%).
 */
val TrendingRepo.growth: Double
    get() = periodStars.toDouble() / ((stars - periodStars).coerceAtLeast(0) + GROWTH_PRIOR)

/** The stars [TrendingRepo.growth] counts a repository as having had before the period, on top of its own. */
const val GROWTH_PRIOR = 50

/**
 * The [TrendingRepo.growth] a repository must reach over the period to be on the page: a gain that is small for its
 * own size is the usual flow of stars a well-known project gets every day, not a trend. Regression: Codeberg's
 * ziglang/zig stood second on the mixed page with +7 on 7,000 stars (0.1%) and forgejo third with +8 on 5,800.
 * Under linear for the longer periods: a burst doesn't repeat every day of the month.
 */
val TrendingPeriod.minGrowth: Double
    get() = when (this) {
        TrendingPeriod.DAILY -> 0.0025
        TrendingPeriod.WEEKLY -> 0.01
        TrendingPeriod.MONTHLY -> 0.03
    }

/**
 * Whether [this] stands on [period]'s page at all: it gained at least [minStars] and grew by at least [minGrowth] of
 * its own size. A forge that picks its own Trending (Tangled) is shown whole.
 */
fun TrendingRepo.isTrending(period: TrendingPeriod): Boolean =
    id.forge.type.curatesTrending || (periodStars >= period.minStars && growth >= period.minGrowth)

/**
 * Ranks every forge's repositories on one page by how fast each grew against its own size ([growth]), relative to how
 * fast its forge's listed repositories grew that period (their median): `growth / median`. A repository at three times
 * its forge's usual pace stands with one at three times its own forge's, whatever the forges' sizes, so a big day on
 * GitHub and a big day on Codeberg mix on merit rather than by turns. Only repositories that [isTrending] are kept; the
 * median is taken over each forge's whole list, the ones under the floors included, since they are its usual pace.
 * Ties keep the forges' order, then each forge's own.
 *
 * Replaces a merge by share of the forge's stars gained, which no tuning made fair: on 2026-11-14 Codeberg's whole
 * first page (+12 down to +5) stood above GitHub's +1,925. Taking turns between forges was tried too, and read as
 * artificial: it hid which forge really moved.
 */
fun rankByGrowth(rankings: List<List<TrendingRepo>>, period: TrendingPeriod): List<TrendingRepo> =
    rankings.flatMap { list ->
        val median = list.map { it.growth }.median().coerceAtLeast(MIN_MEDIAN_GROWTH)
        list.filter { it.isTrending(period) }.map { it to it.growth / median }
    }.sortedByDescending { (_, score) -> score }.map { (repo, _) -> repo }

/**
 * The least a forge's median [growth] is counted as: a list whose repositories mostly gained nothing (Tangled's pick on
 * a quiet day) would otherwise make any gain on it infinitely fast.
 */
const val MIN_MEDIAN_GROWTH = 0.001

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    val middle = size / 2
    return if (size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
