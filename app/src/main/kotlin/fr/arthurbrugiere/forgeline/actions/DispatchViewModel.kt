package fr.arthurbrugiere.forgeline.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.actions.ActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DispatchUiState(
    val workflows: Loadable<List<Workflow>> = Loadable.Idle,
    val selected: Workflow? = null,
    val ref: String = "",
    /** Null inside Loaded: the workflow can't be started by hand. */
    val inputs: Loadable<List<DispatchInput>?> = Loadable.Idle,
    val values: Map<String, String> = emptyMap(),
    val sending: Boolean = false,
    val sendError: ForgeError? = null,
    /** Set once the forge accepted the start; the sheet then closes. */
    val started: Boolean = false,
) {
    /** Required inputs still empty. */
    val missing: List<String>
        get() = ((inputs as? Loadable.Loaded)?.value.orEmpty())
            .filter { it.required && values[it.name].isNullOrBlank() }
            .map { it.name }

    val canStart: Boolean
        get() = selected != null && ref.isNotBlank() && (inputs as? Loadable.Loaded)?.value != null && missing.isEmpty() && !sending
}

/** Starting a workflow by hand: pick it, read the inputs it declares, fill them, start it at a ref. */
@HiltViewModel(assistedFactory = DispatchViewModel.Factory::class)
class DispatchViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val defaultBranch: String,
    private val actions: ActionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, defaultBranch: String): DispatchViewModel
    }

    private val _state = MutableStateFlow(DispatchUiState(ref = defaultBranch))
    val state: StateFlow<DispatchUiState> = _state.asStateFlow()

    /** Starts afresh each time the sheet opens, loading the workflows the first time. */
    fun open() {
        _state.update { DispatchUiState(workflows = it.workflows, ref = defaultBranch) }
        if (_state.value.workflows is Loadable.Loaded) return
        _state.update { it.copy(workflows = Loadable.Loading) }
        viewModelScope.launch {
            val result = actions.workflows(repo)
            _state.update { it.copy(workflows = result.toLoadable()) }
        }
    }

    fun select(workflow: Workflow?) {
        _state.update { it.copy(selected = workflow, inputs = Loadable.Idle, values = emptyMap(), sendError = null) }
        if (workflow != null) loadInputs()
    }

    fun setRef(ref: String) = _state.update { it.copy(ref = ref) }

    /** The inputs of the chosen workflow at the chosen ref; they can differ between branches. */
    fun loadInputs() {
        val workflow = _state.value.selected ?: return
        val ref = _state.value.ref.trim().ifEmpty { defaultBranch }
        _state.update { it.copy(inputs = Loadable.Loading) }
        viewModelScope.launch {
            val result = actions.dispatchInputs(repo, workflow, ref)
            _state.update { state ->
                if (state.selected != workflow) return@update state
                val inputs = result.toLoadable()
                val defaults = (inputs as? Loadable.Loaded)?.value.orEmpty()
                    .mapNotNull { input -> (input.default ?: if (input.type == DispatchInputType.BOOLEAN) "false" else null)?.let { input.name to it } }
                    .toMap()
                state.copy(inputs = inputs, values = defaults + state.values)
            }
        }
    }

    fun setValue(name: String, value: String) = _state.update { it.copy(values = it.values + (name to value)) }

    fun start() {
        val state = _state.value
        val workflow = state.selected ?: return
        if (!state.canStart) return
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            val declared = (state.inputs as? Loadable.Loaded)?.value.orEmpty().map { it.name }.toSet()
            // Empty optional inputs are left out, so the workflow sees its own defaults.
            val inputs = state.values.filterKeys { it in declared }.filterValues { it.isNotEmpty() }
            val result = actions.dispatch(repo, workflow, state.ref.trim(), inputs)
            _state.update {
                it.copy(sending = false, sendError = (result as? ForgeResult.Failure)?.error, started = result is ForgeResult.Success)
            }
        }
    }

    private fun <T> ForgeResult<T>.toLoadable(): Loadable<T> = when (this) {
        is ForgeResult.Success -> Loadable.Loaded(value)
        is ForgeResult.Failure -> Loadable.Failed(error)
    }
}
