package fr.arthurbrugiere.forgeline.feed

import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
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
 * The Feed stays a chronological timeline: nothing is promoted or reordered. The only change is
 * that identical events on the same repo (three people starring it) become one row, at the newest.
 */
fun feedItems(events: List<FeedEvent>, shown: Set<FeedKind>): List<FeedItem> {
    val items = LinkedHashMap<Pair<RepoId, FeedAction>, FeedItem>()
    events.filter { it.action.kind in shown }.sortedByDescending { it.createdAt }.forEach { event ->
        items.merge(event.repo to event.action, FeedItem(event.id, listOf(event.actor), event.repo, event.action, event.createdAt)) { first, later ->
            if (first.actors.any { it.login == event.actor.login }) first else first.copy(actors = first.actors + later.actors)
        }
    }
    return items.values.toList()
}

/** The repository whose preview (description, language, stars) this row shows, when the event doesn't carry it. */
val FeedItem.previewRepo: RepoId?
    get() = when (action) {
        FeedAction.Starred, FeedAction.MadePublic, is FeedAction.Forked -> repo
        else -> null
    }

/** The pull request whose title this row shows: pull request events carry only the number. */
val FeedItem.previewPull: IssueRef?
    get() = when (val a = action) {
        is FeedAction.PullRequest -> IssueRef(repo, a.number)
        is FeedAction.Reviewed -> IssueRef(repo, a.number)
        is FeedAction.Commented -> if (a.isPullRequest && a.title == null) IssueRef(repo, a.number) else null
        else -> null
    }
