package fr.arthurbrugiere.forgeline.wiki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WikiPage
import fr.arthurbrugiere.forgeline.core.model.WikiPageSummary
import fr.arthurbrugiere.forgeline.core.model.wikiBaseUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WikiUiState(
    val repo: RepoId,
    /** The page shown, by its key. */
    val key: String,
    val page: WikiPage? = null,
    /** Every page of the wiki, where its forge lists them; null until known, and where it can't. */
    val pages: List<WikiPageSummary>? = null,
    val isLoading: Boolean = true,
    val error: ForgeError? = null,
) {
    val webUrl: String get() = page?.webUrl ?: "${repo.wikiBaseUrl}$key"
}

/** A page of a repository's wiki, [page] or its first one, read with the list of the others beside it. */
@HiltViewModel(assistedFactory = WikiViewModel.Factory::class)
class WikiViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted page: String?,
    private val repos: RepoRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, page: String?): WikiViewModel
    }

    private val _state = MutableStateFlow(WikiUiState(repo, page ?: repos.wikiHome(repo.forge)))
    val state: StateFlow<WikiUiState> = _state.asStateFlow()

    init {
        refresh()
        // The list is asked once: it changes far less than the page read.
        viewModelScope.launch {
            val pages = (repos.wikiPages(repo) as? ForgeResult.Success)?.value
            _state.update { it.copy(pages = pages) }
        }
    }

    /** Reads the page again. What was read stays on screen meanwhile, and when the forge can't be asked. */
    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repos.wikiPage(repo, state.value.key)
            _state.update {
                when (result) {
                    is ForgeResult.Success -> it.copy(page = result.value, isLoading = false)
                    is ForgeResult.Failure -> it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }
}
