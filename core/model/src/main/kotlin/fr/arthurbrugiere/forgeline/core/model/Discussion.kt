package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

/** A discussion as its repository lists it. */
data class DiscussionSummary(
    val number: Int,
    val title: String,
    val author: ForgeUser?,
    val createdAt: Instant?,
    /** The category it was filed under ("Q&A", "Announcements"). */
    val category: String?,
    val comments: Int,
    val upvotes: Int,
    /** Whether an answer was picked; null where the category takes none. */
    val isAnswered: Boolean?,
)

/** How many gave each reaction to a discussion or one of its comments, and which of them the reader gave. */
data class DiscussionReactions(
    val counts: Map<Reaction, Int> = emptyMap(),
    val given: Set<Reaction> = emptySet(),
    /** Whether the reader may give one: signed in, and not kept out by a lock. */
    val canReact: Boolean = false,
) {
    /** What giving [reaction] does: takes it back when the reader had given it. */
    fun gives(reaction: Reaction): Boolean = reaction !in given
}

/** How many upvoted a discussion or one of its comments, whether the reader did, and whether they may. */
data class DiscussionUpvotes(val count: Int, val given: Boolean = false, val canGive: Boolean = false)

/** Comments of a discussion, or replies to one of its comments, after those read, and what asks for the next. */
data class DiscussionCommentPage(val items: List<DiscussionComment>, val next: String?)

/** One page of a repository's discussions, most recently active first, and what asks for the next. */
data class DiscussionPage(val items: List<DiscussionSummary>, val next: String?)

data class DiscussionComment(
    val id: String,
    val author: ForgeUser?,
    val body: String,
    val createdAt: Instant?,
    val upvotes: Int,
    /** Picked as the answer to the discussion. */
    val isAnswer: Boolean,
    /** What was answered to this comment; replies have none of their own. */
    val replies: List<DiscussionComment> = emptyList(),
    /** How many replies the forge holds, which may be more than [replies]. */
    val replyCount: Int = replies.size,
    /** What asks the forge for the replies after [replies]; null once they are all read. */
    val repliesAfter: String? = null,
    /** Whether the reader may pick it as the answer, or take that back: the discussion's author and maintainers may. */
    val canMarkAnswer: Boolean = false,
    val canUnmarkAnswer: Boolean = false,
    val reactions: DiscussionReactions = DiscussionReactions(),
    val upvoted: Boolean = false,
    val canUpvote: Boolean = false,
    /** Whether the reader may rewrite it or delete it: their own, or a repository they look after. */
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
) {
    val upvoteTally: DiscussionUpvotes get() = DiscussionUpvotes(upvotes, upvoted, canUpvote)
}

/** A discussion with its text and its comments. */
data class Discussion(
    val summary: DiscussionSummary,
    val body: String,
    /** The first comments, oldest first; [DiscussionSummary.comments] may count more. */
    val comments: List<DiscussionComment>,
    /** What the forge names it by, to comment on it; null where it can't be commented on from here. */
    val id: String? = null,
    /** What asks the forge for the comments after [comments]; null once they are all read. */
    val commentsAfter: String? = null,
    /** Locked: only those who look after the repository may still comment. */
    val locked: Boolean = false,
    /** Those given to its own text. */
    val reactions: DiscussionReactions = DiscussionReactions(),
    /** Whether the reader upvoted it, and may; how many did is in [summary]. */
    val upvoted: Boolean = false,
    val canUpvote: Boolean = false,
    /** Whether the reader may rewrite its title and text, or delete it with its comments. */
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    /** Whether the reader hears of every comment on it; null where they can't follow it (signed out). */
    val subscribed: Boolean? = null,
) {
    val upvoteTally: DiscussionUpvotes get() = DiscussionUpvotes(summary.upvotes, upvoted, canUpvote)

    /** The comment or reply of that id. */
    fun commentOrReply(id: String): DiscussionComment? = comments.firstNotNullOfOrNull { comment -> if (comment.id == id) comment else comment.replies.firstOrNull { it.id == id } }

    /** Its own upvotes ([id]), or those of the comment or reply of that id; null when it has none such. */
    fun upvotesOf(subjectId: String): DiscussionUpvotes? = when (subjectId) {
        id -> upvoteTally
        else -> comments.firstNotNullOfOrNull { comment -> if (comment.id == subjectId) comment.upvoteTally else comment.replies.firstOrNull { it.id == subjectId }?.upvoteTally }
    }

    /** The same discussion, [subjectId]'s upvotes now being [upvotes]. */
    fun withUpvotes(subjectId: String, upvotes: DiscussionUpvotes): Discussion = if (subjectId == id) {
        copy(summary = summary.copy(upvotes = upvotes.count), upvoted = upvotes.given, canUpvote = upvotes.canGive)
    } else {
        val change = { c: DiscussionComment -> if (c.id == subjectId) c.copy(upvotes = upvotes.count, upvoted = upvotes.given, canUpvote = upvotes.canGive) else c }
        copy(comments = comments.map { comment -> change(comment).copy(replies = comment.replies.map(change)) })
    }

    /** Whether comments or replies were left out: the forge's site has them all. */

    /** What was given to its own text ([id]) or to the comment or reply of that id; null when it has none such. */
    fun reactionsOf(subjectId: String): DiscussionReactions? = when (subjectId) {
        id -> reactions
        else -> comments.firstNotNullOfOrNull { comment -> if (comment.id == subjectId) comment.reactions else comment.replies.firstOrNull { it.id == subjectId }?.reactions }
    }

    /** The same discussion, what [subjectId] was given now being [reactions]. */
    fun withReactions(subjectId: String, reactions: DiscussionReactions): Discussion = if (subjectId == id) {
        copy(reactions = reactions)
    } else {
        copy(
            comments = comments.map { comment ->
                if (comment.id == subjectId) {
                    comment.copy(reactions = reactions)
                } else {
                    comment.copy(replies = comment.replies.map { if (it.id == subjectId) it.copy(reactions = reactions) else it })
                }
            },
        )
    }

    val isPartial: Boolean get() = comments.size < summary.comments || comments.any { it.replies.size < it.replyCount }
}
