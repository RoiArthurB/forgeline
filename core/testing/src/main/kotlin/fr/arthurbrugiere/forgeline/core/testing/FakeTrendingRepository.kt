package fr.arthurbrugiere.forgeline.core.testing

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

    override suspend fun readThrough(period: TrendingPeriod): RepoId? = marks[period]?.first

    override suspend fun markReadThrough(period: TrendingPeriod, repo: RepoId, rank: Int) {
        if (rank > (marks[period]?.second ?: -1)) marks[period] = repo to rank
    }
}
