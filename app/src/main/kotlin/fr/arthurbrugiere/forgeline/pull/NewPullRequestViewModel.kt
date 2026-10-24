package fr.arthurbrugiere.forgeline.pull

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.pull.PullRequestRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewPullRequestUiState(
    val repo: RepoId,
    /** The repository's branches, its default one first. */
    val branches: Loadable<List<String>> = Loadable.Loading,
    /** The branch the change goes into: the default one until another is picked. */
    val base: String? = null,
    /** The branch that holds the change; none until one is picked. */
    val head: String? = null,
    val title: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val isSending: Boolean = false,
    val error: ForgeError? = null,
    /** The pull request once opened: the form gives way to it. */
    val created: IssueRef? = null,
    /** What the repository offers to start the description from. */
    val templates: List<IssueTemplate> = emptyList(),
    /** The template whose text is being asked for, by its key. */
    val pickingTemplate: String? = null,
    /** A template's text couldn't be had. */
    val templateError: ForgeError? = null,
    /** The template the description was last filled from, by its key, its text as kept, and as put in the description. */
    val template: String? = null,
    val templateSource: String? = null,
    val templateText: String? = null,
) {
    val canSend: Boolean get() = !isSending && title.isNotBlank() && base != null && head != null && head != base

    /** Whether the description is still a template's, untouched, or empty: another template may then take its place. */
    val bodyIsTemplate: Boolean get() = body.isEmpty() || body == templateText

    /** Where there is a choice, it stays offered until the reader writes in the description. */
    val offersTemplates: Boolean get() = templates.size > 1 && bodyIsTemplate && !isSending
}

/**
 * Opens a pull request between two branches of one repository. One from a fork into the repository it was forked
 * from is not offered: the forges each name a fork's branch their own way.
 */
@HiltViewModel(assistedFactory = NewPullRequestViewModel.Factory::class)
class NewPullRequestViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    private val repos: RepoRepository,
    private val pulls: PullRequestRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId): NewPullRequestViewModel
    }

    private val _state = MutableStateFlow(NewPullRequestUiState(repo))
    val state: StateFlow<NewPullRequestUiState> = _state.asStateFlow()

    init {
        loadBranches()
    }

    fun loadBranches() {
        _state.update { it.copy(branches = Loadable.Loading) }
        viewModelScope.launch {
            when (val result = repos.refs(repo)) {
                is ForgeResult.Failure -> _state.update { it.copy(branches = Loadable.Failed(result.error)) }
                is ForgeResult.Success -> {
                    // The repository was just looked at: what it calls its default branch is at hand.
                    val default = repos.observe(repo).first().details?.defaultBranch?.takeIf { it in result.value.branches }
                    val branches = listOfNotNull(default) + (result.value.branches - setOfNotNull(default))
                    _state.update { it.copy(branches = Loadable.Loaded(branches), base = it.base ?: default ?: branches.firstOrNull()) }
                    (default ?: branches.firstOrNull())?.let(::loadTemplates)
                }
            }
        }
    }

    private var templatesAsked = false

    /** What the repository offers to start the description from, as kept on its default [branch]. */
    private fun loadTemplates(branch: String) {
        if (templatesAsked) return
        templatesAsked = true
        viewModelScope.launch {
            // A forge that won't say offers nothing: the form works without.
            val templates = (pulls.templates(repo, branch) as? ForgeResult.Success)?.value.orEmpty()
            _state.update { it.copy(templates = templates) }
            // The one the forge's own form starts from: the only one, or the one named for it.
            val default = templates.singleOrNull() ?: templates.firstOrNull { it.key.substringAfterLast('/').substringBeforeLast('.').lowercase() in DEFAULT_TEMPLATES }
            if (default != null && _state.value.body.isEmpty()) useTemplate(default)
        }
    }

    /** Fills the description from [template], its text asked for first where the forge listed it without. */
    fun useTemplate(template: IssueTemplate) {
        if (_state.value.pickingTemplate != null) return
        template.body?.let { return fill(template.key, it) }
        _state.update { it.copy(pickingTemplate = template.key, templateError = null) }
        viewModelScope.launch {
            when (val result = pulls.template(repo, template)) {
                is ForgeResult.Failure -> _state.update { it.copy(pickingTemplate = null, templateError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { it.copy(pickingTemplate = null) }
                    fill(template.key, result.value.body.orEmpty())
                }
            }
        }
    }

    /** Puts [source] in the description, its branches filled in, unless the reader has written their own meanwhile. */
    private fun fill(key: String, source: String) = _state.update {
        if (!it.bodyIsTemplate) return@update it
        val text = withBranches(source, it.head, it.base)
        it.copy(body = text, template = key, templateSource = source, templateText = text, templateError = null)
    }

    /** A template still untouched follows the branches picked, where it names them. */
    private fun NewPullRequestUiState.branchesPicked(): NewPullRequestUiState {
        val source = templateSource ?: return this
        if (body != templateText) return this
        val text = withBranches(source, head, base)
        return copy(body = text, templateText = text)
    }

    fun baseChanged(branch: String) = _state.update { it.copy(base = branch).branchesPicked() }

    /** The branch that holds the change; a title still empty takes its name, as the forges' own forms do. */
    fun headChanged(branch: String) = _state.update {
        it.copy(head = branch, title = it.title.ifBlank { branch.substringAfterLast('/').replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase) })
            .branchesPicked()
    }

    fun titleChanged(title: String) = _state.update { it.copy(title = title) }

    fun bodyChanged(body: String) = _state.update { it.copy(body = body) }

    fun draftChanged(draft: Boolean) = _state.update { it.copy(draft = draft) }

    fun send() {
        val current = _state.value
        if (!current.canSend) return
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            val request = NewPullRequest(current.title.trim(), current.body.trim(), head = current.head!!, base = current.base!!, draft = current.draft)
            when (val result = pulls.create(repo, request)) {
                // What was written stays, to be sent again.
                is ForgeResult.Failure -> _state.update { it.copy(isSending = false, error = result.error) }
                is ForgeResult.Success -> _state.update { it.copy(isSending = false, created = result.value) }
            }
        }
    }
}

/** The names, without folder or extension, of the template a forge's own form starts from when there are several. */
private val DEFAULT_TEMPLATES = setOf("pull_request_template", "default")

/**
 * [template] with the branches GitLab's templates may name (`%{source_branch}`, `%{target_branch}`) filled in, blank
 * until picked; the commits they may quote (`%{first_commit}` and the like) are left out, as they aren't read here.
 */
internal fun withBranches(template: String, head: String?, base: String?): String = template
    .replace("%{source_branch}", head.orEmpty())
    .replace("%{target_branch}", base.orEmpty())
    .replace(Regex("""%\{(all_commits|co_authored_by|first_commit|first_multiline_commit)}"""), "")
