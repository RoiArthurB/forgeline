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
    /** Whether the reader may pick it as the answer, or take that back: the discussion's author and maintainers may. */
    val canMarkAnswer: Boolean = false,
    val canUnmarkAnswer: Boolean = false,
)

/** A discussion with its text and its comments. */
data class Discussion(
    val summary: DiscussionSummary,
    val body: String,
    /** The first comments, oldest first; [DiscussionSummary.comments] may count more. */
    val comments: List<DiscussionComment>,
    /** What the forge names it by, to comment on it; null where it can't be commented on from here. */
    val id: String? = null,
    /** Locked: only those who look after the repository may still comment. */
    val locked: Boolean = false,
) {
    /** Whether comments or replies were left out: the forge's site has them all. */
    val isPartial: Boolean get() = comments.size < summary.comments || comments.any { it.replies.size < it.replyCount }
}
