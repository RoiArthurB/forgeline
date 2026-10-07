package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.AttachmentRule
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import java.time.LocalDate
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.model.UserProfile

/** Issues and pull requests share numbering and conversation on every supported forge. */
interface IssueApi {
    suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails>

    /** Just the title, for rows that only name a conversation: one request where [issue] may need several. */
    suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = when (val result = issue(token, ref)) {
        is ForgeResult.Failure -> result
        is ForgeResult.Success -> ForgeResult.Success(result.value.title)
    }

    /** Oldest first. [page] starts at 1. */
    suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage>

    /** Adds a comment to the conversation, [body] being Markdown, and answers it as the forge kept it. */
    suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment>

    /** Replaces the text of the comment [commentId] of [ref] with [body], Markdown. */
    suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = unsupported

    /** Deletes the comment [commentId] of [ref], for good. */
    suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = unsupported

    /** Changes the title and the description of [ref], [body] being Markdown and possibly empty. */
    suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = unsupported

    /**
     * Gives [reaction] to the comment [commentId] of [ref], or to the conversation's own text when it is null, as
     * [login]; takes it back when they had already given it. Answers the reactions it then has, everyone's.
     */
    suspend fun toggleReaction(token: String, login: String, ref: IssueRef, commentId: Long?, reaction: Reaction): ForgeResult<Map<Reaction, Int>> = unsupported

    /** Who may put a picture in a comment through this forge's API. */
    val attachments: AttachmentRule get() = AttachmentRule.NOBODY

    /**
     * Uploads the file [bytes], named [name] and of type [mimeType], for a comment of [ref], and answers the Markdown
     * that shows it there.
     */
    suspend fun attach(token: String, ref: IssueRef, name: String, mimeType: String, bytes: ByteArray): ForgeResult<String> = unsupported

    /** Opens an issue in [repo], [body] being Markdown and possibly empty, and answers it as the forge kept it. */
    suspend fun create(token: String, repo: RepoId, title: String, body: String): ForgeResult<IssueDetails>

    /**
     * Closes the issue or pull request [ref], or reopens it. A merged pull request can't be reopened. [reason] says
     * why it is closed, on forges that keep that ([ConversationAction.CLOSE_REASON]).
     */
    suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason? = null): ForgeResult<Unit>

    /** What the signed-in user may do in [repo] beyond reading it, and what the repository switches off. */
    suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights>

    /** What this forge's API can do to a conversation; the calls below answer Unsupported for the rest. */
    val actions: Set<ConversationAction> get() = emptySet()

    /** Those of [actions] this forge only has for issues: a pull request isn't offered them. */
    val issueOnly: Set<ConversationAction> get() = emptySet()

    /** The labels [repo]'s conversations can wear. */
    suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = unsupported

    /** Makes [names] the labels of [ref], taking away any other. */
    suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = unsupported

    /** Who a conversation of [repo] can be assigned to. */
    suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = unsupported

    /** Makes [logins] the assignees of [ref], taking away anyone else. */
    suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> = unsupported

    /** The open milestones of [repo]. */
    suspend fun milestones(token: String, repo: RepoId): ForgeResult<List<Milestone>> = unsupported

    /** Files [ref] under [milestone], or under none. */
    suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?): ForgeResult<Unit> = unsupported

    /** A locked conversation only takes comments from the repository's collaborators. */
    suspend fun setLocked(token: String, ref: IssueRef, locked: Boolean): ForgeResult<Unit> = unsupported

    suspend fun isPinned(token: String, ref: IssueRef): ForgeResult<Boolean> = unsupported

    /** A pinned issue stays at the top of its repository's issues. */
    suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean): ForgeResult<Unit> = unsupported

    /** Moves the issue [ref] to the repository [to], and answers where it now is. */
    suspend fun transfer(token: String, ref: IssueRef, to: RepoId): ForgeResult<IssueRef> = unsupported

    /** Deletes the issue [ref] and its conversation, for good. */
    suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = unsupported

    /** Says the day [ref] is due, or that it has none. */
    suspend fun setDueDate(token: String, ref: IssueRef, date: LocalDate?): ForgeResult<Unit> = unsupported

    suspend fun timeTracking(token: String, ref: IssueRef): ForgeResult<TimeTracking> = unsupported

    /** Starts the signed-in user's timer on [ref], or stops it: stopping records the time it ran. */
    suspend fun setTimerRunning(token: String, ref: IssueRef, running: Boolean): ForgeResult<Unit> = unsupported

    /** Records [seconds] spent on [ref] by the signed-in user. */
    suspend fun addTime(token: String, ref: IssueRef, seconds: Long): ForgeResult<Unit> = unsupported

    /** The conversations [ref] depends on: it can't be closed before they are. */
    suspend fun dependencies(token: String, ref: IssueRef): ForgeResult<List<LinkedIssue>> = unsupported

    suspend fun addDependency(token: String, ref: IssueRef, on: IssueRef): ForgeResult<Unit> = unsupported

    suspend fun removeDependency(token: String, ref: IssueRef, on: IssueRef): ForgeResult<Unit> = unsupported
}

private val unsupported = ForgeResult.Failure(ForgeError.Unsupported)

interface UserApi {
    suspend fun user(token: String?, login: String): ForgeResult<UserProfile>

    /** Most recently updated first. */
    suspend fun repos(token: String?, login: String): ForgeResult<List<RepoSummary>>

    suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>>

    /** Whether the signed-in user follows [login]. */
    suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean>

    suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit>
}
