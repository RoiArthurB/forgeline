package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import java.time.Instant

/** What a Feed row needs looked up beyond its event: a repository, a release, or an issue or pull request. */
sealed interface FeedDetailKey {
    data class Repo(val id: RepoId) : FeedDetailKey

    data class Release(val ref: ReleaseRef) : FeedDetailKey

    data class Conversation(val ref: IssueRef) : FeedDetailKey
}

/** The answer to a [FeedDetailKey] of the same kind. */
sealed interface FeedDetail {
    data class Repo(val preview: RepoPreview) : FeedDetail

    data class Release(val preview: ReleasePreview) : FeedDetail

    data class Conversation(val preview: ConversationPreview) : FeedDetail
}

/** [events] is null when nothing changed since `ifModifiedSince`. */
data class FeedPage(
    val events: List<FeedEvent>?,
    val nextPage: Int?,
    val lastModified: String? = null,
    /** Minimum seconds the forge asks clients to wait between polls. */
    val pollIntervalSeconds: Int? = null,
)

interface FeedApi {
    /** Activity of the people [login] follows and the repos they watch, newest first. */
    suspend fun receivedEvents(token: String?, login: String, page: Int = 1, ifModifiedSince: String? = null): ForgeResult<FeedPage>

    /**
     * What the repositories the signed-in person starred published since [since]: each one's latest release and, where
     * the forge has them, its announcements. The forges' own feeds leave these out (they only cover people followed and
     * repositories watched). On GitHub, also the repositories the people followed and the organisations joined
     * created, which its events stopped saying. It can take many seconds, so it is asked apart from [receivedEvents]. An announcement
     * posted with a release is left out: the release says it.
     */
    suspend fun starredActivity(token: String, since: Instant): ForgeResult<List<FeedEvent>> = ForgeResult.Success(emptyList())

    /**
     * The details of many rows in one go, where the forge can answer them together (GitHub's GraphQL); null where it
     * can't, and they are then looked up one by one with the forge's other calls. A key missing from the answer
     * wasn't found.
     */
    suspend fun details(token: String, keys: List<FeedDetailKey>): ForgeResult<Map<FeedDetailKey, FeedDetail>>? = null
}
