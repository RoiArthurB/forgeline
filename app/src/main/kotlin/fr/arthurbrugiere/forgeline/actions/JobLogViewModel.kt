package fr.arthurbrugiere.forgeline.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.actions.ActionsRepository
import fr.arthurbrugiere.forgeline.core.data.actions.JobLogRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StepLines
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.LogStep
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/** What has been read of one step: windows of its last lines, the earliest first. */
data class StepRead(
    val entries: Loadable<List<LogEntry>> = Loadable.Loading,
    /** Where the earliest line read begins: the step has more before it while this is past the step's start. */
    val start: Long = 0,
    /** How many of the log's lines have been read. */
    val lines: Int = 0,
    val loadingEarlier: Boolean = false,
    val earlierFailed: ForgeError? = null,
    /** Folded groups the reader opened, and groups holding an error, which start open: counted from the step's end. */
    val openGroups: Set<Int> = emptySet(),
)

data class JobLogUiState(
    val repo: RepoId,
    val jobId: Long,
    val jobName: String,
    /** The job and its steps, which update while it runs; its log only exists once it has finished. */
    val job: RunJob? = null,
    /** The log's steps, each with where its lines are: nothing of them is read until one is opened. */
    val steps: Loadable<List<LogStep>> = Loadable.Loading,
    /** Log requests made since the job finished: GitHub publishes the log a few seconds after. */
    val logAttempts: Int = 0,
    /** Steps opened, by index. */
    val opened: Set<Int> = emptySet(),
    /** What has been read of each step, kept while it is folded again. */
    val read: Map<Int, StepRead> = emptyMap(),
    /** The step opened by itself as the log came, for the screen to scroll to once. */
    val scrollToStep: Int? = null,
) {
    /** The job is still going: the screen shows its steps live instead of a log. */
    val isRunning: Boolean get() = job != null && job.status != RunStatus.COMPLETED

    /** Worth checking again soon: the job runs, or it just finished and its log isn't published yet. */
    val isLive: Boolean
        get() = isRunning || (job != null && logAttempts < MAX_LOG_ATTEMPTS && (steps as? Loadable.Failed)?.error.isNotFound())

    /** The step the job failed at: what the header offers to open. */
    val failedStep: Int? get() = (steps as? Loadable.Loaded)?.value?.indexOfFirst { it.conclusion == RunConclusion.FAILURE }?.takeIf { it >= 0 }

    /** How long each step took, when the forge says and its steps are the log's. */
    val durations: List<Duration?> by lazy {
        val logSteps = (steps as? Loadable.Loaded)?.value.orEmpty()
        val apiSteps = job?.steps.orEmpty().sortedBy { it.number }
        if (apiSteps.size != logSteps.size) return@lazy logSteps.map { null }
        apiSteps.map { step -> step.startedAt?.let { start -> step.completedAt?.let { Duration.between(start, it) } } }
    }

    /** What the list shows: each step, and the lines of those opened. */
    val rows: List<LogRow> by lazy {
        val logSteps = (steps as? Loadable.Loaded)?.value.orEmpty()
        buildList {
            logSteps.forEachIndexed { index, step ->
                val open = index in opened
                add(LogRow.Step(index, step, open, durations.getOrNull(index)))
                if (!open) return@forEachIndexed
                val read = read[index] ?: StepRead()
                when (val entries = read.entries) {
                    Loadable.Idle, Loadable.Loading -> add(LogRow.Loading(index))
                    is Loadable.Failed -> add(LogRow.Failed(index, entries.error))
                    is Loadable.Loaded -> {
                        if (read.start > step.start) {
                            add(LogRow.Earlier(index, step.lines?.let { it - read.lines }, read.start - step.start, read.loadingEarlier, read.earlierFailed))
                        }
                        val count = entries.value.size
                        entries.value.forEachIndexed { at, entry ->
                            val fromEnd = count - at
                            when (entry) {
                                is LogEntry.Line -> add(LogRow.Line(index, entry, fromEnd))
                                is LogEntry.Group -> {
                                    val groupOpen = fromEnd in read.openGroups
                                    add(LogRow.Group(index, entry.title, fromEnd, groupOpen, entry.lines.size))
                                    if (groupOpen) entry.lines.forEachIndexed { line, it -> add(LogRow.Line(index, it, fromEnd, line)) }
                                }
                            }
                        }
                        if (count == 0 && read.start <= step.start) add(LogRow.Empty(index))
                    }
                }
            }
        }
    }

    /** Where the errors read so far are in [rows], for "Jump to an error". */
    val errorRows: List<Int> by lazy { rows.indices.filter { (rows[it] as? LogRow.Line)?.line?.kind == LogLineKind.ERROR } }
}

/** How many times to ask for a just-finished job's log before saying it's missing. */
const val MAX_LOG_ATTEMPTS = 6

private fun ForgeError?.isNotFound() = this is ForgeError.Http && status == 404

sealed interface LogRow {
    val step: Int
    val key: String

    data class Step(override val step: Int, val logStep: LogStep, val open: Boolean, val took: Duration?) : LogRow {
        override val key get() = "s$step"
    }

    data class Loading(override val step: Int) : LogRow {
        override val key get() = "s$step-loading"
    }

    data class Failed(override val step: Int, val error: ForgeError) : LogRow {
        override val key get() = "s$step-failed"
    }

    /** The step has lines before those read: [lines] of them when the whole log was read, else [bytes] of them. */
    data class Earlier(override val step: Int, val lines: Int?, val bytes: Long, val loading: Boolean, val error: ForgeError?) : LogRow {
        override val key get() = "s$step-earlier"
    }

    data class Empty(override val step: Int) : LogRow {
        override val key get() = "s$step-empty"
    }

    data class Group(override val step: Int, val title: String, val fromEnd: Int, val open: Boolean, val lines: Int) : LogRow {
        override val key get() = "s$step-g$fromEnd"
    }

    data class Line(override val step: Int, val line: LogEntry.Line, val fromEnd: Int, val inGroup: Int? = null) : LogRow {
        override val key get() = if (inGroup == null) "s$step-l$fromEnd" else "s$step-g$fromEnd-$inGroup"
    }
}

@HiltViewModel(assistedFactory = JobLogViewModel.Factory::class)
class JobLogViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val runId: Long,
    @Assisted("jobId") private val jobId: Long,
    @Assisted jobName: String,
    private val actions: ActionsRepository,
    private val logs: JobLogRepository,
    private val settings: UserSettingsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, runId: Long, @Assisted("jobId") jobId: Long, jobName: String): JobLogViewModel
    }

    private val _state = MutableStateFlow(JobLogUiState(repo, jobId, jobName))
    val state: StateFlow<JobLogUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(steps = Loadable.Loading, logAttempts = 0) }
        viewModelScope.launch { check() }
    }

    /** A quiet check while [JobLogUiState.isLive]: the steps while the job runs, then its log's. */
    fun poll() {
        viewModelScope.launch { check() }
    }

    private suspend fun check() {
        val job = (actions.job(repo, runId, jobId) as? ForgeResult.Success)?.value
        if (job != null) _state.update { it.copy(job = job) }
        // Without the job (an older Forgejo lists no jobs), its log may still come: its steps are read from it.
        val finished = job ?: RunJob(jobId, _state.value.jobName, RunStatus.COMPLETED, null, null, null, emptyList())
        if (finished.status == RunStatus.COMPLETED) loadSteps(finished) else _state.update { it.copy(steps = Loadable.Idle) }
    }

    private suspend fun loadSteps(job: RunJob) {
        if (_state.value.steps is Loadable.Loaded) return
        _state.update { it.copy(logAttempts = it.logAttempts + 1) }
        when (val result = logs.steps(repo, job)) {
            is ForgeResult.Failure -> _state.update { it.copy(steps = Loadable.Failed(result.error)) }
            is ForgeResult.Success -> {
                _state.update { it.copy(steps = Loadable.Loaded(result.value)) }
                if (settings.settings.first().openFailedStep) openFailed()?.let { failed -> _state.update { it.copy(scrollToStep = failed) } }
            }
        }
    }

    /** The screen scrolled to [JobLogUiState.scrollToStep]: it isn't done again. */
    fun scrolledToStep() = _state.update { it.copy(scrollToStep = null) }

    /** Opens step [index], reading its last lines the first time; folds it when open. */
    fun toggleStep(index: Int) {
        val state = _state.value
        if (index in state.opened) return _state.update { it.copy(opened = it.opened - index) }
        val step = (state.steps as? Loadable.Loaded)?.value?.getOrNull(index) ?: return
        if (step.isEmpty) return
        _state.update { it.copy(opened = it.opened + index) }
        if (state.read[index]?.entries is Loadable.Loaded) return
        readLast(index, step)
    }

    /** Opens the step the job failed at, if it isn't: answers its index, to be scrolled to. */
    fun openFailed(): Int? {
        val failed = _state.value.failedStep ?: return null
        if (failed !in _state.value.opened) toggleStep(failed)
        return failed
    }

    /** Reads [index]'s last lines again, after they failed to come. */
    fun retryStep(index: Int) {
        val step = (_state.value.steps as? Loadable.Loaded)?.value?.getOrNull(index) ?: return
        readLast(index, step)
    }

    /** The job the log is read for: as the forge said it, or as little as the screen knows of it. */
    private val job: RunJob
        get() = _state.value.job ?: RunJob(jobId, _state.value.jobName, RunStatus.COMPLETED, null, null, null, emptyList())

    private fun readLast(index: Int, step: LogStep) {
        updateRead(index) { StepRead(start = step.end) }
        viewModelScope.launch {
            when (val result = logs.lines(repo, job, step, step.end)) {
                is ForgeResult.Failure -> updateRead(index) { it.copy(entries = Loadable.Failed(result.error)) }
                is ForgeResult.Success -> updateRead(index) {
                    StepRead(Loadable.Loaded(result.value.entries), result.value.start, result.value.lines, openGroups = errorGroups(result.value.entries))
                }
            }
        }
    }

    /** Reads the lines of step [index] before those already read. */
    fun loadEarlier(index: Int) {
        val state = _state.value
        val step = (state.steps as? Loadable.Loaded)?.value?.getOrNull(index) ?: return
        val read = state.read[index] ?: return
        val shown = (read.entries as? Loadable.Loaded)?.value ?: return
        if (read.loadingEarlier || read.start <= step.start) return
        updateRead(index) { it.copy(loadingEarlier = true, earlierFailed = null) }
        viewModelScope.launch {
            when (val result = logs.lines(repo, job, step, read.start)) {
                is ForgeResult.Failure -> updateRead(index) { it.copy(loadingEarlier = false, earlierFailed = result.error) }
                is ForgeResult.Success -> updateRead(index) { current ->
                    val earlier: StepLines = result.value
                    // Groups are counted from the step's end: those already read keep their place.
                    val opened = errorGroups(earlier.entries).map { it + shown.size }.toSet()
                    current.copy(
                        entries = Loadable.Loaded(earlier.entries + shown),
                        start = earlier.start,
                        lines = current.lines + earlier.lines,
                        loadingEarlier = false,
                        openGroups = current.openGroups + opened,
                    )
                }
            }
        }
    }

    fun toggleGroup(step: Int, fromEnd: Int) = updateRead(step) {
        it.copy(openGroups = if (fromEnd in it.openGroups) it.openGroups - fromEnd else it.openGroups + fromEnd)
    }

    private fun updateRead(index: Int, change: (StepRead) -> StepRead) =
        _state.update { it.copy(read = it.read + (index to change(it.read[index] ?: StepRead()))) }

    /** The groups of [entries] holding an error, counted from the end: they start open. */
    private fun errorGroups(entries: List<LogEntry>): Set<Int> = entries.withIndex()
        .filter { (_, entry) -> entry is LogEntry.Group && entry.lines.any { it.kind == LogLineKind.ERROR } }
        .map { entries.size - it.index }
        .toSet()

    companion object {
        /** How often a live job is checked while on screen. */
        const val POLL_MILLIS = 5_000L
    }
}
