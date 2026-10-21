package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.FeedDetail
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

class FakeFeedApi : FeedApi {
    /** Events per page; a page is followed by the next one when it exists. */
    val pages: MutableMap<Int, List<FeedEvent>> = java.util.concurrent.ConcurrentHashMap()
    var lastModified: String = "Sun, 27 Sep 2026 10:00:00 GMT"
    var failure: ForgeError? = null

    // The feed refreshes in the background while a test reads what was asked: plain lists broke under that now and then.
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** When set, pages wait for it: a slow forge. */
    var gate: CompletableDeferred<Unit>? = null

    /** What the starred repositories published; null when the forge can't be asked right now. */
    var starred: List<FeedEvent>? = emptyList()
    val starredCalls: MutableList<Instant> = java.util.concurrent.CopyOnWriteArrayList()

    /** When set, starred activity waits for it: GitHub takes about 10 s per 100 stars. */
    var starredGate: CompletableDeferred<Unit>? = null

    /** What the forge answers for many rows at once; null where it can't, and they're looked up one by one. */
    var details: Map<FeedDetailKey, FeedDetail>? = null
    var detailsFailure: ForgeError? = null
    val detailCalls: MutableList<List<FeedDetailKey>> = java.util.concurrent.CopyOnWriteArrayList()

    override suspend fun details(token: String, keys: List<FeedDetailKey>): ForgeResult<Map<FeedDetailKey, FeedDetail>>? {
        val all = details ?: return null
        detailCalls += keys
        detailsFailure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(all.filterKeys { it in keys })
    }

    override suspend fun starredActivity(token: String, since: Instant): ForgeResult<List<FeedEvent>> {
        starredCalls += since
        starredGate?.await()
        return starred?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Network)
    }

    override suspend fun receivedEvents(token: String?, login: String, page: Int, ifModifiedSince: String?): ForgeResult<FeedPage> {
        calls += "$login@$page" + (ifModifiedSince?.let { " since $it" } ?: "")
        gate?.await()
        failure?.let { return ForgeResult.Failure(it) }
        if (page == 1 && ifModifiedSince == lastModified) return ForgeResult.Success(FeedPage(null, null, lastModified, 60))
        val next = (page + 1).takeIf { it in pages }
        return ForgeResult.Success(FeedPage(pages[page].orEmpty(), next, lastModified, 60))
    }
}

fun feedEvent(
    id: String,
    actor: String = "alice",
    repo: String = "acme/rocket",
    action: FeedAction = FeedAction.Starred,
    createdAt: String = "2026-09-27T09:00:00Z",
): FeedEvent {
    val (owner, name) = repo.split('/')
    return FeedEvent(id, ForgeUser(actor, null, "https://avatars.example/$actor"), RepoId(owner, name), action, Instant.parse(createdAt))
}
