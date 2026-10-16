package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFeedPreviewRepository : FeedPreviewRepository {
    val previews = MutableStateFlow(FeedPreviews())
    val requested = mutableSetOf<FeedDetailKey>()

    override fun observe(): Flow<FeedPreviews> = previews

    override suspend fun ensure(keys: Set<FeedDetailKey>) {
        requested += keys
    }
}
