package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingSnapshot
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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

    val measured = mutableListOf<ForgeInstance>()
    var measureResult: RefreshResult = RefreshResult.Refreshed
    val measuredAt = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())

    override suspend fun measure(forge: ForgeInstance): RefreshResult {
        measured += forge
        return measureResult
    }

    override fun observeMeasuredAt(): Flow<Map<String, Long>> = measuredAt

    /** The forges whose Trending can be measured on the phone; every one but GitHub unless a test says otherwise. */
    var measurable: (ForgeInstance) -> Boolean = { it.type != fr.arthurbrugiere.forgeline.core.model.ForgeType.GITHUB }

    override fun canMeasure(forge: ForgeInstance): Boolean = measurable(forge)
}
