package fr.arthurbrugiere.forgeline.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.actions.ActionsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ArtifactDocument
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunArtifact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtifactUiState(
    val repo: RepoId,
    val runId: Long,
    val artifact: RunArtifact,
    /** The Markdown files the artifact holds; null until read. */
    val documents: List<ArtifactDocument>? = null,
    val isLoading: Boolean = true,
    val error: ForgeError? = null,
    /** The file picked among several. */
    val selected: String? = null,
) {
    /** The file shown: the only one there is, or the one picked; null while they are listed. */
    val shown: ArtifactDocument? get() = documents?.singleOrNull() ?: documents?.firstOrNull { it.path == selected }
}

/** A run's artifact, read for the report a workflow wrote: GitHub's API can't give the run's summary itself. */
@HiltViewModel(assistedFactory = ArtifactViewModel.Factory::class)
class ArtifactViewModel @AssistedInject constructor(
    @Assisted private val repo: RepoId,
    @Assisted private val runId: Long,
    @Assisted private val artifact: RunArtifact,
    private val actions: ActionsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(repo: RepoId, runId: Long, artifact: RunArtifact): ArtifactViewModel
    }

    private val _state = MutableStateFlow(ArtifactUiState(repo, runId, artifact))
    val state: StateFlow<ArtifactUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = actions.artifactDocuments(repo, artifact)
            _state.update {
                it.copy(
                    isLoading = false,
                    documents = (result as? ForgeResult.Success)?.value ?: it.documents,
                    error = (result as? ForgeResult.Failure)?.error,
                )
            }
        }
    }

    /** Shows [path] of the files listed; null lists them again. */
    fun select(path: String?) = _state.update { it.copy(selected = path) }
}
