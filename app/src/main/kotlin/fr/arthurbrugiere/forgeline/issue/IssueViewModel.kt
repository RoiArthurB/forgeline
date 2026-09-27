package fr.arthurbrugiere.forgeline.issue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IssueUiState(
    val ref: IssueRef,
    val issue: IssueDetails? = null,
    val items: List<TimelineItem> = emptyList(),
    val nextPage: Int? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: ForgeError? = null,
)

@HiltViewModel(assistedFactory = IssueViewModel.Factory::class)
class IssueViewModel @AssistedInject constructor(
    @Assisted private val ref: IssueRef,
    private val repository: IssueRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(ref: IssueRef): IssueViewModel
    }

    private val _state = MutableStateFlow(
        repository.cached(ref).let { cached ->
            IssueUiState(ref, cached?.issue, cached?.firstPage?.items.orEmpty(), cached?.firstPage?.nextPage)
        },
    )
    val state: StateFlow<IssueUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val issue = async { repository.issue(ref) }
            val firstPage = async { repository.timeline(ref, page = 1) }
            val issueResult = issue.await()
            val pageResult = firstPage.await()
            _state.update { state ->
                state.copy(
                    issue = (issueResult as? ForgeResult.Success)?.value ?: state.issue,
                    items = (pageResult as? ForgeResult.Success)?.value?.items ?: state.items,
                    nextPage = if (pageResult is ForgeResult.Success) pageResult.value.nextPage else state.nextPage,
                    isRefreshing = false,
                    error = (issueResult as? ForgeResult.Failure)?.error ?: (pageResult as? ForgeResult.Failure)?.error,
                )
            }
        }
    }

    fun loadMore() {
        val page = _state.value.nextPage ?: return
        if (_state.value.isLoadingMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.timeline(ref, page)) {
                is ForgeResult.Success -> _state.update {
                    it.copy(items = it.items + result.value.items, nextPage = result.value.nextPage, isLoadingMore = false)
                }
                is ForgeResult.Failure -> _state.update { it.copy(isLoadingMore = false, error = result.error) }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
