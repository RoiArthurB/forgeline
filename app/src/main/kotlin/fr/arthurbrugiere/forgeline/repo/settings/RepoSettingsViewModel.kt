package fr.arthurbrugiere.forgeline.repo.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.with
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RepoSettingsUiState(
    val repo: RepoId,
    val settings: RepoSettings? = null,
    val isRefreshing: Boolean = false,
    /** Why the settings couldn't be read. */
    val error: ForgeError? = null,
    /** The change the forge is being told of; it shows already, and nothing else changes meanwhile. */
    val saving: RepoSettingsChange? = null,
    /** Why the last change was refused: it was taken back. */
    val refused: ForgeError? = null,
    /** Counts the changes the forge took, so that what asked for one can close on it. */
    val saved: Int = 0,
    /** The branches the default one is picked among, read when asked for. */
    val branches: Loadable<List<String>> = Loadable.Idle,
)

/** A repository's settings, each changed on its forge as soon as it is here. */
@HiltViewModel(assistedFactory = RepoSettingsViewModel.Factory::class)
class RepoSettingsViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    private val repository: RepoSettingsRepository,
    private val repos: RepoRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId): RepoSettingsViewModel
    }

    private val _state = MutableStateFlow(RepoSettingsUiState(repo))
    val state: StateFlow<RepoSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.settings(repo)) {
                is ForgeResult.Success -> _state.update { it.copy(settings = result.value, isRefreshing = false) }
                is ForgeResult.Failure -> _state.update { it.copy(isRefreshing = false, error = result.error) }
            }
        }
    }

    /** Shows [change] at once and tells the forge; what it refuses is taken back. One change at a time. */
    fun change(change: RepoSettingsChange) {
        val before = _state.value.settings ?: return
        if (_state.value.saving != null || before.with(change) == before) return
        _state.update { it.copy(settings = before.with(change), saving = change, refused = null) }
        viewModelScope.launch {
            when (val result = repository.change(repo, change)) {
                is ForgeResult.Success -> _state.update { it.copy(settings = result.value, saving = null, saved = it.saved + 1) }
                is ForgeResult.Failure -> _state.update { it.copy(settings = before, saving = null, refused = result.error) }
            }
        }
    }

    fun loadBranches() {
        if (_state.value.branches is Loadable.Loaded || _state.value.branches == Loadable.Loading) return
        _state.update { it.copy(branches = Loadable.Loading) }
        viewModelScope.launch {
            val branches = when (val result = repos.refs(repo)) {
                is ForgeResult.Success -> Loadable.Loaded(result.value.branches)
                is ForgeResult.Failure -> Loadable.Failed(result.error)
            }
            _state.update { it.copy(branches = branches) }
        }
    }

    fun refusalShown() = _state.update { it.copy(refused = null) }

    fun errorShown() = _state.update { it.copy(error = null) }
}
