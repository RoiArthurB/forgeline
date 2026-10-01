package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

/** Kinds of Feed activity people can switch on or off; the defaults keep the Feed social, not noisy. */
enum class FeedKind(val shownByDefault: Boolean) {
    STARS(true),
    FORKS(true),
    NEW_REPOS(true),
    RELEASES(true),

    /** Release candidates, betas and other pre-releases: shown like GitHub's own feed does, and easy to switch off. */
    PRERELEASES(true),

    /** A repository's announcements (GitHub's Announcements discussions); Forgejo has none. */
    ANNOUNCEMENTS(true),
    ISSUES_OPENED(true),
    ISSUES_CLOSED(true),
    PRS_OPENED(true),
    PRS_CLOSED(true),
    COMMENTS(false),
    REVIEWS(false),
    PUSHES(false),
    BRANCHES(false),
    MEMBERS(false),
    ;

    companion object {
        val defaults: Set<FeedKind> = entries.filter { it.shownByDefault }.toSet()
    }
}

enum class IssueAction { OPENED, CLOSED, REOPENED }

enum class PullRequestAction { OPENED, CLOSED, MERGED, REOPENED }

sealed interface FeedAction {
    val kind: FeedKind

    data object Starred : FeedAction {
        override val kind = FeedKind.STARS
    }

    data class Forked(val fork: RepoId) : FeedAction {
        override val kind = FeedKind.FORKS
    }

    data class CreatedRepo(val description: String?) : FeedAction {
        override val kind = FeedKind.NEW_REPOS
    }

    data object MadePublic : FeedAction {
        override val kind = FeedKind.NEW_REPOS
    }

    data class Released(val tag: String, val name: String?, val prerelease: Boolean) : FeedAction {
        override val kind = if (prerelease) FeedKind.PRERELEASES else FeedKind.RELEASES
    }

    /** A post in a repository's Announcements, by its discussion [number]. */
    data class Announced(val number: Int, val title: String) : FeedAction {
        override val kind = FeedKind.ANNOUNCEMENTS
    }

    data class Issue(val action: IssueAction, val number: Int, val title: String) : FeedAction {
        override val kind = if (action == IssueAction.CLOSED) FeedKind.ISSUES_CLOSED else FeedKind.ISSUES_OPENED
    }

    /** GitHub's events carry no pull request title, only its number ([title] null); Forgejo's carry it. */
    data class PullRequest(val action: PullRequestAction, val number: Int, val title: String? = null) : FeedAction {
        override val kind = when (action) {
            PullRequestAction.CLOSED, PullRequestAction.MERGED -> FeedKind.PRS_CLOSED
            else -> FeedKind.PRS_OPENED
        }
    }

    data class Commented(val number: Int, val title: String?, val isPullRequest: Boolean) : FeedAction {
        override val kind = FeedKind.COMMENTS
    }

    data class Reviewed(val number: Int, val state: ReviewState) : FeedAction {
        override val kind = FeedKind.REVIEWS
    }

    data class Pushed(val branch: String) : FeedAction {
        override val kind = FeedKind.PUSHES
    }

    data class Branch(val name: String, val isTag: Boolean, val deleted: Boolean) : FeedAction {
        override val kind = FeedKind.BRANCHES
    }

    data class AddedMember(val login: String) : FeedAction {
        override val kind = FeedKind.MEMBERS
    }
}

data class FeedEvent(
    val id: String,
    val actor: ForgeUser,
    val repo: RepoId,
    val action: FeedAction,
    val createdAt: Instant,
)

/** What a Feed row shows about a repository its event names; events don't carry it, so it's fetched and cached. */
data class RepoPreview(val description: String?, val language: String?, val stars: Int)

/** Previews known so far for the Feed: repositories, and pull request titles (pull request events carry only the number). */
data class FeedPreviews(
    val repos: Map<RepoId, RepoPreview> = emptyMap(),
    val pullTitles: Map<IssueRef, String> = emptyMap(),
)
