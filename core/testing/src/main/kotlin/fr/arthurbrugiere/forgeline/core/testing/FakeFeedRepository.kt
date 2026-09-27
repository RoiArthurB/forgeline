package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.feed.FeedRepository
import fr.arthurbrugiere.forgeline.core.data.feed.FeedSnapshot
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFeedRepository : FeedRepository {
    val snapshot = MutableStateFlow(FeedSnapshot(emptyList(), null, hasMore = false))
    var failure: ForgeError? = null
    val refreshes = mutableListOf<Boolean>()
    var loadMoreCalls = 0

    /** Returned by the next [loadMore]; appended to the snapshot. */
    var olderPage: List<FeedEvent> = emptyList()

    fun set(vararg events: FeedEvent, hasMore: Boolean = false) {
        snapshot.value = FeedSnapshot(events.toList(), 1_000, hasMore)
    }

    override fun observe(): Flow<FeedSnapshot> = snapshot

    override suspend fun refresh(force: Boolean): ForgeResult<Unit> {
        refreshes += force
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(Unit)
    }

    override suspend fun loadMore(): ForgeResult<Unit> {
        loadMoreCalls++
        failure?.let { return ForgeResult.Failure(it) }
        snapshot.value = snapshot.value.copy(events = snapshot.value.events + olderPage, hasMore = false)
        return ForgeResult.Success(Unit)
    }
}
