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
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
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
    /** The repository's website, where its forge sets something about one; read beside the settings. */
    val pages: PagesSite? = null,
    /** A rename, the deletion or a change to the website is on its way. */
    val working: Boolean = false,
    /** Where the repository now is, once renamed: its page is opened there. */
    val renamedTo: RepoId? = null,
    /** What became of the repository once its deletion was asked for: there is nothing left to set. */
    val deleted: RepoDeletion? = null,
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
        // Beside the settings, and without holding them back: a site that can't be read is one nothing is offered for.
        viewModelScope.launch {
            val pages = (repository.pages(repo) as? ForgeResult.Success)?.value
            _state.update { it.copy(pages = pages ?: it.pages) }
        }
    }

    /** Changes the repository's website; what the forge refuses is said, and the site stays as it was. */
    fun changePages(change: PagesChange) {
        if (busy() || _state.value.pages == null) return
        _state.update { it.copy(working = true, refused = null) }
        viewModelScope.launch {
            when (val result = repository.changePages(repo, change)) {
                is ForgeResult.Success -> _state.update { it.copy(working = false, pages = result.value, saved = it.saved + 1) }
                is ForgeResult.Failure -> _state.update { it.copy(working = false, refused = result.error) }
            }
        }
    }

    /** Shows [change] at once and tells the forge; what it refuses is taken back. One change at a time. */
    fun change(change: RepoSettingsChange) {
        val before = _state.value.settings ?: return
        if (busy() || before.with(change) == before) return
        _state.update { it.copy(settings = before.with(change), saving = change, refused = null) }
        viewModelScope.launch {
            when (val result = repository.change(repo, change)) {
                is ForgeResult.Success -> _state.update { it.copy(settings = result.value, saving = null, saved = it.saved + 1) }
                is ForgeResult.Failure -> _state.update { it.copy(settings = before, saving = null, refused = result.error) }
            }
        }
    }

    fun rename(name: String) {
        if (busy() || name.isBlank() || name == repo.name) return
        _state.update { it.copy(working = true, refused = null) }
        viewModelScope.launch {
            when (val result = repository.rename(repo, name.trim())) {
                is ForgeResult.Success -> _state.update { it.copy(working = false, renamedTo = result.value) }
                is ForgeResult.Failure -> _state.update { it.copy(working = false, refused = result.error) }
            }
        }
    }

    fun delete() {
        if (busy()) return
        _state.update { it.copy(working = true, refused = null) }
        viewModelScope.launch {
            when (val result = repository.delete(repo)) {
                is ForgeResult.Success -> _state.update { it.copy(working = false, deleted = result.value) }
                is ForgeResult.Failure -> _state.update { it.copy(working = false, refused = result.error) }
            }
        }
    }

    private fun busy() = _state.value.run { working || saving != null || deleted != null }

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
