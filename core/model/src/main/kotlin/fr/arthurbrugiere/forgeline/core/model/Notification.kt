package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

enum class NotificationReason {
    MENTION, TEAM_MENTION, REVIEW_REQUESTED, ASSIGN, AUTHOR, COMMENT, STATE_CHANGE,
    SUBSCRIBED, MANUAL, CI_ACTIVITY, SECURITY_ALERT, OTHER,
}

enum class SubjectType { ISSUE, PULL_REQUEST, RELEASE, DISCUSSION, CHECK_SUITE, COMMIT, OTHER }

/** Where an issue or pull request stands now; notifications don't say, so it's asked for separately. */
enum class SubjectState { OPEN, DRAFT, MERGED, CLOSED, NOT_PLANNED }

data class NotificationThread(
    val id: String,
    val repo: RepoId,
    val title: String,
    val type: SubjectType,
    /** Issue or PR number when the subject has one. */
    val number: Int?,
    val reason: NotificationReason,
    val unread: Boolean,
    val updatedAt: Instant,
    /** The repository owner's (user or organisation) avatar, when the forge sends it. */
    val ownerAvatarUrl: String? = null,
    /** Null until known, and for subjects that have no state (releases, commits...). */
    val state: SubjectState? = null,
) {
    /** The issue or pull request this thread is about, when it is about one. */
    val subject: IssueRef?
        get() = number?.takeIf { type == SubjectType.ISSUE || type == SubjectType.PULL_REQUEST }?.let { IssueRef(repo, it) }

    /** GitHub's "participating": you're directly involved, not just watching. */
    val isParticipating: Boolean
        get() = reason in setOf(
            NotificationReason.MENTION, NotificationReason.TEAM_MENTION, NotificationReason.REVIEW_REQUESTED,
            NotificationReason.ASSIGN, NotificationReason.AUTHOR, NotificationReason.COMMENT, NotificationReason.STATE_CHANGE,
        )

    /** Waiting on you: asked for your review, named you, assigned you, or flagged a security issue. */
    val needsYou: Boolean
        get() = reason in setOf(
            NotificationReason.REVIEW_REQUESTED, NotificationReason.MENTION, NotificationReason.TEAM_MENTION,
            NotificationReason.ASSIGN, NotificationReason.SECURITY_ALERT,
        )
}
