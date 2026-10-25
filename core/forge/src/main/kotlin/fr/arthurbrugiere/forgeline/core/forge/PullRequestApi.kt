package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import fr.arthurbrugiere.forgeline.core.model.replaceLines

/**
 * What stands behind a pull request's numbers (its files, commits and checks), what can be done to one (review it,
 * merge it, open one), and a repository's history, which is read the same way. Writes need a token.
 */
interface PullRequestApi {
    /** The files [ref] changes, with their changes. [page] starts at 1. */
    suspend fun files(token: String?, ref: IssueRef, page: Int = 1): ForgeResult<ChangedFiles>

    /** The commits of [ref], oldest first. */
    suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>>

    /** What ran on [ref]'s latest commit; empty when nothing did. */
    suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>>

    /** What the check [checkId] of [repo] reported ([Check.checkId]): only GitHub keeps such reports. */
    suspend fun checkReport(token: String?, repo: RepoId, checkId: Long): ForgeResult<CheckReport> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether [ref] can be merged, by the signed-in user, and how. */
    suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo>

    /**
     * Merges [ref] by [method], deleting the branch that holds the change when [deleteBranch] says so (and
     * [MergeInfo.deleteBranch] made it a choice). With [whenChecksPass] the forge is told to merge it once its checks
     * pass instead ([MergeInfo.canMergeWhenChecksPass]).
     */
    suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean = false, whenChecksPass: Boolean = false): ForgeResult<Unit>

    /**
     * Brings [ref]'s branch up to date with the one it goes into ([MergeInfo.behind]): the forge merges that in, or
     * rebases onto it where that is its only way (GitLab). The forge may still be at it when this answers.
     */
    suspend fun updateBranch(token: String, ref: IssueRef): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Takes back [merge]'s "once its checks pass": the pull request stays open, unmerged. */
    suspend fun cancelMergeWhenChecksPass(token: String, ref: IssueRef): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** The verdicts a review can give here: GitLab's API can't ask for changes. */
    val verdicts: Set<ReviewVerdict> get() = ReviewVerdict.entries.toSet()

    /** Reviews [ref]: a verdict, what it says ([body], Markdown, possibly empty) and remarks on lines of the change. */
    suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit>

    /** The remarks on lines of [ref]'s change, each with its replies, oldest first. */
    suspend fun threads(token: String?, ref: IssueRef): ForgeResult<List<ReviewThread>> = ForgeResult.Success(emptyList())

    /** Whether the reader's own remarks on lines can be rewritten and taken back here. */
    val editsThreadComments: Boolean get() = false

    /** Rewrites [comment], one of [thread]'s, as [body] (Markdown): the reader's own, which only its author may. */
    suspend fun editThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, body: String): ForgeResult<Unit> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether they can also be taken back: Forgejo answers yes to deleting one and keeps it (Codeberg, 2026-10-25). */
    val deletesThreadComments: Boolean get() = editsThreadComments

    /** Takes back [comment], one of [thread]'s; the thread goes with its last remark. */
    suspend fun deleteThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment): ForgeResult<Unit> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether remarks on lines take reactions here. */
    val reactsToThreadComments: Boolean get() = false

    /**
     * Gives [reaction] to [comment], one of [thread]'s, as [login]; takes it back when they had given it. Answers the
     * reactions it then has, everyone's.
     */
    suspend fun toggleThreadReaction(token: String, login: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, reaction: Reaction): ForgeResult<Map<Reaction, Int>> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** Answers [thread] of [ref] with [body], Markdown. */
    suspend fun reply(token: String, ref: IssueRef, thread: ReviewThread, body: String): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether this forge's API marks threads resolved: Forgejo's doesn't. */
    val resolvesThreads: Boolean get() = false

    /** Marks [thread] of [ref] resolved, or not resolved again. */
    suspend fun setResolved(token: String, ref: IssueRef, thread: ReviewThread, resolved: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether [applySuggestion] can be asked here. */
    val appliesSuggestions: Boolean get() = false

    /**
     * Commits [suggestion] to [ref]'s branch, in the repository it lives in (a fork's, where its owner lets the
     * maintainers push), in place of the lines [thread] is on ([ReviewThread.suggestedOver]), saying [message]; or has
     * the forge apply it, where it keeps the suggestion ([ThreadComment.suggestions]). [ForgeError.Conflict] where
     * those lines read otherwise there now, or the forge no longer applies it.
     */
    suspend fun applySuggestion(token: String, ref: IssueRef, thread: ReviewThread, suggestion: List<String>, message: String): ForgeResult<Unit> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether this forge's API dismisses someone's review: GitLab's can't take back another's approval. */
    val dismissesReviews: Boolean get() = false

    /** Dismisses the review [reviewId] of [ref], saying why ([message]): its verdict no longer counts. */
    suspend fun dismissReview(token: String, ref: IssueRef, reviewId: Long, message: String): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Opens a pull request in [repo], and answers where it is. */
    suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef>

    /**
     * The templates [repo] offers for a pull request's description, as kept on [branch], its default one, where the
     * forge reads them from the repository; none where it has none. A template's title stays empty.
     */
    suspend fun pullRequestTemplates(token: String, repo: RepoId, branch: String): ForgeResult<List<IssueTemplate>> = ForgeResult.Success(emptyList())

    /** [template] with its body, for a forge that lists its templates without them. */
    suspend fun pullRequestTemplate(token: String, repo: RepoId, template: IssueTemplate): ForgeResult<IssueTemplate> = ForgeResult.Success(template)

    /**
     * [repo]'s history, newest first: from [ref] (a branch, a tag, a commit; the default branch when null), and
     * only the commits that touched [path] when one is given. [page] starts at 1.
     */
    suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int = 1): ForgeResult<CommitPage>

    /** One commit of [repo], with the files it changed. */
    suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails>

    /** Whether [blame] can be asked here: Forgejo's API has none. */
    val supportsBlame: Boolean get() = false

    /**
     * Which commit last changed each line of the file [path] as it is at [ref], top to bottom. GitHub only tells
     * signed-in users (Unauthorized without a token).
     */
    suspend fun blame(token: String?, repo: RepoId, ref: String, path: String): ForgeResult<List<BlameRange>> =
        ForgeResult.Failure(ForgeError.Unsupported)
}

/**
 * The file [text] with the lines [thread] is on replaced by [suggestion], for [PullRequestApi.applySuggestion]:
 * [ForgeError.Conflict] where the forge didn't give those lines, or they read otherwise now.
 */
fun suggestionApplied(text: String, thread: ReviewThread, suggestion: List<String>): ForgeResult<String> {
    val over = thread.suggestedOver
    val first = over.firstOrNull()?.newNumber ?: return ForgeResult.Failure(ForgeError.Conflict)
    return replaceLines(text, first, over.map { it.text }, suggestion)?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Conflict)
}
