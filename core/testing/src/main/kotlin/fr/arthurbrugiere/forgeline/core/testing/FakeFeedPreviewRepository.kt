package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFeedPreviewRepository : FeedPreviewRepository {
    val previews = MutableStateFlow(FeedPreviews())
    val requestedRepos = mutableSetOf<RepoId>()
    val requestedPulls = mutableSetOf<IssueRef>()

    override fun observe(): Flow<FeedPreviews> = previews

    override suspend fun ensure(repos: Set<RepoId>, pulls: Set<IssueRef>) {
        requestedRepos += repos
        requestedPulls += pulls
    }
}
