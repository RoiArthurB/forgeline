package fr.arthurbrugiere.forgeline.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.search.SearchRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
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
    val nextPage: Int? = null,
    val isLoading: Boolean = false,
    val error: ForgeError? = null,
) {
    val submitted: Boolean get() = query != null
    val hasMore: Boolean get() = nextPage != null
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
        if (submitted != null) load(scope.value, page = 1)
    }

    fun onQueryChange(text: String) {
        savedState[QUERY_KEY] = text
    }

    fun submit() {
        val text = query.value.trim().takeIf { it.isNotEmpty() } ?: return
        savedState[SUBMITTED_KEY] = text
        results.value = emptyMap()
        load(scope.value, page = 1)
    }

    fun selectScope(selected: SearchScope) {
        savedState[SCOPE_KEY] = selected
        val text = submitted ?: return
        if (results.value[selected]?.query != text) load(selected, page = 1)
    }

    fun loadMore() {
        val current = results.value[scope.value] ?: return
        val next = current.nextPage ?: return
        if (!current.isLoading && current.error == null) load(scope.value, next)
    }

    fun retry() {
        val current = results.value[scope.value]
        load(scope.value, page = if (current == null || current.items.isEmpty()) 1 else current.nextPage ?: return)
    }

    private fun load(target: SearchScope, page: Int) {
        val text = submitted ?: return
        results.update { all ->
            val previous = all[target]?.takeIf { page > 1 } ?: ScopeResults(query = text)
            all + (target to previous.copy(isLoading = true, error = null))
        }
        viewModelScope.launch {
            val result: ForgeResult<SearchPage<SearchResult>> = when (target) {
                SearchScope.REPOSITORIES -> search.repositories(text, page).map { SearchResult.Repository(it) }
                SearchScope.ISSUES -> search.issues(text, page).map { SearchResult.Issue(it) }
                SearchScope.USERS -> search.users(text, page).map { SearchResult.User(it) }
            }
            // A newer search replaced this one while it was in flight.
            if (submitted != text) return@launch
            results.update { all ->
                val current = all[target] ?: ScopeResults(query = text)
                val updated = when (result) {
                    is ForgeResult.Failure -> current.copy(isLoading = false, error = result.error)
                    is ForgeResult.Success -> current.copy(
                        items = current.items + result.value.items,
                        totalCount = result.value.totalCount,
                        nextPage = result.value.nextPage,
                        isLoading = false,
                    )
                }
                all + (target to updated)
            }
        }
    }

    private fun <T> ForgeResult<SearchPage<T>>.map(transform: (T) -> SearchResult): ForgeResult<SearchPage<SearchResult>> = when (this) {
        is ForgeResult.Failure -> this
        is ForgeResult.Success -> ForgeResult.Success(SearchPage(value.items.map(transform), value.totalCount, value.nextPage))
    }

    private companion object {
        const val QUERY_KEY = "query"
        const val SUBMITTED_KEY = "submitted"
        const val SCOPE_KEY = "scope"
    }
}
