package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.DiscussionPage
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.WikiPage
import fr.arthurbrugiere.forgeline.core.model.WikiPageSummary
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun

/**
 * Read access to a repository. Every call works anonymously ([token] null) for public repos,
 * at the forge's lower anonymous rate limit.
 */
interface RepoApi {
    suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails>

    /**
     * [repo], with what [token]'s account may do there when the same answer says it: opening a repository then asks the
     * forge for it once, not again for [IssueApi.access]. Null rights signed out, or where the answer doesn't tell.
     */
    suspend fun repoAndRights(token: String?, id: RepoId): ForgeResult<Pair<RepoDetails, RepoRights?>> = repo(token, id).map { it to null }

    /** Null when the repo has no README. [ref] null reads the default branch. */
    suspend fun readme(token: String?, id: RepoId, ref: String? = null): ForgeResult<Readme?>

    /** Every branch and tag. */
    suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs>

    /** Entries of the directory at [path] ("" for the root), directories first then by name. */
    suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>>

    suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String>

    /** The issues [query] asks for, pull requests excluded: most recent first, or best match first when it has words. */
    suspend fun issues(token: String?, id: RepoId, query: IssueQuery = IssueQuery()): ForgeResult<List<IssueSummary>>

    suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery = IssueQuery()): ForgeResult<List<IssueSummary>>

    /** The issues pinned to the top of the repository's list; none where the forge can't say. */
    suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> = ForgeResult.Success(emptyList())

    suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>>

    /** The languages the code is written in, largest share first. */
    suspend fun languages(token: String?, id: RepoId): ForgeResult<List<RepoLanguage>> = ForgeResult.Success(emptyList())

    /** The release published under [tag]; 404 when there is none, or it is still a draft. */
    suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release>

    suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>>

    /** A page of the repository's discussions, most recently active first; [after] is what the page before gave as next. */
    suspend fun discussions(token: String?, id: RepoId, after: String? = null): ForgeResult<DiscussionPage> = ForgeResult.Failure(ForgeError.Unsupported)

    /** The discussion [number] with its first comments and their replies; 404 when there is none. */
    suspend fun discussion(token: String?, id: RepoId, number: Int): ForgeResult<Discussion> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Comments on [discussion], or replies under its comment [replyTo]: a comment, never a reply, which has none of its own. */
    suspend fun commentOnDiscussion(token: String, discussion: Discussion, body: String, replyTo: String? = null): ForgeResult<Unit> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** The key of a wiki's first page, which opens it. */
    val wikiHome: String get() = "Home"

    /**
     * The pages of [id]'s wiki, by title, without the parts every page shows (a sidebar, a footer); Unsupported where
     * its forge can't list them (GitHub, whose wikis have no API).
     */
    suspend fun wikiPages(token: String?, id: RepoId): ForgeResult<List<WikiPageSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    /** The wiki page [key] of [id]; 404 where there is none, or no wiki. */
    suspend fun wikiPage(token: String?, id: RepoId, key: String): ForgeResult<WikiPage> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Picks the comment [commentId] as the answer to its discussion, or takes that back. */
    suspend fun setDiscussionAnswer(token: String, commentId: String, answer: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Where raw file bytes live, ending with `/`: README images resolve against it. */
    fun rawBaseUrl(id: RepoId, ref: String): String

    /** Where file pages live, ending with `/`: README links resolve against it. */
    fun blobBaseUrl(id: RepoId, ref: String): String
}
