package fr.arthurbrugiere.forgeline.issue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class IssueDraft(val title: String = "", val body: String = "")

/**
 * Issues being written, one per repository, for as long as the app runs: leaving the form by mistake, or to look
 * something up, loses nothing.
 */
@Singleton
class IssueDrafts @Inject constructor() {
    private val drafts = ConcurrentHashMap<RepoId, IssueDraft>()

    operator fun get(repo: RepoId): IssueDraft = drafts[repo] ?: IssueDraft()

    fun keep(repo: RepoId, draft: IssueDraft) {
        if (draft.title.isEmpty() && draft.body.isEmpty()) drafts.remove(repo) else drafts[repo] = draft
    }
}

data class NewIssueUiState(
    val repo: RepoId,
    val title: String = "",
    val body: String = "",
    val isSending: Boolean = false,
    val error: ForgeError? = null,
    /** The issue the forge opened, once it has: the form gives way to its conversation. */
    val created: IssueRef? = null,
) {
    /** An issue needs a title; its description can stay empty. */
    val canSend: Boolean get() = title.isNotBlank() && !isSending
}

@HiltViewModel(assistedFactory = NewIssueViewModel.Factory::class)
class NewIssueViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    private val repository: IssueRepository,
    private val drafts: IssueDrafts,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId): NewIssueViewModel
    }

    private val _state = MutableStateFlow(drafts[repo].let { NewIssueUiState(repo, it.title, it.body) })
    val state: StateFlow<NewIssueUiState> = _state.asStateFlow()

    fun titleChanged(text: String) = write { it.copy(title = text) }

    fun bodyChanged(text: String) = write { it.copy(body = text) }

    private fun write(change: (NewIssueUiState) -> NewIssueUiState) {
        val written = _state.updateAndGet { change(it).copy(error = null) }
        drafts.keep(repo, IssueDraft(written.title, written.body))
    }

    /** Opens the issue. What was written stays until the forge has taken it, so a failure loses nothing. */
    fun send() {
        val state = _state.value
        if (!state.canSend) return
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.create(repo, state.title.trim(), state.body.trim())) {
                is ForgeResult.Failure -> _state.update { it.copy(isSending = false, error = result.error) }
                is ForgeResult.Success -> {
                    drafts.keep(repo, IssueDraft())
                    _state.update { it.copy(isSending = false, created = result.value.ref) }
                }
            }
        }
    }
}
