package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary

/** Forge-wide search, best match first. [query] uses the forge's own search syntax. No code search, by design. */
interface SearchApi {
    suspend fun repositories(token: String?, query: String, page: Int = 1): ForgeResult<SearchPage<RepoSummary>>

    /** Issues and pull requests together. */
    suspend fun issues(token: String?, query: String, page: Int = 1): ForgeResult<SearchPage<IssueSearchResult>>

    suspend fun users(token: String?, query: String, page: Int = 1): ForgeResult<SearchPage<UserSummary>>
}
