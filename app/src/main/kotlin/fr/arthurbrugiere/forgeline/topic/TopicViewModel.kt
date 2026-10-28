package fr.arthurbrugiere.forgeline.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.search.SearchCursor
import fr.arthurbrugiere.forgeline.core.data.search.SearchRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TopicUiState(
    val topic: String,
    val repos: List<RepoSummary> = emptyList(),
    /** Null before the first page came. */
    val totalCount: Int? = null,
    val next: SearchCursor? = null,
    val isLoading: Boolean = true,
    val error: ForgeError? = null,
    /** The forges asked; a choice among them is offered, and each row names its own, when there are several. */
    val forges: List<ForgeInstance> = emptyList(),
    /** The one forge asked, or null for all of them. */
    val onlyForge: ForgeInstance? = null,
) {
    val hasMore: Boolean get() = next != null
    val showForge: Boolean get() = onlyForge == null && forges.size > 1
}

/** A topic's repositories: each forge's most starred, interleaved, a page at a time. */
@HiltViewModel(assistedFactory = TopicViewModel.Factory::class)
class TopicViewModel @AssistedInject constructor(
    @Assisted topic: String,
    private val search: SearchRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(topic: String): TopicViewModel
    }

    private val _state = MutableStateFlow(TopicUiState(topic))
    val state: StateFlow<TopicUiState> = _state.asStateFlow()

    private var loading: Job? = null

    init {
        load(cursor = null)
    }

    fun loadMore() {
        val current = _state.value
        val next = current.next ?: return
        if (!current.isLoading && current.error == null) load(next)
    }

    fun retry() {
        val current = _state.value
        load(if (current.repos.isEmpty()) null else current.next ?: return)
    }

    /** Asks [forge] alone, or every forge when null, from the first page. */
    fun selectForge(forge: ForgeInstance?) {
        if (forge == _state.value.onlyForge) return
        _state.update { TopicUiState(it.topic, forges = it.forges, onlyForge = forge) }
        load(cursor = null)
    }

    private fun load(cursor: SearchCursor?) {
        _state.update { it.copy(isLoading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            val forges = search.topicForges.first()
            val only = _state.value.onlyForge
            when (val result = search.topic(_state.value.topic, cursor, only)) {
                is ForgeResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error, forges = forges) }
                is ForgeResult.Success -> _state.update {
                    val page = result.value
                    it.copy(
                        // A repository can come twice when a forge's pages shift under it.
                        repos = (if (cursor == null) page.items else it.repos + page.items).distinctBy { repo -> repo.id },
                        totalCount = if (cursor == null) page.totalCount else it.totalCount,
                        next = page.next,
                        isLoading = false,
                        forges = forges,
                    )
                }
            }
        }
    }
}
