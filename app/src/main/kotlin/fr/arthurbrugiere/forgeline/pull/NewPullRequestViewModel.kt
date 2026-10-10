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
) {
    val canSend: Boolean get() = !isSending && title.isNotBlank() && base != null && head != null && head != base
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
                }
            }
        }
    }

    fun baseChanged(branch: String) = _state.update { it.copy(base = branch) }

    /** The branch that holds the change; a title still empty takes its name, as the forges' own forms do. */
    fun headChanged(branch: String) = _state.update {
        it.copy(head = branch, title = it.title.ifBlank { branch.substringAfterLast('/').replace('-', ' ').replace('_', ' ').replaceFirstChar(Char::uppercase) })
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
