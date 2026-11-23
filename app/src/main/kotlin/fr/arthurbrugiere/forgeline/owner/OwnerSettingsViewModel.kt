package fr.arthurbrugiere.forgeline.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.user.MemberChange
import fr.arthurbrugiere.forgeline.core.data.user.OwnerSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.OrgMembers
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.with
import fr.arthurbrugiere.forgeline.repo.Loadable
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
    /** Whether the owner blocks people through the forge's API, and whom, read when asked for. */
    val blocksSupported: Boolean = false,
    val blocked: Loadable<List<UserSummary>> = Loadable.Idle,
    /** Whether an organisation's members are managed here, and who they are, read when asked for. */
    val membersSupported: Boolean = false,
    val members: Loadable<OrgMembers> = Loadable.Idle,
    /** A change to the members is on its way. */
    val membersWorking: Boolean = false,
    /** Whether the picture is changed here, and taken away; a new one is on its way while [pictureWorking]. */
    val avatarSupported: Boolean = false,
    val avatarRemovable: Boolean = false,
    val pictureWorking: Boolean = false,
    /** Counts the pictures the forge took, to say so once each. */
    val pictureChanged: Int = 0,
    /** Whether the organisation is renamed and deleted here; a rename or the deletion is on its way while [working]. */
    val renames: Boolean = false,
    val deletes: Boolean = false,
    val working: Boolean = false,
    /** The organisation's login once renamed: its page is opened there. */
    val renamedTo: String? = null,
    /** What became of the organisation once its deletion was asked for: there is nothing left to set. */
    val deleted: RepoDeletion? = null,
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

    private val _state = MutableStateFlow(
        OwnerSettingsUiState(
            forge, login, owner, blocksSupported = repository.blocksSupported(forge, owner),
            membersSupported = organization && repository.membersSupported(forge),
            renames = organization && repository.renamesOrganizations(forge),
            deletes = organization && repository.deletesOrganizations(forge),
            avatarSupported = repository.avatarSupported(forge, if (organization) Owner.Organization(login) else Owner.Me),
            avatarRemovable = repository.avatarRemovable(forge, if (organization) Owner.Organization(login) else Owner.Me),
        ),
    )
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

    fun loadBlocked() {
        if (_state.value.blocked == Loadable.Loading || _state.value.blocked is Loadable.Loaded) return
        _state.update { it.copy(blocked = Loadable.Loading) }
        viewModelScope.launch {
            val blocked = when (val result = repository.blocked(forge, owner)) {
                is ForgeResult.Success -> Loadable.Loaded(result.value)
                is ForgeResult.Failure -> Loadable.Failed(result.error)
            }
            _state.update { it.copy(blocked = blocked) }
        }
    }

    /** Blocks [person] or lets them be; shown at once, and taken back if the forge refuses. */
    fun setBlocked(person: String, blocked: Boolean) {
        val login = person.trim().removePrefix("@").ifEmpty { return }
        val before = (_state.value.blocked as? Loadable.Loaded)?.value.orEmpty()
        val after = if (blocked) before.filterNot { it.login.equals(login, ignoreCase = true) } + UserSummary(login, null, false, forge) else before.filterNot { it.login == login }
        _state.update { it.copy(blocked = Loadable.Loaded(after), refused = null) }
        viewModelScope.launch {
            when (val result = repository.setBlocked(forge, owner, login, blocked)) {
                is ForgeResult.Success -> _state.update { it.copy(saved = it.saved + 1) }
                is ForgeResult.Failure -> _state.update { it.copy(blocked = Loadable.Loaded(before), refused = result.error) }
            }
        }
    }

    /** Reads the members, again when [force]d (once a change is made, to show it as the forge now has it). */
    fun loadMembers(force: Boolean = false) {
        val current = _state.value.members
        if (!force && (current == Loadable.Loading || current is Loadable.Loaded)) return
        if (current !is Loadable.Loaded) _state.update { it.copy(members = Loadable.Loading) }
        viewModelScope.launch {
            val members = when (val result = repository.members(forge, login)) {
                is ForgeResult.Success -> Loadable.Loaded(result.value)
                is ForgeResult.Failure -> if (current is Loadable.Loaded && force) current else Loadable.Failed(result.error)
            }
            _state.update { it.copy(members = members) }
        }
    }

    /** Makes [change] to the members; what the forge refuses is said, and the list read again either way. */
    fun changeMembers(change: MemberChange) {
        if (_state.value.membersWorking) return
        _state.update { it.copy(membersWorking = true, refused = null) }
        viewModelScope.launch {
            val result = repository.changeMembers(forge, login, change)
            _state.update {
                it.copy(
                    membersWorking = false,
                    refused = (result as? ForgeResult.Failure)?.error,
                    saved = if (result is ForgeResult.Success) it.saved + 1 else it.saved,
                )
            }
            loadMembers(force = true)
        }
    }

    /** Makes [image] (prepared: square, small, JPEG) the picture, or takes it away (null). */
    fun setAvatar(image: ByteArray?) {
        if (_state.value.pictureWorking) return
        _state.update { it.copy(pictureWorking = true, refused = null) }
        viewModelScope.launch {
            val result = repository.setAvatar(forge, owner, image)
            _state.update {
                when (result) {
                    is ForgeResult.Success -> it.copy(pictureWorking = false, pictureChanged = it.pictureChanged + 1)
                    is ForgeResult.Failure -> it.copy(pictureWorking = false, refused = result.error)
                }
            }
        }
    }

    fun rename(name: String) {
        if (_state.value.working || name.isBlank() || name.trim() == login) return
        _state.update { it.copy(working = true, refused = null) }
        viewModelScope.launch {
            when (val result = repository.renameOrganization(forge, login, name)) {
                is ForgeResult.Success -> _state.update { it.copy(working = false, renamedTo = result.value) }
                is ForgeResult.Failure -> _state.update { it.copy(working = false, refused = result.error) }
            }
        }
    }

    fun delete() {
        if (_state.value.working) return
        _state.update { it.copy(working = true, refused = null) }
        viewModelScope.launch {
            when (val result = repository.deleteOrganization(forge, login)) {
                is ForgeResult.Success -> _state.update { it.copy(working = false, deleted = result.value) }
                is ForgeResult.Failure -> _state.update { it.copy(working = false, refused = result.error) }
            }
        }
    }

    /** A picture that couldn't be read from the phone. */
    fun pictureUnreadable() = _state.update { it.copy(refused = ForgeError.Unreadable) }

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
