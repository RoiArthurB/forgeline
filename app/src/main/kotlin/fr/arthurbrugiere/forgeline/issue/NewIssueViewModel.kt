package fr.arthurbrugiere.forgeline.issue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.draft.Draft
import fr.arthurbrugiere.forgeline.core.data.draft.DraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.InMemoryDraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.commentDraftKey
import fr.arthurbrugiere.forgeline.core.data.draft.issueDraftKey
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueChooser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
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
    /** What the repository offers to start a new issue from. */
    val templates: List<IssueTemplate> = emptyList(),
    /** The template whose text is being asked for, by its key. */
    val pickingTemplate: String? = null,
    /** A template's text couldn't be had. */
    val templateError: ForgeError? = null,
    /** Whether the repository lets an issue start blank, and where else it sends people. */
    val chooser: IssueChooser = IssueChooser(),
    /** The labels the template picked puts on the issue, by name, unless the reader takes them off. */
    val labels: List<String> = emptyList(),
    /** The branch an open pull request being edited goes into, as picked, and as it was; null for anything else. */
    val base: String? = null,
    val baseWas: String? = null,
    /** The repository's branches, once listed: what [base] can be. */
    val branches: List<String> = emptyList(),
) {
    /** The branch a pull request goes into can be changed with its title, where there is another to go into. */
    val offersBase: Boolean get() = base != null && branches.size > 1

    /** Templates are offered while nothing is written yet: picking one fills the form, it doesn't overwrite it. */
    val offersTemplates: Boolean get() = editing == null && templates.isNotEmpty() && title.isEmpty() && body.isEmpty() && !isSending

    /** Where else the repository sends people, offered before anything is written, like the templates. */
    val offersLinks: Boolean get() = editing == null && chooser.links.isNotEmpty() && title.isEmpty() && body.isEmpty() && !isSending

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
    suggestions: ReferenceSuggestions = ReferenceSuggestions(),
    private val branches: Branches = Branches(),
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, editing: IssueRef?): NewIssueViewModel
    }

    /** The conversations a reference being typed in the description may mean. */
    val references = ReferenceTyping(viewModelScope, suggestions, repo)

    private val _state = MutableStateFlow(
        if (editing == null) {
            drafts[repo].let { NewIssueUiState(repo, it.title, it.body) }
        } else {
            val issue = repository.cached(editing)?.issue
            // Only an open pull request is pointed elsewhere.
            val base = issue?.pullRequest?.baseRef?.takeIf { issue.state == IssueState.OPEN }
            NewIssueUiState(repo, issue?.title.orEmpty(), issue?.body.orEmpty(), editing = editing, isPullRequest = issue?.pullRequest != null, base = base, baseWas = base)
        },
    )
    val state: StateFlow<NewIssueUiState> = _state.asStateFlow()

    init {
        // What the repository offers to start from, for a new issue; a forge that won't say offers nothing.
        if (editing == null) {
            viewModelScope.launch {
                val templates = (repository.issueTemplates(repo) as? ForgeResult.Success)?.value.orEmpty()
                _state.update { it.copy(templates = templates) }
            }
            viewModelScope.launch {
                val chooser = (repository.issueChooser(repo) as? ForgeResult.Success)?.value ?: return@launch
                _state.update { it.copy(chooser = chooser) }
            }
        }
        // What an open pull request could go into instead.
        if (_state.value.base != null) {
            viewModelScope.launch {
                val listed = (branches(repo) as? ForgeResult.Success)?.value.orEmpty()
                _state.update { it.copy(branches = listed) }
            }
        }
        // Written in an earlier launch: it comes back, unless the reader has started writing meanwhile.
        if (editing == null && _state.value.run { title.isEmpty() && body.isEmpty() }) {
            viewModelScope.launch {
                val kept = drafts.stored(repo)
                _state.update { if (it.title.isEmpty() && it.body.isEmpty()) it.copy(title = kept.title, body = kept.body) else it }
            }
        }
    }

    /** Fills the form from [template], its text asked for first where the forge listed it without. */
    fun useTemplate(template: IssueTemplate) {
        if (_state.value.pickingTemplate != null) return
        val body = template.body
        if (body != null) {
            write { it.copy(title = template.title, body = body, templateError = null, labels = template.labels) }
            return
        }
        _state.update { it.copy(pickingTemplate = template.key, templateError = null) }
        viewModelScope.launch {
            when (val result = repository.issueTemplate(repo, template)) {
                is ForgeResult.Failure -> _state.update { it.copy(pickingTemplate = null, templateError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { it.copy(pickingTemplate = null) }
                    write { it.copy(title = result.value.title, body = result.value.body.orEmpty(), labels = result.value.labels) }
                }
            }
        }
    }

    fun baseChanged(branch: String) = _state.update { it.copy(base = branch, error = null) }

    fun titleChanged(text: String) = write { it.copy(title = text) }

    fun bodyChanged(text: String) = write { it.copy(body = text) }

    /** Opens the issue without the labels its template puts on it. */
    fun dropLabels() = _state.update { it.copy(labels = emptyList()) }

    private fun write(change: (NewIssueUiState) -> NewIssueUiState) {
        val written = _state.updateAndGet { before ->
            val changed = change(before).copy(error = null)
            // Everything written taken away is the template too: its labels go with it.
            if (changed.title.isEmpty() && changed.body.isEmpty()) changed.copy(labels = emptyList()) else changed
        }
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
                // The branch first: a pull request pointed elsewhere is what the reader asked above all, and a title
                // saved without it would say it was done.
                val base = state.base
                if (base != null && base != state.baseWas) {
                    when (val moved = repository.setBase(editing, base)) {
                        is ForgeResult.Failure -> {
                            _state.update { it.copy(isSending = false, error = moved.error) }
                            return@launch
                        }
                        is ForgeResult.Success -> _state.update { it.copy(baseWas = base) }
                    }
                }
                when (val result = repository.edit(editing, state.title.trim(), state.body.trim())) {
                    is ForgeResult.Failure -> _state.update { it.copy(isSending = false, error = result.error) }
                    is ForgeResult.Success -> _state.update { it.copy(isSending = false, created = editing) }
                }
            }
            return
        }
        viewModelScope.launch {
            when (val result = repository.create(repo, state.title.trim(), state.body.trim(), state.labels)) {
                is ForgeResult.Failure -> _state.update { it.copy(isSending = false, error = result.error) }
                is ForgeResult.Success -> {
                    drafts.keep(repo, IssueDraft())
                    _state.update { it.copy(isSending = false, created = result.value.ref) }
                }
            }
        }
    }
}
