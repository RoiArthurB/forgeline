package fr.arthurbrugiere.forgeline.feed

import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
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
