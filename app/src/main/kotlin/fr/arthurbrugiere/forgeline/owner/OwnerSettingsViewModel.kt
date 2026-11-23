package fr.arthurbrugiere.forgeline.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.user.OwnerSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.with
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A change the forge just took, to say once, and the change that takes it back when that is one tap: a switch, a choice. */
data class OwnerApplied(val id: Int, val change: OwnerSettingsChange, val undo: OwnerSettingsChange? = null)

data class OwnerSettingsUiState(
    val forge: ForgeInstance,
    /** The profile or organisation's login, as its page names it. */
    val login: String,
    val owner: Owner,
    val settings: OwnerSettings? = null,
    val isRefreshing: Boolean = false,
    /** Why the settings couldn't be read. */
    val error: ForgeError? = null,
    /** The change the forge is being told of; it shows already, and nothing else changes meanwhile. */
    val saving: OwnerSettingsChange? = null,
    /** Why the last change was refused: it was taken back. */
    val refused: ForgeError? = null,
    /** Counts the changes the forge took, so that what asked for one can close on it. */
    val saved: Int = 0,
    val applied: OwnerApplied? = null,
)

/** A person's own profile settings, or an organisation's, each changed on its forge as soon as it is here. */
@HiltViewModel(assistedFactory = OwnerSettingsViewModel.Factory::class)
class OwnerSettingsViewModel @AssistedInject constructor(
    @Assisted private val forge: ForgeInstance,
    @Assisted private val login: String,
    @Assisted organization: Boolean,
    private val repository: OwnerSettingsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(forge: ForgeInstance, login: String, organization: Boolean): OwnerSettingsViewModel
    }

    private val owner: Owner = if (organization) Owner.Organization(login) else Owner.Me

    private val _state = MutableStateFlow(OwnerSettingsUiState(forge, login, owner))
    val state: StateFlow<OwnerSettingsUiState> = _state.asStateFlow()

    private var serial = 0

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.settings(forge, owner)) {
                is ForgeResult.Success -> _state.update { it.copy(settings = result.value, isRefreshing = false) }
                is ForgeResult.Failure -> _state.update { it.copy(isRefreshing = false, error = result.error) }
            }
        }
    }

    /**
     * Shows [change] at once and tells the forge; what it refuses is taken back. One change at a time. What it takes is
     * said once ([OwnerSettingsUiState.applied]), with the way back when [undoable] and there is one.
     */
    fun change(change: OwnerSettingsChange, undoable: Boolean = true) {
        val before = _state.value.settings ?: return
        if (_state.value.saving != null || before.with(change) == before) return
        _state.update { it.copy(settings = before.with(change), saving = change, refused = null, applied = null) }
        viewModelScope.launch {
            when (val result = repository.change(forge, owner, change)) {
                is ForgeResult.Success -> _state.update {
                    it.copy(
                        settings = result.value, saving = null, saved = it.saved + 1,
                        applied = OwnerApplied(++serial, change, undo = if (undoable) before.undoOf(change) else null),
                    )
                }
                is ForgeResult.Failure -> _state.update { it.copy(settings = before, saving = null, refused = result.error) }
            }
        }
    }

    /** Takes the last change back, as a change of its own: said in turn, with no way back from the way back. */
    fun undo() {
        val applied = _state.value.applied ?: return
        _state.update { it.copy(applied = null) }
        applied.undo?.let { change(it, undoable = false) }
    }

    fun appliedShown() = _state.update { it.copy(applied = null) }

    fun refusalShown() = _state.update { it.copy(refused = null) }

    fun errorShown() = _state.update { it.copy(error = null) }
}

/** The change that puts these settings back as they were before [change], where that is one tap to make again. */
internal fun OwnerSettings.undoOf(change: OwnerSettingsChange): OwnerSettingsChange? = when (change) {
    is OwnerSettingsChange.Switch -> switches[change.field]?.let { OwnerSettingsChange.Switch(change.field, it) }
    is OwnerSettingsChange.Choice -> choices[change.field]?.let { OwnerSettingsChange.Choice(change.field, it.value) }
    // Written out: no way back offered.
    else -> null
}
