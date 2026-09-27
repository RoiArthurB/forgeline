package fr.arthurbrugiere.forgeline.core.data.search

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Search works signed out; signed in, it uses the token for the higher rate limit and private results. */
class SearchRepository @Inject constructor(
    private val api: SearchApi,
    private val accounts: AccountRepository,
) {
    suspend fun repositories(query: String, page: Int = 1): ForgeResult<SearchPage<RepoSummary>> = api.repositories(token(), query, page)

    suspend fun issues(query: String, page: Int = 1): ForgeResult<SearchPage<IssueSearchResult>> = api.issues(token(), query, page)

    suspend fun users(query: String, page: Int = 1): ForgeResult<SearchPage<UserSummary>> = api.users(token(), query, page)

    private suspend fun token(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }
}
