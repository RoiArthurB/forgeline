package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun

/**
 * Read access to a repository. Every call works anonymously ([token] null) for public repos,
 * at the forge's lower anonymous rate limit.
 */
interface RepoApi {
    suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails>

    /** Null when the repo has no README. [ref] null reads the default branch. */
    suspend fun readme(token: String?, id: RepoId, ref: String? = null): ForgeResult<Readme?>

    /** Every branch and tag. */
    suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs>

    /** Entries of the directory at [path] ("" for the root), directories first then by name. */
    suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>>

    suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String>

    /** Most recent open issues, pull requests excluded. */
    suspend fun openIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>>

    suspend fun openPullRequests(token: String?, id: RepoId): ForgeResult<List<IssueSummary>>

    suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>>

    suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>>

    /** Where raw file bytes live, ending with `/`: README images resolve against it. */
    fun rawBaseUrl(id: RepoId, ref: String): String

    /** Where file pages live, ending with `/`: README links resolve against it. */
    fun blobBaseUrl(id: RepoId, ref: String): String
}
