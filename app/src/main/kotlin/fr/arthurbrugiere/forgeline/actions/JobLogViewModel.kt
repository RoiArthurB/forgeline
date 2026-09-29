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
    /** Folded groups the reader opened, and groups holding an error, which start open. */
    val openGroups: Set<Int> = emptySet(),
) {
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
        _state.update { it.copy(log = Loadable.Loading) }
        viewModelScope.launch {
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
    }

    fun toggleGroup(entry: Int) = _state.update {
        it.copy(openGroups = if (entry in it.openGroups) it.openGroups - entry else it.openGroups + entry)
    }
}
