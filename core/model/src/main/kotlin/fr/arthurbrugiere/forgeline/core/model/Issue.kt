package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

enum class Reaction { THUMBS_UP, THUMBS_DOWN, LAUGH, HOORAY, CONFUSED, HEART, ROCKET, EYES }

/**
 * A conversation in a repository. [isPullRequest] says which kind, where that is known.
 *
 * GitHub and Forgejo number issues and pull requests together: there the number alone tells a conversation, and two
 * references to it are the same whether or not they say its kind. GitLab numbers merge requests apart from issues
 * (#7 and !7 are two conversations): there the kind is part of which one it is, and a reference that doesn't say is
 * an issue, as it is for GitLab's clients.
 */
data class IssueRef(val repo: RepoId, val number: Int, val isPullRequest: Boolean? = null) {
    /** The kind, where two conversations can share a number; nothing where they can't. */
    private val kind: Boolean? get() = if (repo.forge.type.numbersMergeRequestsApart) isPullRequest == true else null

    override fun equals(other: Any?): Boolean =
        this === other || (other is IssueRef && repo == other.repo && number == other.number && kind == other.kind)

    override fun hashCode(): Int = 31 * (31 * repo.hashCode() + number) + kind.hashCode()
}

data class PullRequestInfo(
    val isDraft: Boolean,
    val isMerged: Boolean,
    val baseRef: String,
    val headRef: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val commits: Int,
    /** Who is asked to review it and hasn't yet. */
    val reviewers: List<ForgeUser> = emptyList(),
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
    /** What kind of issue it is (a bug, a task...), on forges whose owners name kinds. */
    val type: IssueType? = null,
)

/** A kind of issue its repository's owner named: [color] as GitHub names it ("RED", "blue"). */
data class IssueType(val name: String, val color: String? = null, val description: String? = null)

/**
 * What the signed-in user may do in a repository beyond reading it, least to most. Triage manages its conversations
 * (labels, assignees, closing), write also changes the repository, admin also deletes from it.
 */
enum class RepoAccess { NONE, TRIAGE, WRITE, ADMIN }

/** Who a forge's API lets put a picture in a comment. */
enum class AttachmentRule {
    /** The API takes no file (GitHub's own apps use a way of theirs). */
    NOBODY,

    /** Whoever opened the conversation, and whoever can write to its repository: the file is kept with the conversation. */
    AUTHOR_OR_WRITER,

    /** Whoever can comment: the file is kept with the repository. */
    ANYONE,
}

/** What can be done to a conversation beyond commenting on it and closing it; a forge's API offers some of them. */
enum class ConversationAction {
    LABELS, ASSIGNEES, MILESTONE, CLOSE_REASON, LOCK, PIN, TRANSFER, DELETE, DUE_DATE, TIME_TRACKING, DEPENDENCIES,

    /** What kind of issue it is, among those its repository's owner named. */
    TYPE,

    /** Asking for a pull request's review, and taking the ask back: only pull requests have it. */
    REVIEWERS,

    /** Marking a pull request a draft, or ready for review: only pull requests have it. */
    DRAFT,
}

/**
 * What the signed-in user may do in a repository, and what its settings or their role take away there
 * ([switchedOff]): a Forgejo repository can switch time tracking and dependencies off, or keep tracking to contributors.
 */
data class RepoRights(val access: RepoAccess, val switchedOff: Set<ConversationAction> = emptySet())

/** The time spent on a conversation by everyone, and since when the signed-in user's own timer runs on it, if it does. */
data class TimeTracking(val totalSeconds: Long, val runningSince: Instant?)

/** Another conversation this one is tied to: one it depends on. */
data class LinkedIssue(val ref: IssueRef, val title: String, val state: IssueState)

/** Where an issue stands among others: the one it is part of, if any, and those that are part of it. */
data class SubIssues(val parent: LinkedIssue?, val children: List<LinkedIssue>) {
    /** How many of its sub-issues are done. */
    val done: Int get() = children.count { it.state != IssueState.OPEN }
}

/**
 * What a repository offers to start an issue from: [name] and [about] say what it is for, [title] and [body] go into the
 * new issue. [body] is null until asked for, where the forge lists its templates without them (GitLab); [key] is what
 * it is asked for by.
 */
data class IssueTemplate(
    val key: String,
    val name: String,
    val about: String? = null,
    val title: String = "",
    val body: String? = null,
    /** The labels an issue opened from it wears, by name. */
    val labels: List<String> = emptyList(),
)

/**
 * What a repository says beside its templates about opening an issue: whether one may start blank ([blankIssues]),
 * and where to go instead for what its issues aren't for ([links]: a forum, a chat room, its documentation).
 */
data class IssueChooser(val blankIssues: Boolean = true, val links: List<ContactLink> = emptyList())

/** A place a repository sends people to instead of its issues: [url] may be a web page or a `mailto:` address. */
data class ContactLink(val name: String, val url: String, val about: String? = null)

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

    /** The branch a pull request came from was deleted, or made again where it was. */
    BRANCH_DELETED,
    BRANCH_RESTORED,

    /** Someone was asked to review a pull request ([TimelineItem.Event.subject]: who, a person or a team), or no longer. */
    REVIEW_REQUESTED,
    REVIEW_REQUEST_REMOVED,

    /** A review's verdict was set aside; the subject is what the one who did it said, where they said something. */
    REVIEW_DISMISSED,

    /** Commits were pushed to the pull request (the subject: how many, where the forge says), or its branch rewritten. */
    PUSHED,
    FORCE_PUSHED,

    /** A pull request was marked ready for review, or made a draft again. */
    READY_FOR_REVIEW,
    CONVERTED_TO_DRAFT,

    /** It was said to be a duplicate of another conversation (the subject, and the target, where the forge names it), or no longer. */
    MARKED_DUPLICATE,
    UNMARKED_DUPLICATE,

    /** The subject (and the target) was said to be a duplicate of this one, or no longer: the original's side of it. */
    DUPLICATE_ADDED,
    DUPLICATE_REMOVED,

    /** It was put on a project board (the subject: the board's name, where the forge tells it), taken off one, or moved on one to the subject's column. */
    ADDED_TO_PROJECT,
    REMOVED_FROM_PROJECT,
    PROJECT_STATUS_CHANGED,

    /** Its status was set, on forges that keep one beside open and closed (GitLab's "To do", "In progress"...). */
    STATUS_CHANGED,

    /** A comment was deleted: what it said is gone. */
    COMMENT_DELETED,

    /** A comment was pinned to the top of the conversation, or no longer. */
    COMMENT_PINNED,
    COMMENT_UNPINNED,

    /** The pull request's changes were deployed, to the subject's environment where it is named. */
    DEPLOYED,

    /** The pull request is set to merge itself once its checks pass, or no longer. */
    AUTO_MERGE_ENABLED,
    AUTO_MERGE_DISABLED,

    /** The pull request joined the queue of those merged one after the other, or left it. */
    ADDED_TO_MERGE_QUEUE,
    REMOVED_FROM_MERGE_QUEUE,

    /** The pull request now goes into the subject's branch. */
    BASE_CHANGED,

    /** A pull request that closes this issue once merged was linked to it (the subject, and the target), or no longer. */
    LINKED,
    UNLINKED,

    /** Someone was blocked from the repository's owner for what they said here. */
    USER_BLOCKED,

    /** Its type was set to the subject's, or the subject's taken away. */
    TYPE_SET,
    TYPE_REMOVED,

    /** The subject became part of this issue, or no longer; this one became part of the subject, or no longer. */
    SUB_ISSUE_ADDED,
    SUB_ISSUE_REMOVED,
    PARENT_ADDED,
    PARENT_REMOVED,

    /** This one now holds the subject up (it can't be closed before this is), or no longer: the other side of a dependency. */
    BLOCKING_ADDED,
    BLOCKING_REMOVED,

    /** Time recorded on it was taken back, or a running timer was stopped without recording. */
    TIME_REMOVED,
    TRACKING_CANCELLED,

    /** How long it is thought to take was set to the subject (as the forge writes it), or taken away. */
    ESTIMATE_SET,
    ESTIMATE_REMOVED,

    /** It was tied to the subject as related, or no longer (GitLab). */
    RELATED_ADDED,
    RELATED_REMOVED,

    /** Its description was changed. */
    DESCRIPTION_CHANGED,

    /** It was made visible only to the project's members, or to everyone again (GitLab). */
    CONFIDENTIAL,
    NOT_CONFIDENTIAL,

    /** Its weight was set to the subject, or taken away (GitLab). */
    WEIGHT_SET,
    WEIGHT_REMOVED,

    /** It was put in the subject's iteration, or taken out of it (GitLab). */
    ITERATION_SET,
    ITERATION_REMOVED,

    /** It was moved to the subject, another repository, and goes on there; or copied there. */
    MOVED_AWAY,
    CLONED,

    /** A merge request's approval was taken back (GitLab). */
    APPROVAL_REVOKED,

    /** Every thread of the pull request was resolved. */
    THREADS_RESOLVED,
}

enum class ReviewState { APPROVED, CHANGES_REQUESTED, COMMENTED, DISMISSED }

enum class StateChange { CLOSED, REOPENED, MERGED }

/** One entry of an issue or PR conversation; noise (subscriptions, mentions...) is left out. */
sealed interface TimelineItem {
    val createdAt: Instant?

    /**
     * [hidden] says why it was hidden, when someone who may hid it ([canHide] tells whether the reader may), by its
     * [nodeId] on forges that hide comments.
     */
    data class Comment(
        val id: Long,
        val author: ForgeUser?,
        val body: String,
        override val createdAt: Instant,
        val reactions: Map<Reaction, Int>,
        val nodeId: String? = null,
        val hidden: HideReason? = null,
        val canHide: Boolean = false,
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
        /** The commit that closed the conversation, when one did ("Fixes #12" pushed to the default branch). */
        val commit: ClosingCommit? = null,
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
     * depended on ("#12 Its title"), the branch deleted or restored where the forge names it; null for the other events.
     * [target] is the conversation the event names, where it names one (a sub-issue, a duplicate, a dependency), to open.
     */
    data class Event(
        val event: ConversationEvent,
        val actor: ForgeUser?,
        val subject: String?,
        override val createdAt: Instant,
        val target: IssueRef? = null,
        /** The project board it happened on, where the forge names it. */
        val place: String? = null,
    ) : TimelineItem

    /** [commit] mentioned this conversation in its message, without closing it. */
    data class CommitReferenced(val commit: ClosingCommit, val actor: ForgeUser?, override val createdAt: Instant) : TimelineItem

    data class Committed(val sha: String, val message: String, val authorName: String?, override val createdAt: Instant?) : TimelineItem
}

/**
 * A conversation's place on a project board ([title], [url]): [status] is its column, among [statuses], none when it
 * has none. [canChange] says whether the reader may move it. [id], [projectId] and [statusFieldId] are what the forge
 * takes to move it.
 */
data class ProjectItem(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String?,
    val statusFieldId: String?,
    val statuses: List<ProjectStatus>,
    val canChange: Boolean,
    val url: String? = null,
)

/** A column of a project board, by the [id] the forge takes. */
data class ProjectStatus(val id: String, val name: String)

/** A reply the reader saved on their forge, to say again in a comment. */
data class SavedReply(val title: String, val body: String)

/** Why a comment was hidden, as GitHub names it. */
enum class HideReason { OFF_TOPIC, OUTDATED, RESOLVED, DUPLICATE, SPAM, ABUSE }

/** A commit that closed a conversation: its [message] is null when the forge couldn't give it, and [webUrl] its page. */
data class ClosingCommit(val sha: String, val message: String?, val webUrl: String)

/**
 * One page of a conversation. [lastPage] is the last one there is, when the forge says: the pages up to it can then be
 * asked all at once instead of one after the other.
 */
data class TimelinePage(val items: List<TimelineItem>, val nextPage: Int?, val lastPage: Int? = null)

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
    /** Where else to find them: email, social accounts, chat. The [website] is apart. */
    val links: List<ProfileLink> = emptyList(),
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
    /** Seen only by its owner and the people it's shared with. */
    val isPrivate: Boolean = false,
    /** When code was last pushed to it, where the forge tells it apart from [updatedAt] (which a star also moves). */
    val pushedAt: Instant? = null,
    /** Whether an organisation (a GitLab group) owns it; null where the forge doesn't say. */
    val ownerIsOrganization: Boolean? = null,
)
