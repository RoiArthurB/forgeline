package fr.arthurbrugiere.forgeline.core.data.search

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.SearchSort
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Where a search continues: the next page to read from each forge that has more. A sorted search also keeps [held],
 * what each forge sent that can't show yet: another forge's next page may come before it.
 */
data class SearchCursor(val pages: Map<ForgeInstance, Int>, val held: Map<ForgeInstance, List<Any>> = emptyMap())

/**
 * One page of a search across forges; [forges] are the forges searched, for naming each result's when there are
 * several. [refused] are those that turned the search away for want of an account there (Codeberg's repositories,
 * signed out, since 2026-11): signing in finds theirs.
 */
data class MergedSearchPage<T>(
    val items: List<T>,
    val totalCount: Int,
    val next: SearchCursor?,
    val forges: List<ForgeInstance>,
    val refused: List<ForgeInstance> = emptyList(),
    /** The forges left out because they can't sort the way asked: best match finds theirs. */
    val unsorted: List<ForgeInstance> = emptyList(),
)

/**
 * One search across forges: the three public ones, always (they answer signed out), and that of each other server an
 * account is signed in to.
 * Each forge's results keep their own order and are interleaved by rank: every forge's best match, then every
 * forge's second, and so on, GitHub first. Sorted otherwise, they are merged in that order, each forge sorting its own.
 * Signed in, a forge is searched with its token, for the higher rate limit
 * and private results.
 */
class SearchRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) {
    /**
     * The forges a search or a topic asks: the three public ones, which answer without an account, then each other
     * server an account is signed in to. Regression: signing in to GitHub alone left Codeberg and gitlab.com out.
     */
    val forges: Flow<List<ForgeInstance>> = accounts.accounts
        .map { signedIn -> (PUBLIC_FORGES + signedIn.map { it.forge }).distinct() }
        .distinctUntilChanged()

    /** The forges an account is signed in to: one turning a search away answers it once signed in there. */
    val signedIn: Flow<Set<ForgeInstance>> = accounts.accounts.map { signedIn -> signedIn.mapTo(HashSet()) { it.forge } }.distinctUntilChanged()

    /**
     * The orders a search of [scope] on [forges] can be sorted in: those at least one of them sorts in, in the order
     * [SearchScope.sorts] gives. Best match always.
     */
    fun sorts(scope: SearchScope, forges: List<ForgeInstance>): List<SearchSort> =
        forges.flatMapTo(hashSetOf(SearchSort.BEST_MATCH)) { clients.search(it).sorts(scope) }.let { offered -> scope.sorts.filter { it in offered } }

    // [only] narrows a search to one forge; null searches them all.

    suspend fun repositories(
        query: String,
        cursor: SearchCursor? = null,
        only: ForgeInstance? = null,
        sort: SearchSort = SearchSort.BEST_MATCH,
    ): ForgeResult<MergedSearchPage<RepoSummary>> =
        search(cursor, only, SearchScope.REPOSITORIES, sort, repoOrder(sort)) { token, page -> repositories(token, query, page, sort) }

    suspend fun issues(
        query: String,
        cursor: SearchCursor? = null,
        only: ForgeInstance? = null,
        sort: SearchSort = SearchSort.BEST_MATCH,
    ): ForgeResult<MergedSearchPage<IssueSearchResult>> =
        search(cursor, only, SearchScope.ISSUES, sort, issueOrder(sort)) { token, page -> issues(token, query, page, sort) }

    suspend fun users(query: String, cursor: SearchCursor? = null, only: ForgeInstance? = null): ForgeResult<MergedSearchPage<UserSummary>> =
        search(cursor, only) { token, page -> users(token, query, page) }

    /** The repositories tagged [topic], each forge's most starred first, interleaved as a search's are. */
    suspend fun topic(topic: String, cursor: SearchCursor? = null, only: ForgeInstance? = null): ForgeResult<MergedSearchPage<RepoSummary>> =
        search(cursor, only) { token, page -> topic(token, topic, page) }

    /**
     * Reads the next page of every forge in [cursor], or the first of every forge. A forge that fails is left out of
     * what follows, so one forge down doesn't stop the others; the search only fails when every forge did. A forge
     * that turned a signed-out search away isn't a failure but [MergedSearchPage.refused]: the page says so. Sorted
     * by [order], a forge that can't sort by [sort] isn't asked but named in [MergedSearchPage.unsorted].
     */
    private suspend fun <T> search(
        cursor: SearchCursor?,
        only: ForgeInstance?,
        scope: SearchScope = SearchScope.REPOSITORIES,
        sort: SearchSort = SearchSort.BEST_MATCH,
        order: Comparator<T>? = null,
        fetch: suspend SearchApi.(token: String?, page: Int) -> ForgeResult<SearchPage<T>>,
    ): ForgeResult<MergedSearchPage<T>> {
        val searched = forges.first().filter { only == null || it == only }.ifEmpty { listOfNotNull(only) }
        val unsorted = if (sort == SearchSort.BEST_MATCH) emptyList() else searched.filter { sort !in clients.search(it).sorts(scope) }
        @Suppress("UNCHECKED_CAST")
        val held = cursor?.held.orEmpty() as Map<ForgeInstance, List<T>>
        val pages = cursor?.pages ?: (searched - unsorted.toSet()).associateWith { 1 }
        // A forge with results still held is read again only once they have shown.
        val asked = pages.filterKeys { held[it].isNullOrEmpty() }
        val results = coroutineScope {
            asked.map { (forge, page) ->
                async {
                    val token = accounts.tokenOn(forge)
                    Triple(forge, token, clients.search(forge).fetch(token, page))
                }
            }.awaitAll()
        }
        val found = results.mapNotNull { (forge, _, result) -> (result as? ForgeResult.Success)?.let { forge to it.value } }
        val refused = results.filter { (_, token, result) -> token == null && (result as? ForgeResult.Failure)?.error == ForgeError.Unauthorized }.map { it.first }
        // Nothing found where a forge failed says why, rather than that there is nothing: signed out, GitHub's limit
        // reached while Codeberg and gitlab.com have no match would read as no match anywhere.
        val failed = results.filter { it.first !in refused }.firstNotNullOfOrNull { (_, _, result) -> result as? ForgeResult.Failure }
        if (failed != null && held.values.all { it.isEmpty() } && found.all { it.second.items.isEmpty() }) return failed
        // A forge still showing what it sent keeps its place, unread.
        val next = pages.filterKeys { it !in asked } + found.mapNotNull { (forge, page) -> page.nextPage?.let { forge to it } }
        if (order == null) {
            return ForgeResult.Success(
                MergedSearchPage(
                    items = interleave(found.map { it.second.items }),
                    totalCount = found.sumOf { it.second.totalCount },
                    next = next.takeIf { it.isNotEmpty() }?.let(::SearchCursor),
                    forges = searched,
                    refused = refused,
                    unsorted = unsorted,
                ),
            )
        }
        val pagesFound = found.toMap()
        val waiting = (held.keys + pagesFound.keys).sortedBy { searched.indexOf(it) }
            .associateWith { forge -> held[forge].orEmpty() + pagesFound[forge]?.items.orEmpty() }
        val (shown, kept) = merge(waiting, next.keys, order)
        return ForgeResult.Success(
            MergedSearchPage(
                items = shown,
                totalCount = found.sumOf { it.second.totalCount },
                next = SearchCursor(next, kept).takeIf { next.isNotEmpty() || kept.values.any { it.isNotEmpty() } },
                forges = searched,
                refused = refused,
                unsorted = unsorted,
            ),
        )
    }

    /**
     * What of [waiting] can show in [order]: all of it, but for forges that [more] to come, each of whose next pages
     * comes after the last of its own here. What shows is what comes before every such last; the rest is kept.
     */
    private fun <T> merge(waiting: Map<ForgeInstance, List<T>>, more: Set<ForgeInstance>, order: Comparator<T>): Pair<List<T>, Map<ForgeInstance, List<T>>> {
        val bound = waiting.filterKeys { it in more }.values.mapNotNull { it.lastOrNull() }.minWithOrNull(order)
        val shown = mutableListOf<T>()
        val kept = waiting.mapValues { (_, items) ->
            val (now, later) = items.partition { bound == null || order.compare(it, bound) <= 0 }
            shown += now
            later
        }.filterValues { it.isNotEmpty() }
        // Stable: within ties, the forges' order, GitHub first.
        return shown.sortedWith(order) to kept
    }

    private companion object {
        val PUBLIC_FORGES = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg, ForgeInstance.GitLab)

        fun repoOrder(sort: SearchSort): Comparator<RepoSummary>? = when (sort) {
            SearchSort.STARS -> compareByDescending { it.stars }
            SearchSort.UPDATED -> compareByDescending(nullsFirst<java.time.Instant>()) { it.updatedAt }
            SearchSort.BEST_MATCH, SearchSort.NEWEST -> null
        }

        fun issueOrder(sort: SearchSort): Comparator<IssueSearchResult>? = when (sort) {
            SearchSort.NEWEST -> compareByDescending { it.issue.createdAt }
            SearchSort.UPDATED -> compareByDescending { it.issue.updatedAt ?: it.issue.createdAt }
            SearchSort.BEST_MATCH, SearchSort.STARS -> null
        }
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
        for (rank in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(rank)?.let(::add) }
    }
}
