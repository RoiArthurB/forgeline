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
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.DiscussionSummary
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscussionUiState(
    val repo: RepoId,
    val number: Int,
    /** What its repository's list said of it, heading the page while the rest loads. */
    val summary: DiscussionSummary? = null,
    val discussion: Discussion? = null,
    val isLoading: Boolean = true,
    val error: ForgeError? = null,
) {
    val webUrl: String get() = "${repo.webUrl}/discussions/$number"
}

@HiltViewModel(assistedFactory = DiscussionViewModel.Factory::class)
class DiscussionViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val number: Int,
    private val repos: RepoRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, number: Int): DiscussionViewModel
    }

    private val _state = MutableStateFlow(DiscussionUiState(repo, number, summary = repos.rememberedDiscussion(repo, number)))
    val state: StateFlow<DiscussionUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reads the discussion again. What was read stays on screen meanwhile, and when the forge can't be asked. */
    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repos.discussion(repo, number)
            _state.update {
                when (result) {
                    is ForgeResult.Success -> it.copy(discussion = result.value, summary = result.value.summary, isLoading = false)
                    is ForgeResult.Failure -> it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
