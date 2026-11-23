package fr.arthurbrugiere.forgeline.feed

import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import java.time.Instant

/** One row of the Feed: an event, or identical events on the same repo by several people. */
data class FeedItem(
    val key: String,
    val actors: List<ForgeUser>,
    val repo: RepoId,
    val action: FeedAction,
    val createdAt: Instant,
)

/**
 * The Feed stays a chronological timeline: nothing is promoted or reordered. Identical events on the same repo (three
 * people starring it) become one row, at the newest. With [hideSettled], a pull request or issue opened (or reopened)
 * and since merged or closed shows only that newer row: "merged #30" says it was opened, and two rows for it said it
 * twice.
 */
fun feedItems(events: List<FeedEvent>, shown: Set<FeedKind>, hideSettled: Boolean = false): List<FeedItem> {
    val visible = events.filter { it.action.kind in shown }
    // When each pull request and issue the Feed shows was last merged or closed: an opening before it is old news.
    val settledAt = if (!hideSettled) emptyMap() else buildMap<Conversation, Instant> {
        visible.forEach { event -> event.settles()?.let { merge(it, event.createdAt, ::maxOf) } }
    }
    val items = LinkedHashMap<Pair<RepoId, FeedAction>, FeedItem>()
    visible.sortedByDescending { it.createdAt }.forEach { event ->
        val settled = event.opens()?.let(settledAt::get)
        if (settled != null && event.createdAt <= settled) return@forEach
        items.merge(event.repo to event.action, FeedItem(event.id, listOf(event.actor), event.repo, event.action, event.createdAt)) { first, later ->
            if (first.actors.any { it.login == event.actor.login }) first else first.copy(actors = first.actors + later.actors)
        }
    }
    return items.values.toList()
}

/** A pull request or issue, by its repository and number: the two are numbered apart on some forges. */
private data class Conversation(val repo: RepoId, val number: Int, val isPullRequest: Boolean)

/** The pull request or issue this event opens or reopens. */
private fun FeedEvent.opens(): Conversation? = when (val a = action) {
    is FeedAction.PullRequest -> Conversation(repo, a.number, true).takeIf { a.action == PullRequestAction.OPENED || a.action == PullRequestAction.REOPENED }
    is FeedAction.Issue -> Conversation(repo, a.number, false).takeIf { a.action != IssueAction.CLOSED }
    else -> null
}

/** The pull request or issue this event merges or closes. */
private fun FeedEvent.settles(): Conversation? = when (val a = action) {
    is FeedAction.PullRequest -> Conversation(repo, a.number, true).takeIf { a.action == PullRequestAction.MERGED || a.action == PullRequestAction.CLOSED }
    is FeedAction.Issue -> Conversation(repo, a.number, false).takeIf { a.action == IssueAction.CLOSED }
    else -> null
}

/**
 * What this row shows that its event doesn't carry, looked up once it scrolls into view: a repository's details, a
 * release's summary, picture and reactions, an issue's labels and comments, a pull request's title and size.
 */
val FeedItem.details: List<FeedDetailKey>
    get() = when (val a = action) {
        FeedAction.Starred, FeedAction.MadePublic, is FeedAction.Forked, is FeedAction.CreatedRepo -> listOf(FeedDetailKey.Repo(repo))
        is FeedAction.Released -> listOf(FeedDetailKey.Release(ReleaseRef(repo, a.tag)))
        is FeedAction.Issue -> listOf(FeedDetailKey.Conversation(IssueRef(repo, a.number, isPullRequest = false)))
        is FeedAction.PullRequest -> listOf(FeedDetailKey.Conversation(IssueRef(repo, a.number, isPullRequest = true)))
        is FeedAction.Reviewed -> listOf(FeedDetailKey.Conversation(IssueRef(repo, a.number, isPullRequest = true)))
        // GitHub's comment events name their issue; Codeberg's give the comment, not the title.
        is FeedAction.Commented -> if (a.title == null) listOf(FeedDetailKey.Conversation(IssueRef(repo, a.number, a.isPullRequest))) else emptyList()
        else -> emptyList()
    }

/** The issue or pull request this row is about, as its preview is kept. */
val FeedItem.conversation: IssueRef?
    get() = (details.firstOrNull() as? FeedDetailKey.Conversation)?.ref

/** The release this row is about. */
val FeedItem.release: ReleaseRef?
    get() = (action as? FeedAction.Released)?.let { ReleaseRef(repo, it.tag) }
