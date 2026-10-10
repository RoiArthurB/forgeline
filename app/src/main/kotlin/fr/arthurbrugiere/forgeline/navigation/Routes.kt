package fr.arthurbrugiere.forgeline.navigation

import androidx.navigation3.runtime.NavKey
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.serialization.Serializable

@Serializable
data object InboxRoute : NavKey

@Serializable
data object FeedRoute : NavKey

@Serializable
data object TrendingRoute : NavKey

@Serializable
data object YouRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

/** One page of Settings. */
@Serializable
data class SettingsSectionRoute(val section: fr.arthurbrugiere.forgeline.settings.SettingsSection) : NavKey

@Serializable
data object CreditsRoute : NavKey

@Serializable
data object SignInRoute : NavKey

// Every route into forge content names its forge by host: the same owner/name can exist on two forges.

@Serializable
data class RepoRoute(val host: String, val owner: String, val name: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class FileRoute(val host: String, val owner: String, val name: String, val path: String, val ref: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

/** One discussion of a repository, by its number. */
@Serializable
data class DiscussionRoute(val host: String, val owner: String, val name: String, val number: Int) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

/** A picture of a README, a comment or a release's notes, shown on its own. */
@Serializable
data class PictureRoute(val url: String, val description: String? = null) : NavKey {
    /** The picture's name where it lives, without what its address asks for after it. */
    val fileName: String get() = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
}

/**
 * A conversation. Opened from a notification that is [unread], it starts at what is new since [lastReadAtMillis], or
 * at its latest entry when the forge doesn't say when it was last read.
 */
@Serializable
data class IssueRoute(
    val host: String,
    val owner: String,
    val name: String,
    val number: Int,
    /** Whether it is a pull request, where that is known: GitLab numbers merge requests apart from issues. */
    val isPullRequest: Boolean? = null,
    val unread: Boolean = false,
    val lastReadAtMillis: Long? = null,
) : NavKey {
    val issue: IssueRef get() = IssueRef(RepoId(owner, name, ForgeInstance.of(host)), number, isPullRequest)
}

/** The form that opens an issue in a repository, or changes the title and text of conversation [edit] there. */
@Serializable
data class NewIssueRoute(
    val host: String,
    val owner: String,
    val name: String,
    val edit: Int? = null,
    /** Whether [edit] is a pull request, where that tells it from an issue of the same number (GitLab). */
    val editIsPullRequest: Boolean? = null,
) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))

    val editing: IssueRef? get() = edit?.let { IssueRef(repo, it, editIsPullRequest) }
}

/** One release of a repository, by its tag. */
@Serializable
data class ReleaseRoute(val host: String, val owner: String, val name: String, val tag: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

/**
 * What a change holds, file by file: a pull request's (by its [number]) or one commit's (by its [sha]).
 */
@Serializable
data class ChangesRoute(val host: String, val owner: String, val name: String, val number: Int? = null, val sha: String? = null) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))

    val target: fr.arthurbrugiere.forgeline.pull.ChangesTarget
        get() = if (number != null) {
            fr.arthurbrugiere.forgeline.pull.ChangesTarget.Pull(IssueRef(repo, number, isPullRequest = true))
        } else {
            fr.arthurbrugiere.forgeline.pull.ChangesTarget.OfCommit(repo, sha.orEmpty())
        }
}

/**
 * A list of commits: a pull request's (by its [number]), or a repository's history from [ref] (its default branch
 * when null), of the one file at [path] when there is one.
 */
@Serializable
data class CommitsRoute(val host: String, val owner: String, val name: String, val number: Int? = null, val ref: String? = null, val path: String? = null) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))

    val target: fr.arthurbrugiere.forgeline.pull.CommitsTarget
        get() = if (number != null) {
            fr.arthurbrugiere.forgeline.pull.CommitsTarget.Pull(IssueRef(repo, number, isPullRequest = true))
        } else {
            fr.arthurbrugiere.forgeline.pull.CommitsTarget.History(repo, ref, path)
        }
}

@Serializable
data class RunRoute(val host: String, val owner: String, val name: String, val runId: Long) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class JobLogRoute(val host: String, val owner: String, val name: String, val runId: Long, val jobId: Long, val jobName: String) : NavKey {
    val repo: RepoId get() = RepoId(owner, name, ForgeInstance.of(host))
}

@Serializable
data class UserRoute(val host: String, val login: String) : NavKey {
    val forge: ForgeInstance get() = ForgeInstance.of(host)
}

@Serializable
data object SearchRoute : NavKey

fun RepoId.route() = RepoRoute(forge.host, owner, name)

fun RepoId.releaseRoute(tag: String) = ReleaseRoute(forge.host, owner, name, tag)

fun RepoId.newIssueRoute() = NewIssueRoute(forge.host, owner, name)

fun IssueRef.changesRoute() = ChangesRoute(repo.forge.host, repo.owner, repo.name, number = number)

fun IssueRef.commitsRoute() = CommitsRoute(repo.forge.host, repo.owner, repo.name, number = number)

fun RepoId.commitRoute(sha: String) = ChangesRoute(forge.host, owner, name, sha = sha)

fun RepoId.historyRoute(ref: String?, path: String? = null) = CommitsRoute(forge.host, owner, name, ref = ref, path = path)

fun IssueRef.route() = IssueRoute(repo.forge.host, repo.owner, repo.name, number, isPullRequest)

/** The conversation of a thread, for a thread about one; an unread thread opens at what is new. */
fun NotificationThread.route(): IssueRoute? = subject?.route()?.copy(unread = unread, lastReadAtMillis = lastReadAt?.toEpochMilli())

fun ForgeInstance.userRoute(login: String) = UserRoute(host, login)
