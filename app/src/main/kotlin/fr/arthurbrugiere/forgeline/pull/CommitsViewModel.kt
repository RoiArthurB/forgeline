package fr.arthurbrugiere.forgeline.pull

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.pull.PullRequestRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which commits are listed: a pull request's, or a repository's history from a ref, of one file when [History.path] is set. */
sealed interface CommitsTarget {
    val repo: RepoId

    data class Pull(val ref: IssueRef) : CommitsTarget {
        override val repo: RepoId get() = ref.repo
    }

    data class History(override val repo: RepoId, val ref: String?, val path: String?) : CommitsTarget
}

data class CommitsUiState(
    val target: CommitsTarget,
    /** Null until the first page is in. A pull request's read oldest first, a history newest first. */
    val commits: List<Commit>? = null,
    val nextPage: Int? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ForgeError? = null,
)

@HiltViewModel(assistedFactory = CommitsViewModel.Factory::class)
class CommitsViewModel @AssistedInject constructor(
    @Assisted private val target: CommitsTarget,
    private val repository: PullRequestRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(target: CommitsTarget): CommitsViewModel
    }

    private val _state = MutableStateFlow(CommitsUiState(target))
    val state: StateFlow<CommitsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = when (target) {
                is CommitsTarget.Pull -> repository.commits(target.ref).let { result ->
                    if (result is ForgeResult.Success) ForgeResult.Success(result.value to null) else result as ForgeResult.Failure
                }
                is CommitsTarget.History -> repository.history(target.repo, target.ref, target.path).let { result ->
                    if (result is ForgeResult.Success) ForgeResult.Success(result.value.commits to result.value.nextPage) else result as ForgeResult.Failure
                }
            }
            when (result) {
                is ForgeResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error) }
                is ForgeResult.Success -> _state.update { it.copy(isLoading = false, commits = result.value.first, nextPage = result.value.second) }
            }
        }
    }

    /** Reads the next page of a history, when the list's end comes in sight. */
    fun loadMore() {
        val current = _state.value
        val page = current.nextPage ?: return
        if (current.isLoadingMore || target !is CommitsTarget.History) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.history(target.repo, target.ref, target.path, page)) {
                is ForgeResult.Failure -> _state.update { it.copy(isLoadingMore = false, error = result.error) }
                is ForgeResult.Success -> _state.update {
                    it.copy(isLoadingMore = false, commits = it.commits.orEmpty() + result.value.commits, nextPage = result.value.nextPage)
                }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
