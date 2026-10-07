package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.data.draft.issueDraftKey
import fr.arthurbrugiere.forgeline.core.data.draft.commentDraftKey
import fr.arthurbrugiere.forgeline.core.data.draft.InMemoryDraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.DraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.Draft
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
import javax.inject.Inject
import javax.inject.Singleton

data class IssueDraft(val title: String = "", val body: String = "")

/**
 * What is being written and not sent yet: an issue per repository, a comment per conversation. Kept until it is
 * sent, across screens and across launches: leaving by mistake, to look something up, or because the phone took the
 * app away, loses nothing.
 */
@Singleton
class IssueDrafts @Inject constructor(private val store: DraftStore) {
    /** Drafts for as long as this object lives: for tests. */
    constructor() : this(InMemoryDraftStore())

    /** The issue being written for [repo], as far as it is known without waiting: see [stored]. */
    operator fun get(repo: RepoId): IssueDraft = store.peek(issueDraftKey(repo)).toIssueDraft()

    /** The issue being written for [repo], from an earlier launch if need be. */
    suspend fun stored(repo: RepoId): IssueDraft = store.read(issueDraftKey(repo)).toIssueDraft()

    fun keep(repo: RepoId, draft: IssueDraft) = store.write(issueDraftKey(repo), Draft(draft.title, draft.body))

    /** The comment being written in [ref], as far as it is known without waiting: see [storedComment]. */
    fun comment(ref: IssueRef): String = store.peek(commentDraftKey(ref))?.body.orEmpty()

    /** The comment being written in [ref], from an earlier launch if need be. */
    suspend fun storedComment(ref: IssueRef): String = store.read(commentDraftKey(ref))?.body.orEmpty()

    fun keepComment(ref: IssueRef, text: String) = store.write(commentDraftKey(ref), Draft(body = text))

    private fun Draft?.toIssueDraft() = this?.let { IssueDraft(it.title, it.body) } ?: IssueDraft()
}

data class NewIssueUiState(
    val repo: RepoId,
    val title: String = "",
    val body: String = "",
    val isSending: Boolean = false,
    val error: ForgeError? = null,
    /** The issue the forge opened, or the one it changed, once it has: the form gives way to its conversation. */
    val created: IssueRef? = null,
    /** The conversation whose title and text are being changed; null when a new issue is being written. */
    val editing: IssueRef? = null,
    /** Whether [editing] is a pull request, which the form then says. */
    val isPullRequest: Boolean = false,
) {
    /** An issue needs a title; its description can stay empty. */
    val canSend: Boolean get() = title.isNotBlank() && !isSending
}

@HiltViewModel(assistedFactory = NewIssueViewModel.Factory::class)
class NewIssueViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    private val repository: IssueRepository,
    private val drafts: IssueDrafts,
    /** The conversation to change instead of opening one: the form starts from its title and text as last read. */
    @Assisted private val editing: IssueRef? = null,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, editing: IssueRef?): NewIssueViewModel
    }

    private val _state = MutableStateFlow(
        if (editing == null) {
            drafts[repo].let { NewIssueUiState(repo, it.title, it.body) }
        } else {
            val issue = repository.cached(editing)?.issue
            NewIssueUiState(repo, issue?.title.orEmpty(), issue?.body.orEmpty(), editing = editing, isPullRequest = issue?.pullRequest != null)
        },
    )
    val state: StateFlow<NewIssueUiState> = _state.asStateFlow()

    init {
        // Written in an earlier launch: it comes back, unless the reader has started writing meanwhile.
        if (editing == null && _state.value.run { title.isEmpty() && body.isEmpty() }) {
            viewModelScope.launch {
                val kept = drafts.stored(repo)
                _state.update { if (it.title.isEmpty() && it.body.isEmpty()) it.copy(title = kept.title, body = kept.body) else it }
            }
        }
    }

    fun titleChanged(text: String) = write { it.copy(title = text) }

    fun bodyChanged(text: String) = write { it.copy(body = text) }

    private fun write(change: (NewIssueUiState) -> NewIssueUiState) {
        val written = _state.updateAndGet { change(it).copy(error = null) }
        // The draft kept is the issue not opened yet: changes to one that exists are not it.
        if (editing == null) drafts.keep(repo, IssueDraft(written.title, written.body))
    }

    /** Opens the issue. What was written stays until the forge has taken it, so a failure loses nothing. */
    fun send() {
        val state = _state.value
        if (!state.canSend) return
        _state.update { it.copy(isSending = true, error = null) }
        if (editing != null) {
            viewModelScope.launch {
                when (val result = repository.edit(editing, state.title.trim(), state.body.trim())) {
                    is ForgeResult.Failure -> _state.update { it.copy(isSending = false, error = result.error) }
                    is ForgeResult.Success -> _state.update { it.copy(isSending = false, created = editing) }
                }
            }
            return
        }
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
