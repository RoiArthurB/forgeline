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
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RunAction { RERUN_FAILED, RERUN_ALL, CANCEL }

/** The outcome of a [RunAction], shown once. */
data class RunActionResult(val action: RunAction, val error: ForgeError? = null)

data class RunUiState(
    val repo: RepoId,
    val runId: Long,
    val run: WorkflowRun? = null,
    val jobs: List<RunJob>? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
    /** The action waiting for the forge's answer. */
    val pending: RunAction? = null,
    val result: RunActionResult? = null,
    /** Whether the forge can start the run again (Forgejo's API can't). */
    val canRerun: Boolean = true,
) {
    val isFinished: Boolean get() = run?.status == RunStatus.COMPLETED
}

@HiltViewModel(assistedFactory = RunViewModel.Factory::class)
class RunViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val runId: Long,
    private val actions: ActionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, runId: Long): RunViewModel
    }

    private val _state = MutableStateFlow(RunUiState(repo, runId, canRerun = actions.supportsRerun(repo)))
    val state: StateFlow<RunUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = load(showRefreshing = true)

    /** A quiet refresh while the run is going: no indicator, and a failure keeps what is shown. */
    fun poll() = load(showRefreshing = false)

    fun perform(action: RunAction) {
        if (_state.value.pending != null) return
        _state.update { it.copy(pending = action) }
        viewModelScope.launch {
            val result = when (action) {
                RunAction.RERUN_FAILED -> actions.rerun(repo, runId, failedJobsOnly = true)
                RunAction.RERUN_ALL -> actions.rerun(repo, runId, failedJobsOnly = false)
                RunAction.CANCEL -> actions.cancel(repo, runId)
            }
            _state.update { it.copy(pending = null, result = RunActionResult(action, (result as? ForgeResult.Failure)?.error)) }
            if (result is ForgeResult.Success) {
                // The forge takes a moment to queue the new attempt or stop the jobs.
                delay(SETTLE_MILLIS)
                poll()
            }
        }
    }

    fun resultShown() = _state.update { it.copy(result = null) }

    fun errorShown() = _state.update { it.copy(error = null) }

    private fun load(showRefreshing: Boolean) {
        if (showRefreshing) _state.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val run = async { actions.run(repo, runId) }
            val jobs = async { actions.jobs(repo, runId) }
            val runResult = run.await()
            val jobsResult = jobs.await()
            _state.update { state ->
                state.copy(
                    run = (runResult as? ForgeResult.Success)?.value ?: state.run,
                    jobs = (jobsResult as? ForgeResult.Success)?.value ?: state.jobs,
                    isRefreshing = false,
                    error = if (showRefreshing) (runResult as? ForgeResult.Failure)?.error ?: (jobsResult as? ForgeResult.Failure)?.error else state.error,
                )
            }
        }
    }

    companion object {
        const val SETTLE_MILLIS = 2_000L

        /** How often a run that isn't finished is checked while on screen. */
        const val POLL_MILLIS = 10_000L
    }
}
