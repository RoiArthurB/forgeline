package fr.arthurbrugiere.forgeline.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import fr.arthurbrugiere.forgeline.core.data.yours.YourReposRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoFilter
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.YourRepos
import fr.arthurbrugiere.forgeline.core.model.matches
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The You tab's list of every repository one can reach, narrowed by what is typed and by whose they are. */
data class YouReposState(
    val all: YourRepos? = null,
    val query: String = "",
    /** Whose repositories are shown: everyone's, one owner's, or those others shared. */
    val filter: RepoFilter = RepoFilter.All,
    /** The repositories that match, most recently pushed first. */
    val shown: List<RepoSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
)

@HiltViewModel
class YouViewModel @Inject constructor(
    private val repository: YourReposRepository,
    @param:Computation private val computation: CoroutineDispatcher,
) : ViewModel() {

    private data class Local(val query: String = "", val filter: RepoFilter = RepoFilter.All, val isRefreshing: Boolean = false, val error: ForgeError? = null)

    private val local = MutableStateFlow(Local())

    // Filtered off the main thread: a thousand repositories, at every letter typed.
    val state: StateFlow<YouReposState> = combine(repository.observe(), local) { all, local ->
        // An owner no longer there (an account signed out) stops narrowing the list.
        val filter = (local.filter as? RepoFilter.Of)?.owner?.let { picked ->
            all?.owners?.firstOrNull { it.login == picked.login && it.forge == picked.forge }?.let { RepoFilter.Of(it) } ?: RepoFilter.All
        } ?: local.filter
        YouReposState(
            all = all,
            query = local.query,
            filter = filter,
            shown = all?.repos.orEmpty().filter { all?.shows(it, filter) != false && it.matches(local.query) },
            isRefreshing = local.isRefreshing,
            error = local.error,
        )
    }.flowOn(computation).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouReposState())

    init {
        load(force = false)
    }

    fun search(query: String) = local.update { it.copy(query = query) }

    /** Narrows the list as [filter] says; the same again shows everyone's. */
    fun pick(filter: RepoFilter) = local.update { it.copy(filter = if (filter == it.filter) RepoFilter.All else filter) }

    fun refresh() = load(force = true)

    fun errorShown() = local.update { it.copy(error = null) }

    private fun load(force: Boolean) {
        local.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.refresh(force)
            local.update { it.copy(isRefreshing = false, error = (result as? ForgeResult.Failure)?.error) }
        }
    }
}
