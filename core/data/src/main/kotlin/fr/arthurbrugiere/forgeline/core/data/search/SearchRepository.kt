package fr.arthurbrugiere.forgeline.core.data.search

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Where a search continues: the next page to read from each forge that has more. */
data class SearchCursor(val pages: Map<ForgeInstance, Int>)

/** One page of a search across forges; [forges] are the forges searched, for naming each result's when there are several. */
data class MergedSearchPage<T>(val items: List<T>, val totalCount: Int, val next: SearchCursor?, val forges: List<ForgeInstance>)

/**
 * One search across forges: GitHub's, always (it works signed out), and that of each forge an account is signed in to.
 * Each forge's results keep their own order and are interleaved by rank: every forge's best match, then every
 * forge's second, and so on, GitHub first. Signed in, a forge is searched with its token, for the higher rate limit
 * and private results.
 */
class SearchRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) {
    /** The forges a search can ask: GitHub, then each forge an account is signed in to. */
    val forges: Flow<List<ForgeInstance>> = accounts.accounts
        .map { signedIn -> (listOf(ForgeInstance.GitHub) + signedIn.map { it.forge }).distinct() }
        .distinctUntilChanged()

    // [only] narrows a search to one forge; null searches them all.

    suspend fun repositories(query: String, cursor: SearchCursor? = null, only: ForgeInstance? = null): ForgeResult<MergedSearchPage<RepoSummary>> =
        search(cursor, only) { token, page -> repositories(token, query, page) }

    suspend fun issues(query: String, cursor: SearchCursor? = null, only: ForgeInstance? = null): ForgeResult<MergedSearchPage<IssueSearchResult>> =
        search(cursor, only) { token, page -> issues(token, query, page) }

    suspend fun users(query: String, cursor: SearchCursor? = null, only: ForgeInstance? = null): ForgeResult<MergedSearchPage<UserSummary>> =
        search(cursor, only) { token, page -> users(token, query, page) }

    /**
     * Reads the next page of every forge in [cursor], or the first of every forge. A forge that fails is left out of
     * what follows, so one forge down doesn't stop the others; the search only fails when every forge did.
     */
    private suspend fun <T> search(
        cursor: SearchCursor?,
        only: ForgeInstance?,
        fetch: suspend SearchApi.(token: String?, page: Int) -> ForgeResult<SearchPage<T>>,
    ): ForgeResult<MergedSearchPage<T>> {
        val searched = forges.first().filter { only == null || it == only }.ifEmpty { listOfNotNull(only) }
        val pages = cursor?.pages ?: searched.associateWith { 1 }
        val results = coroutineScope {
            pages.map { (forge, page) -> async { forge to clients.search(forge).fetch(accounts.tokenOn(forge), page) } }.awaitAll()
        }
        val found = results.mapNotNull { (forge, result) -> (result as? ForgeResult.Success)?.let { forge to it.value } }
        if (found.isEmpty()) return results.first().second as ForgeResult.Failure
        val next = found.mapNotNull { (forge, page) -> page.nextPage?.let { forge to it } }.toMap()
        return ForgeResult.Success(
            MergedSearchPage(
                items = interleave(found.map { it.second.items }),
                totalCount = found.sumOf { it.second.totalCount },
                next = next.takeIf { it.isNotEmpty() }?.let(::SearchCursor),
                forges = searched,
            ),
        )
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
        for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(rank)?.let(::add) }
    }
}
