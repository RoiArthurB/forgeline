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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class DispatchUiState(
    val workflows: Loadable<List<Workflow>> = Loadable.Idle,
    /**
     * What each workflow asks for when started by hand, read ahead at the default branch as soon as the list is
     * known: null inside Loaded means it can't be started by hand. Missing while still being read.
     */
    val triggers: Map<Long, Loadable<List<DispatchInput>?>> = emptyMap(),
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
    /** Workflows that can be started by hand first, then those still being checked, then the others. */
    val sortedWorkflows: List<Workflow>
        get() = (workflows as? Loadable.Loaded)?.value.orEmpty().sortedBy { workflow ->
            when (val trigger = triggers[workflow.id]) {
                is Loadable.Loaded -> if (trigger.value != null) 0 else 2
                else -> 1
            }
        }

    fun canStartByHand(workflow: Workflow): Boolean? = when (val trigger = triggers[workflow.id]) {
        is Loadable.Loaded -> trigger.value != null
        // A file that couldn't be read may still be startable: let the reader try.
        is Loadable.Failed -> true
        else -> null
    }

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
        _state.update { DispatchUiState(workflows = it.workflows, triggers = it.triggers, ref = defaultBranch) }
        if (_state.value.workflows is Loadable.Loaded) return
        _state.update { it.copy(workflows = Loadable.Loading) }
        viewModelScope.launch {
            val result = actions.workflows(repo)
            _state.update { it.copy(workflows = result.toLoadable()) }
            if (result is ForgeResult.Success) readTriggers(result.value)
        }
    }

    /** Reads every workflow's file, a few at a time, to tell which ones can be started by hand. */
    private suspend fun readTriggers(workflows: List<Workflow>) = coroutineScope {
        val gate = Semaphore(TRIGGER_READS)
        workflows.forEach { workflow ->
            launch {
                gate.withPermit {
                    val result = actions.dispatchInputs(repo, workflow, defaultBranch)
                    _state.update { it.copy(triggers = it.triggers + (workflow.id to result.toLoadable())) }
                }
            }
        }
    }

    fun select(workflow: Workflow?) {
        _state.update { it.copy(selected = workflow, inputs = Loadable.Idle, values = emptyMap(), sendError = null) }
        if (workflow == null) return
        // Already read ahead at the default branch: no need to ask again.
        val known = _state.value.triggers[workflow.id] as? Loadable.Loaded
        if (known != null && _state.value.ref == defaultBranch) showInputs(workflow, known) else loadInputs()
    }

    fun setRef(ref: String) = _state.update { it.copy(ref = ref) }

    /** The inputs of the chosen workflow at the chosen ref; they can differ between branches. */
    fun loadInputs() {
        val workflow = _state.value.selected ?: return
        val ref = _state.value.ref.trim().ifEmpty { defaultBranch }
        _state.update { it.copy(inputs = Loadable.Loading) }
        viewModelScope.launch {
            showInputs(workflow, actions.dispatchInputs(repo, workflow, ref).toLoadable())
        }
    }

    private fun showInputs(workflow: Workflow, inputs: Loadable<List<DispatchInput>?>) = _state.update { state ->
        if (state.selected != workflow) return@update state
        val defaults = (inputs as? Loadable.Loaded)?.value.orEmpty()
            .mapNotNull { input -> (input.default ?: if (input.type == DispatchInputType.BOOLEAN) "false" else null)?.let { input.name to it } }
            .toMap()
        state.copy(inputs = inputs, values = defaults + state.values)
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

    companion object {
        /** Workflow files read at once when telling which can be started by hand. */
        const val TRIGGER_READS = 4
    }

    private fun <T> ForgeResult<T>.toLoadable(): Loadable<T> = when (this) {
        is ForgeResult.Success -> Loadable.Loaded(value)
        is ForgeResult.Failure -> Loadable.Failed(error)
    }
}
