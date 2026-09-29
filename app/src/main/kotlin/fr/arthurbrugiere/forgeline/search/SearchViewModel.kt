package fr.arthurbrugiere.forgeline.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.search.MergedSearchPage
import fr.arthurbrugiere.forgeline.core.data.search.SearchCursor
import fr.arthurbrugiere.forgeline.core.data.search.SearchRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchResult {
    data class Repository(val repo: RepoSummary) : SearchResult

    data class Issue(val result: IssueSearchResult) : SearchResult

    data class User(val user: UserSummary) : SearchResult
}

/** One scope's results; [query] is the search they answer, null before any. */
data class ScopeResults(
    val query: String? = null,
    val items: List<SearchResult> = emptyList(),
    val totalCount: Int? = null,
    /** Where the next page continues on each forge; null once every forge is through. */
    val next: SearchCursor? = null,
    val isLoading: Boolean = false,
    val error: ForgeError? = null,
    /** The forges searched: each result names its own when there are several. */
    val forges: List<ForgeInstance> = emptyList(),
) {
    val submitted: Boolean get() = query != null
    val hasMore: Boolean get() = next != null
    val showForge: Boolean get() = forges.size > 1
}

data class SearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.REPOSITORIES,
    val results: ScopeResults = ScopeResults(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val search: SearchRepository,
) : ViewModel() {

    private val query = savedState.getStateFlow(QUERY_KEY, "")
    private val scope = savedState.getStateFlow(SCOPE_KEY, SearchScope.REPOSITORIES)
    private val results = MutableStateFlow<Map<SearchScope, ScopeResults>>(emptyMap())

    // Searches only run on submit: GitHub allows 10 searches a minute signed out, 30 signed in.
    private val submitted: String? get() = savedState[SUBMITTED_KEY]

    val state: StateFlow<SearchUiState> = combine(query, scope, results) { query, scope, results ->
        SearchUiState(query, scope, results[scope] ?: ScopeResults())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(query.value, scope.value))

    init {
        if (submitted != null) load(scope.value, cursor = null)
    }

    fun onQueryChange(text: String) {
        savedState[QUERY_KEY] = text
    }

    fun submit() {
        val text = query.value.trim().takeIf { it.isNotEmpty() } ?: return
        savedState[SUBMITTED_KEY] = text
        results.value = emptyMap()
        load(scope.value, cursor = null)
    }

    fun selectScope(selected: SearchScope) {
        savedState[SCOPE_KEY] = selected
        val text = submitted ?: return
        if (results.value[selected]?.query != text) load(selected, cursor = null)
    }

    fun loadMore() {
        val current = results.value[scope.value] ?: return
        val next = current.next ?: return
        if (!current.isLoading && current.error == null) load(scope.value, next)
    }

    fun retry() {
        val current = results.value[scope.value]
        load(scope.value, cursor = if (current == null || current.items.isEmpty()) null else current.next ?: return)
    }

    /** Loads the first page when [cursor] is null, else where [cursor] continues. */
    private fun load(target: SearchScope, cursor: SearchCursor?) {
        val text = submitted ?: return
        results.update { all ->
            val previous = all[target]?.takeIf { cursor != null } ?: ScopeResults(query = text)
            all + (target to previous.copy(isLoading = true, error = null))
        }
        viewModelScope.launch {
            val result: ForgeResult<MergedSearchPage<SearchResult>> = when (target) {
                SearchScope.REPOSITORIES -> search.repositories(text, cursor).map { SearchResult.Repository(it) }
                SearchScope.ISSUES -> search.issues(text, cursor).map { SearchResult.Issue(it) }
                SearchScope.USERS -> search.users(text, cursor).map { SearchResult.User(it) }
            }
            // A newer search replaced this one while it was in flight.
            if (submitted != text) return@launch
            results.update { all ->
                val current = all[target] ?: ScopeResults(query = text)
                val updated = when (result) {
                    is ForgeResult.Failure -> current.copy(isLoading = false, error = result.error)
                    is ForgeResult.Success -> current.copy(
                        items = current.items + result.value.items,
                        // The first page counts every forge; later ones only those with more.
                        totalCount = if (cursor == null) result.value.totalCount else current.totalCount,
                        next = result.value.next,
                        isLoading = false,
                        forges = result.value.forges,
                    )
                }
                all + (target to updated)
            }
        }
    }

    private fun <T> ForgeResult<MergedSearchPage<T>>.map(transform: (T) -> SearchResult): ForgeResult<MergedSearchPage<SearchResult>> =
        when (this) {
            is ForgeResult.Failure -> this
            is ForgeResult.Success -> ForgeResult.Success(MergedSearchPage(value.items.map(transform), value.totalCount, value.next, value.forges))
        }

    private companion object {
        const val QUERY_KEY = "query"
        const val SUBMITTED_KEY = "submitted"
        const val SCOPE_KEY = "scope"
    }
}
