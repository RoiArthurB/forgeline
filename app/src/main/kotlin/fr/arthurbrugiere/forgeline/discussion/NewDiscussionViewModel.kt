package fr.arthurbrugiere.forgeline.discussion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DiscussionCategory
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewDiscussionUiState(
    val repo: RepoId,
    /** Where it may be filed, once read; null meanwhile, and when they couldn't be ([categoriesError]). */
    val categories: List<DiscussionCategory>? = null,
    val categoriesError: ForgeError? = null,
    /** The category picked, by its id: the repository's first until the reader picks another. */
    val category: String? = null,
    val title: String = "",
    val body: String = "",
    val isSending: Boolean = false,
    /** Why the discussion wasn't started; what was written stays. */
    val error: ForgeError? = null,
    /** The number of the discussion started, whose page takes this one's place. */
    val created: Int? = null,
) {
    val canSend: Boolean get() = category != null && title.isNotBlank() && !isSending && created == null
}

/** Starting a discussion in [repo]: where it is filed, its title and its text. */
@HiltViewModel(assistedFactory = NewDiscussionViewModel.Factory::class)
class NewDiscussionViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    private val repos: RepoRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId): NewDiscussionViewModel
    }

    private val _state = MutableStateFlow(NewDiscussionUiState(repo))
    val state: StateFlow<NewDiscussionUiState> = _state.asStateFlow()

    init {
        readCategories()
    }

    /** Reads where it may be filed, again when it failed. */
    fun readCategories() {
        _state.update { it.copy(categoriesError = null) }
        viewModelScope.launch {
            when (val result = repos.discussionCategories(repo)) {
                is ForgeResult.Success -> _state.update { state ->
                    state.copy(categories = result.value, category = state.category?.takeIf { id -> result.value.any { it.id == id } } ?: result.value.firstOrNull()?.id)
                }
                is ForgeResult.Failure -> _state.update { it.copy(categoriesError = result.error) }
            }
        }
    }

    fun categoryPicked(id: String) = _state.update { it.copy(category = id) }

    fun titleChanged(title: String) = _state.update { it.copy(title = title, error = null) }

    fun bodyChanged(body: String) = _state.update { it.copy(body = body, error = null) }

    fun send() {
        val state = _state.value
        val category = state.category ?: return
        if (!state.canSend) return
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            when (val result = repos.startDiscussion(repo, category, state.title.trim(), state.body.trim())) {
                is ForgeResult.Success -> _state.update { it.copy(isSending = false, created = result.value) }
                is ForgeResult.Failure -> _state.update { it.copy(isSending = false, error = result.error) }
            }
        }
    }
}
