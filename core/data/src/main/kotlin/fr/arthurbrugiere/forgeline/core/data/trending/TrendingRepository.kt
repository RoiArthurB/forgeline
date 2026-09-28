package fr.arthurbrugiere.forgeline.core.data.trending

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours

data class TrendingSnapshot(val repos: List<TrendingRepo>, val fetchedAtMillis: Long?)

sealed interface RefreshResult {
    data object Refreshed : RefreshResult

    /** The cache was recent enough; nothing fetched. */
    data object Fresh : RefreshResult

    data class Failed(val error: ForgeError) : RefreshResult
}

interface TrendingRepository {
    fun observe(period: TrendingPeriod): Flow<TrendingSnapshot>

    suspend fun refresh(period: TrendingPeriod, force: Boolean = false): RefreshResult

    /** The furthest rank (0-based) read in [period] during the current browse, or null once it has gone stale. */
    suspend fun readThrough(period: TrendingPeriod): Int?

    /** Records that [rank] was read; only ever moves the mark further down. */
    suspend fun markReadThrough(period: TrendingPeriod, rank: Int)
}

class DefaultTrendingRepository @Inject constructor(
    private val dao: TrendingDao,
    private val api: TrendingApi,
    private val clock: Clock,
) : TrendingRepository {

    private val refreshLock = Mutex()

    override fun observe(period: TrendingPeriod): Flow<TrendingSnapshot> =
        combine(dao.observeRepos(period.name), dao.observeFetchedAt(period.name)) { repos, fetchedAt ->
            TrendingSnapshot(repos.map { it.toModel() }, fetchedAt)
        }

    override suspend fun refresh(period: TrendingPeriod, force: Boolean): RefreshResult = refreshLock.withLock {
        val fetchedAt = dao.fetchedAt(period.name)
        if (!force && fetchedAt != null && clock.millis() - fetchedAt < MAX_AGE.inWholeMilliseconds) {
            return RefreshResult.Fresh
        }
        when (val result = api.trending(period)) {
            is ForgeResult.Failure -> RefreshResult.Failed(result.error)
            is ForgeResult.Success -> {
                // An empty page means the markup changed, not that nothing trends: keep the cache.
                if (result.value.isEmpty()) return RefreshResult.Failed(ForgeError.Http(200, "No trending repositories found"))
                dao.replace(
                    period.name,
                    result.value.mapIndexed { rank, repo -> repo.toEntity(period, rank) },
                    clock.millis(),
                )
                RefreshResult.Refreshed
            }
        }
    }

    override suspend fun readThrough(period: TrendingPeriod): Int? =
        dao.mark(period.name)?.takeIf { clock.millis() - it.markedAtMillis < MARK_MAX_AGE.inWholeMilliseconds }?.rank

    override suspend fun markReadThrough(period: TrendingPeriod, rank: Int) {
        val current = readThrough(period)
        if (current == null || rank > current) dao.upsertMark(TrendingMarkEntity(period.name, rank, clock.millis()))
    }

    private companion object {
        val MAX_AGE = 1.hours

        /** A daily browse: yesterday evening's place still counts this morning, last week's doesn't. */
        val MARK_MAX_AGE = 20.hours
    }
}
