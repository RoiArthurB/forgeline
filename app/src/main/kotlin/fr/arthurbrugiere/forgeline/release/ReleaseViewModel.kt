package fr.arthurbrugiere.forgeline.release

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReleaseUiState(
    val repo: RepoId,
    val tag: String,
    val release: Release? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
)

@HiltViewModel(assistedFactory = ReleaseViewModel.Factory::class)
class ReleaseViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val tag: String,
    private val repos: RepoRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, tag: String): ReleaseViewModel
    }

    private val _state = MutableStateFlow(ReleaseUiState(repo, tag, repos.cachedRelease(repo, tag)))
    val state: StateFlow<ReleaseUiState> = _state.asStateFlow()

    init {
        // Opened from its repository's list, it is already whole: the forge is only asked when it comes from elsewhere.
        if (_state.value.release == null) refresh()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            when (val result = repos.release(repo, tag)) {
                is ForgeResult.Success -> _state.update { it.copy(release = result.value, isRefreshing = false) }
                is ForgeResult.Failure -> _state.update { it.copy(isRefreshing = false, error = result.error) }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
