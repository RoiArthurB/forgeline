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
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CheckUiState(
    val repo: RepoId,
    val checkId: Long,
    /** What the check is called where it was tapped, shown until its report says so itself. */
    val name: String,
    val report: CheckReport? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
)

/** What one check of a pull request reported, read from the forge. */
@HiltViewModel(assistedFactory = CheckViewModel.Factory::class)
class CheckViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val checkId: Long,
    @Assisted name: String,
    private val repository: PullRequestRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, checkId: Long, name: String): CheckViewModel
    }

    private val _state = MutableStateFlow(CheckUiState(repo, checkId, name))
    val state: StateFlow<CheckUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.checkReport(repo, checkId)) {
                is ForgeResult.Success -> _state.update { it.copy(report = result.value, isRefreshing = false) }
                is ForgeResult.Failure -> _state.update { it.copy(isRefreshing = false, error = result.error) }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
