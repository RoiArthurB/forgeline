package fr.arthurbrugiere.forgeline.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.actions.ActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JobLogUiState(
    val repo: RepoId,
    val jobId: Long,
    val jobName: String,
    val log: Loadable<JobLog> = Loadable.Loading,
    /** The job and its steps, which update while it runs; its log only exists once it has finished. */
    val job: RunJob? = null,
    /** Log requests made since the job finished: GitHub publishes the log a few seconds after. */
    val logAttempts: Int = 0,
    /** Folded groups the reader opened, and groups holding an error, which start open. */
    val openGroups: Set<Int> = emptySet(),
) {
    /** The job is still going: the screen shows its steps live instead of a log. */
    val isRunning: Boolean get() = job != null && job.status != RunStatus.COMPLETED

    /** Worth checking again soon: the job runs, or it just finished and its log isn't published yet. */
    val isLive: Boolean
        get() = isRunning || (job != null && logAttempts < MAX_LOG_ATTEMPTS && (log as? Loadable.Failed)?.error.isNotFound())

    /** What the list shows: lines, group headers, and the lines of open groups. */
    val rows: List<LogRow> by lazy {
        val entries = (log as? Loadable.Loaded)?.value?.entries.orEmpty()
        buildList {
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    is LogEntry.Line -> add(LogRow.Line(entry, index))
                    is LogEntry.Group -> {
                        val open = index in openGroups
                        add(LogRow.Header(entry.title, index, open, entry.lines.size))
                        if (open) entry.lines.forEachIndexed { line, it -> add(LogRow.Line(it, index, line)) }
                    }
                }
            }
        }
    }

    /** Where the errors are in [rows], for "Jump to error". */
    val errorRows: List<Int> by lazy { rows.indices.filter { (rows[it] as? LogRow.Line)?.line?.kind == LogLineKind.ERROR } }
}

/** How many times to ask for a just-finished job's log before saying it's missing. */
const val MAX_LOG_ATTEMPTS = 6

private fun ForgeError?.isNotFound() = this is ForgeError.Http && status == 404

sealed interface LogRow {
    val key: String

    data class Line(val line: LogEntry.Line, val entry: Int, val inGroup: Int? = null) : LogRow {
        override val key get() = if (inGroup == null) "l$entry" else "g$entry-$inGroup"
    }

    data class Header(val title: String, val entry: Int, val open: Boolean, val lines: Int) : LogRow {
        override val key get() = "h$entry"
    }
}

@HiltViewModel(assistedFactory = JobLogViewModel.Factory::class)
class JobLogViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val jobId: Long,
    @Assisted jobName: String,
    private val actions: ActionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, jobId: Long, jobName: String): JobLogViewModel
    }

    private val _state = MutableStateFlow(JobLogUiState(repo, jobId, jobName))
    val state: StateFlow<JobLogUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(log = Loadable.Loading, logAttempts = 0) }
        viewModelScope.launch { check() }
    }

    /** A quiet check while [JobLogUiState.isLive]: the steps while the job runs, then its log. */
    fun poll() {
        viewModelScope.launch { check() }
    }

    private suspend fun check() {
        val job = (actions.job(repo, jobId) as? ForgeResult.Success)?.value
        if (job != null) _state.update { it.copy(job = job) }
        // Without the job's state (an error), try the log anyway: a finished job's log may still come.
        if (job == null || job.status == RunStatus.COMPLETED) loadLog() else _state.update { it.copy(log = Loadable.Idle) }
    }

    private suspend fun loadLog() {
        if (_state.value.log is Loadable.Loaded) return
        _state.update { it.copy(logAttempts = it.logAttempts + 1) }
        when (val result = actions.jobLog(repo, jobId)) {
            is ForgeResult.Failure -> _state.update { it.copy(log = Loadable.Failed(result.error)) }
            is ForgeResult.Success -> {
                val failing = result.value.entries.withIndex()
                    .filter { (_, entry) -> entry is LogEntry.Group && entry.lines.any { it.kind == LogLineKind.ERROR } }
                    .map { it.index }
                _state.update { it.copy(log = Loadable.Loaded(result.value), openGroups = failing.toSet()) }
            }
        }
    }

    companion object {
        /** How often a live job is checked while on screen. */
        const val POLL_MILLIS = 5_000L
    }

    fun toggleGroup(entry: Int) = _state.update {
        it.copy(openGroups = if (entry in it.openGroups) it.openGroups - entry else it.openGroups + entry)
    }
}
