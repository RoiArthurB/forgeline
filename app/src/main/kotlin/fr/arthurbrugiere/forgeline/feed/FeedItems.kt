package fr.arthurbrugiere.forgeline.feed

import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.FeedKind
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
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
