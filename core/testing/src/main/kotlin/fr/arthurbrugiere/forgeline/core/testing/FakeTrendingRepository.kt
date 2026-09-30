package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingSnapshot
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTrendingRepository : TrendingRepository {
    val snapshots = TrendingPeriod.entries.associateWith { MutableStateFlow(TrendingSnapshot(emptyList(), null)) }
    val refreshes = mutableListOf<Pair<TrendingPeriod, Boolean>>()
    var nextResult: RefreshResult = RefreshResult.Refreshed

    override fun observe(period: TrendingPeriod): Flow<TrendingSnapshot> = snapshots.getValue(period)

    override suspend fun refresh(period: TrendingPeriod, force: Boolean): RefreshResult {
        refreshes += period to force
        return nextResult
    }

    val marks = mutableMapOf<TrendingPeriod, Pair<RepoId, Int>>()

    val measured = mutableListOf<ForgeInstance>()
    var measureResult: RefreshResult = RefreshResult.Refreshed
    val measuredAt = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())

    override suspend fun measure(forge: ForgeInstance): RefreshResult {
        measured += forge
        return measureResult
    }

    override fun observeMeasuredAt(): Flow<Map<String, Long>> = measuredAt

    /** Marks of pages narrowed to one forge, by period and host. */
    val forgeMarks = mutableMapOf<Pair<TrendingPeriod, String>, Pair<RepoId, Int>>()

    override suspend fun readThrough(period: TrendingPeriod, only: ForgeInstance?): RepoId? =
        if (only == null) marks[period]?.first else forgeMarks[period to only.host]?.first

    override suspend fun markReadThrough(period: TrendingPeriod, repo: RepoId, rank: Int, only: ForgeInstance?) {
        if (only == null) {
            if (rank > (marks[period]?.second ?: -1)) marks[period] = repo to rank
        } else if (rank > (forgeMarks[period to only.host]?.second ?: -1)) {
            forgeMarks[period to only.host] = repo to rank
        }
    }
}
