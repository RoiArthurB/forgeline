package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict

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

    /** Whether [ref] can be merged, by the signed-in user, and how. */
    suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo>

    suspend fun merge(token: String, ref: IssueRef, method: MergeMethod): ForgeResult<Unit>

    /** The verdicts a review can give here: GitLab's API can't ask for changes. */
    val verdicts: Set<ReviewVerdict> get() = ReviewVerdict.entries.toSet()

    /** Reviews [ref]: a verdict, what it says ([body], Markdown, possibly empty) and remarks on lines of the change. */
    suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit>

    /** Opens a pull request in [repo], and answers where it is. */
    suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef>

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
