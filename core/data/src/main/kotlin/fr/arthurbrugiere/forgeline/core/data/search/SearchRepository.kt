package fr.arthurbrugiere.forgeline.core.data.search

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import javax.inject.Inject

/**
 * Search works signed out; signed in, it uses the token for the higher rate limit and private results.
 * GitHub only until search spans every forge.
 */
class SearchRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) {
    private val forge = ForgeInstance.GitHub

    suspend fun repositories(query: String, page: Int = 1): ForgeResult<SearchPage<RepoSummary>> =
        clients.search(forge).repositories(accounts.tokenOn(forge), query, page)

    suspend fun issues(query: String, page: Int = 1): ForgeResult<SearchPage<IssueSearchResult>> =
        clients.search(forge).issues(accounts.tokenOn(forge), query, page)

    suspend fun users(query: String, page: Int = 1): ForgeResult<SearchPage<UserSummary>> =
        clients.search(forge).users(accounts.tokenOn(forge), query, page)
}
