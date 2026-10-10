package fr.arthurbrugiere.forgeline.pull

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.pull.PullRequestRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.file.FileTarget
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A run of lines one commit last left as they are: [lines] from [startLine] on. */
data class BlameBlock(val commit: Commit, val startLine: Int, val lines: List<String>)

data class BlameUiState(val target: FileTarget, val blocks: Loadable<List<BlameBlock>> = Loadable.Loading)

/** The file read once and the forge asked who last changed what, then the two put together. */
@HiltViewModel(assistedFactory = BlameViewModel.Factory::class)
class BlameViewModel @AssistedInject constructor(
    @Assisted private val target: FileTarget,
    private val repos: RepoRepository,
    private val pulls: PullRequestRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(target: FileTarget): BlameViewModel
    }

    private val _state = MutableStateFlow(BlameUiState(target))
    val state: StateFlow<BlameUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _state.update { it.copy(blocks = Loadable.Loading) }
        viewModelScope.launch {
            val text = async { repos.fileText(target.id, target.path, target.ref) }
            val blocks = when (val blame = pulls.blame(target.id, target.ref, target.path)) {
                is ForgeResult.Failure -> Loadable.Failed(blame.error)
                is ForgeResult.Success -> when (val file = text.await()) {
                    is ForgeResult.Failure -> Loadable.Failed(file.error)
                    is ForgeResult.Success -> Loadable.Loaded(blocks(file.value, blame.value))
                }
            }
            _state.update { it.copy(blocks = blocks) }
        }
    }

    companion object {
        /** Each range of [blame] with the lines of [text] it covers; a range past the end of the file holds what there is. */
        fun blocks(text: String, blame: List<BlameRange>): List<BlameBlock> {
            // The line break that ends a file is not one more line.
            val lines = text.removeSuffix("\n").split('\n')
            return blame.mapNotNull { range ->
                val from = (range.startLine - 1).coerceAtLeast(0)
                val to = range.endLine.coerceAtMost(lines.size)
                if (from >= to) null else BlameBlock(range.commit, range.startLine, lines.subList(from, to))
            }
        }
    }
}
