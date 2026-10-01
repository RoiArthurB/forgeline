package fr.arthurbrugiere.forgeline.issue

import androidx.lifecycle.SavedStateHandle
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
    /** The comment being written; kept while it is sent and when sending fails. */
    val draft: String = "",
    val isCommenting: Boolean = false,
    val commentError: ForgeError? = null,
    /** A comment was posted but more of the conversation is still to load, so it can't be shown in its place yet. */
    val commentPostedOutOfSight: Boolean = false,
)

@HiltViewModel(assistedFactory = IssueViewModel.Factory::class)
class IssueViewModel @AssistedInject constructor(
    @Assisted private val ref: IssueRef,
    private val repository: IssueRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(ref: IssueRef): IssueViewModel
    }

    private val _state = MutableStateFlow(
        repository.cached(ref).let { cached ->
            // The draft outlives the app being stopped: a comment half written is not to be typed again.
            IssueUiState(ref, cached?.issue, cached?.firstPage?.items.orEmpty(), cached?.firstPage?.nextPage, draft = savedState[DRAFT_KEY] ?: "")
        },
    )
    val state: StateFlow<IssueUiState> = _state.asStateFlow()

    init {
        // Not seen this session: the copy kept on disk shows while the forge answers, unless the answer comes first.
        if (_state.value.issue == null) {
            viewModelScope.launch {
                val stored = repository.stored(ref) ?: return@launch
                _state.update { state ->
                    if (state.issue != null || state.items.isNotEmpty()) {
                        state
                    } else {
                        state.copy(issue = stored.issue, items = stored.firstPage?.items.orEmpty(), nextPage = stored.firstPage?.nextPage)
                    }
                }
            }
        }
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

    fun draftChanged(text: String) {
        savedState[DRAFT_KEY] = text
        _state.update { it.copy(draft = text, commentError = null) }
    }

    /** Posts the draft. It stays until the forge has taken it, so a failure loses nothing. */
    fun sendComment() {
        val body = _state.value.draft.trim()
        if (body.isEmpty() || _state.value.isCommenting) return
        _state.update { it.copy(isCommenting = true, commentError = null) }
        viewModelScope.launch {
            when (val result = repository.comment(ref, body)) {
                is ForgeResult.Failure -> _state.update { it.copy(isCommenting = false, commentError = result.error) }
                is ForgeResult.Success -> {
                    savedState[DRAFT_KEY] = ""
                    _state.update { state ->
                        // At the end of a conversation loaded whole; otherwise it shows once the rest is loaded.
                        val atEnd = state.nextPage == null
                        state.copy(
                            draft = "",
                            isCommenting = false,
                            items = if (atEnd) state.items + result.value else state.items,
                            issue = state.issue?.let { it.copy(comments = it.comments + 1) },
                            commentPostedOutOfSight = !atEnd,
                        )
                    }
                }
            }
        }
    }

    fun commentNoticeShown() = _state.update { it.copy(commentPostedOutOfSight = false) }

    private companion object {
        const val DRAFT_KEY = "draft"
    }
}
