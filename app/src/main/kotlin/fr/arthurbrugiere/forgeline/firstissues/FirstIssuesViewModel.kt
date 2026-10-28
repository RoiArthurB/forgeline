package fr.arthurbrugiere.forgeline.firstissues

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.firstissues.FirstIssuesRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FirstIssue
import fr.arthurbrugiere.forgeline.core.model.FirstIssueSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FirstIssuesUiState(
    /** Every issue found, newest first; null before any was looked for. */
    val all: List<FirstIssue>? = null,
    /** One source alone, or both when null. */
    val source: FirstIssueSource? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
    val fetchedAtMillis: Long? = null,
) {
    val shown: List<FirstIssue> get() = all.orEmpty().filter { source == null || it.source == source }

    /** Whether both sources found something: only then is a choice between them worth offering. */
    val hasBothSources: Boolean get() = all.orEmpty().map { it.source }.toSet().size > 1

    /** Each row names its forge once issues come from several. */
    val showForge: Boolean get() = all.orEmpty().map { it.repo.forge }.toSet().size > 1
}

@HiltViewModel
class FirstIssuesViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: FirstIssuesRepository,
) : ViewModel() {
    private val progress = MutableStateFlow(Progress())

    private data class Progress(val isRefreshing: Boolean = false, val error: ForgeError? = null)

    val state: StateFlow<FirstIssuesUiState> = combine(repository.observe(), savedState.getStateFlow<FirstIssueSource?>(SOURCE_KEY, null), progress) { found, source, progress ->
        FirstIssuesUiState(found?.issues, source, progress.isRefreshing, progress.error, found?.fetchedAtMillis)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FirstIssuesUiState(isRefreshing = true))

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        progress.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val result = repository.refresh(force)
            progress.value = Progress(isRefreshing = false, error = (result as? ForgeResult.Failure)?.error)
        }
    }

    fun selectSource(source: FirstIssueSource?) {
        savedState[SOURCE_KEY] = source
    }

    fun errorShown() = progress.update { it.copy(error = null) }

    private companion object {
        const val SOURCE_KEY = "source"
    }
}
