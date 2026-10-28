package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkKind

/** Forge-wide search, best match first. [query] uses the forge's own search syntax. No code search, by design. */
interface SearchApi {
    suspend fun repositories(token: String?, query: String, page: Int = 1): ForgeResult<SearchPage<RepoSummary>>

    /** Issues and pull requests together. */
    suspend fun issues(token: String?, query: String, page: Int = 1): ForgeResult<SearchPage<IssueSearchResult>>

    suspend fun users(token: String?, query: String, page: Int = 1): ForgeResult<SearchPage<UserSummary>>

    /**
     * The open issues of [repos] marked for a first contribution (see [fr.arthurbrugiere.forgeline.core.model.FirstIssueLabels]),
     * the newest first. A repository that can't be read is left out; this only fails when none could be.
     */
    suspend fun firstIssues(token: String?, repos: List<RepoId>): ForgeResult<List<IssueSearchResult>>

    /** The repositories tagged [topic], the most starred first. */
    suspend fun topic(token: String?, topic: String, page: Int = 1): ForgeResult<SearchPage<RepoSummary>>

    /**
     * The open conversations that are [kind] to the account [token] signs in, known there as [login]: the first
     * page of them, most recently touched first.
     */
    suspend fun work(token: String, login: String, kind: WorkKind): ForgeResult<List<IssueSearchResult>>
}
