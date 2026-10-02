package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

enum class Reaction { THUMBS_UP, THUMBS_DOWN, LAUGH, HOORAY, CONFUSED, HEART, ROCKET, EYES }

data class IssueRef(val repo: RepoId, val number: Int)

data class PullRequestInfo(
    val isDraft: Boolean,
    val isMerged: Boolean,
    val baseRef: String,
    val headRef: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val commits: Int,
)

data class IssueDetails(
    val ref: IssueRef,
    val title: String,
    val body: String?,
    val state: IssueState,
    /** e.g. `completed`, `not_planned`, `duplicate`, `reopened`, as the forge reports it. */
    val stateReason: String?,
    val author: ForgeUser?,
    val labels: List<Label>,
    val createdAt: Instant,
    val closedAt: Instant?,
    val comments: Int,
    val reactions: Map<Reaction, Int>,
    /** Present for pull requests. */
    val pullRequest: PullRequestInfo?,
    /** Locked conversations only take comments from the repository's collaborators. */
    val isLocked: Boolean = false,
    val assignees: List<ForgeUser> = emptyList(),
    val milestone: Milestone? = null,
    /** The day it is due, on forges that keep one. */
    val dueDate: java.time.LocalDate? = null,
)

/**
 * What the signed-in user may do in a repository beyond reading it, least to most. Triage manages its conversations
 * (labels, assignees, closing), write also changes the repository, admin also deletes from it.
 */
enum class RepoAccess { NONE, TRIAGE, WRITE, ADMIN }

/** What can be done to a conversation beyond commenting on it and closing it; a forge's API offers some of them. */
enum class ConversationAction { LABELS, ASSIGNEES, MILESTONE, CLOSE_REASON, LOCK, PIN, TRANSFER, DELETE, DUE_DATE, TIME_TRACKING, DEPENDENCIES }

/**
 * What the signed-in user may do in a repository, and what its settings or their role take away there
 * ([switchedOff]): a Forgejo repository can switch time tracking and dependencies off, or keep tracking to contributors.
 */
data class RepoRights(val access: RepoAccess, val switchedOff: Set<ConversationAction> = emptySet())

/** The time spent on a conversation by everyone, and since when the signed-in user's own timer runs on it, if it does. */
data class TimeTracking(val totalSeconds: Long, val runningSince: Instant?)

/** Another conversation this one is tied to: one it depends on. */
data class LinkedIssue(val ref: IssueRef, val title: String, val state: IssueState)

/** Why an issue is closed, where the forge keeps that. */
enum class CloseReason { COMPLETED, NOT_PLANNED, DUPLICATE }

/** [id] is what the forge takes to set it on an issue: its number on GitHub, its id on Forgejo. */
data class Milestone(val id: Long, val title: String)

/** Something done to a conversation rather than said in it. */
enum class ConversationEvent {
    LOCKED, UNLOCKED, PINNED, UNPINNED, ASSIGNED, UNASSIGNED, MILESTONED, DEMILESTONED, TRANSFERRED,
    DEADLINE_SET, DEADLINE_REMOVED, TRACKING_STARTED, TRACKING_STOPPED, TIME_ADDED, DEPENDENCY_ADDED, DEPENDENCY_REMOVED,

    /** GitHub moved the issue to Discussions: the conversation goes on there. */
    CONVERTED_TO_DISCUSSION,
}

enum class ReviewState { APPROVED, CHANGES_REQUESTED, COMMENTED, DISMISSED }

enum class StateChange { CLOSED, REOPENED, MERGED }

/** One entry of an issue or PR conversation; noise (subscriptions, mentions...) is left out. */
sealed interface TimelineItem {
    val createdAt: Instant?

    data class Comment(
        val id: Long,
        val author: ForgeUser?,
        val body: String,
        override val createdAt: Instant,
        val reactions: Map<Reaction, Int>,
    ) : TimelineItem

    data class Review(
        val id: Long,
        val author: ForgeUser?,
        val state: ReviewState,
        val body: String?,
        override val createdAt: Instant?,
    ) : TimelineItem

    data class StateChanged(
        val change: StateChange,
        val actor: ForgeUser?,
        val stateReason: String?,
        override val createdAt: Instant,
    ) : TimelineItem

    data class Labeled(val added: Boolean, val label: Label, val actor: ForgeUser?, override val createdAt: Instant) : TimelineItem

    data class Renamed(val from: String, val to: String, val actor: ForgeUser?, override val createdAt: Instant) : TimelineItem

    data class CrossReferenced(
        val source: IssueRef,
        val sourceTitle: String,
        val sourceIsPullRequest: Boolean,
        val actor: ForgeUser?,
        override val createdAt: Instant,
    ) : TimelineItem

    /**
     * [subject] is who was assigned or unassigned, the milestone's title, the day it is due (ISO), or the conversation
     * depended on ("#12 Its title"); null for the other events.
     */
    data class Event(val event: ConversationEvent, val actor: ForgeUser?, val subject: String?, override val createdAt: Instant) : TimelineItem

    data class Committed(val sha: String, val message: String, val authorName: String?, override val createdAt: Instant?) : TimelineItem
}

data class TimelinePage(val items: List<TimelineItem>, val nextPage: Int?)

data class UserProfile(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val bio: String?,
    val company: String?,
    val location: String?,
    val website: String?,
    val followers: Int,
    val following: Int,
    val publicRepos: Int,
    val isOrganization: Boolean,
    val createdAt: Instant?,
)

data class RepoSummary(
    val id: RepoId,
    val description: String?,
    val language: String?,
    val stars: Int,
    val forks: Int,
    val isFork: Boolean,
    val updatedAt: Instant?,
    /** The owner's (user or organisation) avatar, when the forge sends it. */
    val ownerAvatarUrl: String? = null,
)
