package fr.arthurbrugiere.forgeline.pull

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.pull.PullRequestRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.DiffHunk
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whose change is read: a pull request's, or one commit's. */
sealed interface ChangesTarget {
    val repo: RepoId

    data class Pull(val ref: IssueRef) : ChangesTarget {
        override val repo: RepoId get() = ref.repo
    }

    data class OfCommit(override val repo: RepoId, val sha: String) : ChangesTarget
}

/** A changed file with its change read into hunks; none for a file that isn't text or whose change the forge left out. */
data class FileDiff(val file: ChangedFile, val hunks: List<DiffHunk>) {
    val lines: Int get() = hunks.sumOf { it.lines.size }

    /** A change long enough to start folded: opening a pull request shouldn't mean scrolling past a lock file. */
    val isLarge: Boolean get() = lines > LARGE_LINES

    companion object {
        const val LARGE_LINES = 300
    }
}

/** The line a remark is being written on. */

/**
 * What a remark is being written about: [line] of the file at [path], or the whole file when it is null. [above] are the
 * lines it can reach up over, nearest first: those of its hunk above it, on its side of the change.
 */
data class LineTarget(val path: String, val line: DiffLine?, val above: List<DiffLine> = emptyList())

data class ChangesUiState(
    val target: ChangesTarget,
    /** Null until the first page is in. */
    val files: List<FileDiff>? = null,
    /** The commit itself, when the change is one commit's. */
    val commit: Commit? = null,
    val nextPage: Int? = null,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ForgeError? = null,
    /** The files folded or unfolded by hand, against how they start ([FileDiff.isLarge] ones folded). */
    val toggled: Set<String> = emptySet(),
    /** Whether a review can be written: a pull request's change, by someone signed in on its forge. */
    val canReview: Boolean = false,
    val verdicts: Set<ReviewVerdict> = emptySet(),
    /** Whether a remark can cover several lines, and be on a whole file, on this forge. */
    val remarksOnRanges: Boolean = false,
    val remarksOnFiles: Boolean = false,
    /** The remarks on lines written so far; they go with the review. */
    val comments: List<LineComment> = emptyList(),
    val drafting: LineTarget? = null,
    val isReviewOpen: Boolean = false,
    val isSending: Boolean = false,
    val reviewError: ForgeError? = null,
    /** Set once a review went through, until the screen has said so. */
    val reviewSent: Boolean = false,
) {
    fun isExpanded(diff: FileDiff): Boolean = diff.isLarge == (diff.file.path in toggled)
}

@HiltViewModel(assistedFactory = ChangesViewModel.Factory::class)
class ChangesViewModel @AssistedInject constructor(
    @Assisted private val target: ChangesTarget,
    private val repository: PullRequestRepository,
    private val accounts: AccountRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(target: ChangesTarget): ChangesViewModel
    }

    private val _state = MutableStateFlow(
        ChangesUiState(
            target,
            verdicts = repository.verdicts(target.repo.forge),
            remarksOnRanges = repository.commentsOnRanges(target.repo.forge),
            remarksOnFiles = repository.commentsOnFiles(target.repo.forge),
        ),
    )
    val state: StateFlow<ChangesUiState> = _state.asStateFlow()

    init {
        refresh()
        if (target is ChangesTarget.Pull) {
            viewModelScope.launch {
                accounts.accounts.collect { signedIn -> _state.update { it.copy(canReview = signedIn.any { account -> account.forge == target.repo.forge }) } }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (target) {
                is ChangesTarget.Pull -> when (val result = repository.files(target.ref)) {
                    is ForgeResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error) }
                    is ForgeResult.Success -> _state.update {
                        it.copy(isLoading = false, files = result.value.files.map(::read), nextPage = result.value.nextPage)
                    }
                }
                is ChangesTarget.OfCommit -> when (val result = repository.commit(target.repo, target.sha)) {
                    is ForgeResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error) }
                    is ForgeResult.Success -> _state.update {
                        it.copy(isLoading = false, files = result.value.files.map(::read), commit = result.value.commit, nextPage = null)
                    }
                }
            }
        }
    }

    /** Reads the next page of files, when the list's end comes in sight. */
    fun loadMore() {
        val current = _state.value
        val page = current.nextPage ?: return
        if (current.isLoadingMore || target !is ChangesTarget.Pull) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            when (val result = repository.files(target.ref, page)) {
                // The end stays where it is: it is asked again when it next comes in sight.
                is ForgeResult.Failure -> _state.update { it.copy(isLoadingMore = false, error = result.error) }
                is ForgeResult.Success -> _state.update {
                    it.copy(isLoadingMore = false, files = it.files.orEmpty() + result.value.files.map(::read), nextPage = result.value.nextPage)
                }
            }
        }
    }

    fun errorShown() = _state.update { it.copy(error = null) }

    /** Folds the file at [path], or unfolds it. */
    fun toggle(path: String) = _state.update { it.copy(toggled = if (path in it.toggled) it.toggled - path else it.toggled + path) }

    /** Starts a remark on [line] of the file at [path]. */
    fun startComment(path: String, line: DiffLine) {
        val state = _state.value
        if (!state.canReview) return
        _state.update { it.copy(drafting = LineTarget(path, line, if (state.remarksOnRanges) linesAbove(path, line) else emptyList())) }
    }

    /** Starts a remark on the whole file at [path], where the forge takes one. */
    fun startFileComment(path: String) {
        val state = _state.value
        if (state.canReview && state.remarksOnFiles) _state.update { it.copy(drafting = LineTarget(path, null)) }
    }

    /** The lines of [line]'s hunk above it, nearest first, that are on its side: the file it is in has them too. */
    private fun linesAbove(path: String, line: DiffLine): List<DiffLine> {
        val hunk = _state.value.files.orEmpty().firstOrNull { it.file.path == path }?.hunks?.firstOrNull { line in it.lines } ?: return emptyList()
        val other = if (line.kind == DiffLineKind.REMOVED) DiffLineKind.ADDED else DiffLineKind.REMOVED
        return hunk.lines.subList(0, hunk.lines.indexOf(line)).filter { it.kind != other }.reversed()
    }

    fun cancelComment() = _state.update { it.copy(drafting = null) }

    /**
     * Keeps what was written about the line being remarked on, from [start] when it covers lines above it, or about the
     * whole file: it is sent with the review, not before.
     */
    fun addComment(body: String, start: DiffLine? = null) {
        val drafting = _state.value.drafting ?: return
        if (body.isBlank()) return
        val file = _state.value.files.orEmpty().firstOrNull { it.file.path == drafting.path }?.file
        val line = drafting.line
        val comment = if (line == null) {
            LineComment(drafting.path, oldLine = null, newLine = null, body = body.trim(), previousPath = file?.previousPath, onFile = true)
        } else {
            val from = start?.takeIf { it in drafting.above }
            LineComment(drafting.path, line.oldNumber, line.newNumber, body.trim(), file?.previousPath, startOldLine = from?.oldNumber, startNewLine = from?.newNumber)
        }
        _state.update { it.copy(drafting = null, comments = it.comments + comment) }
    }

    fun removeComment(comment: LineComment) = _state.update { it.copy(comments = it.comments - comment) }

    fun openReview() = _state.update { it.copy(isReviewOpen = true, reviewError = null) }

    fun closeReview() = _state.update { if (it.isSending) it else it.copy(isReviewOpen = false) }

    fun submitReview(verdict: ReviewVerdict, body: String) {
        val current = _state.value
        if (current.isSending || target !is ChangesTarget.Pull) return
        _state.update { it.copy(isSending = true, reviewError = null) }
        viewModelScope.launch {
            when (val result = repository.review(target.ref, verdict, body.trim(), current.comments)) {
                // What was written stays, to be sent again.
                is ForgeResult.Failure -> _state.update { it.copy(isSending = false, reviewError = result.error) }
                is ForgeResult.Success -> _state.update { it.copy(isSending = false, isReviewOpen = false, comments = emptyList(), reviewSent = true) }
            }
        }
    }

    fun reviewSentShown() = _state.update { it.copy(reviewSent = false) }

    private fun read(file: ChangedFile) = FileDiff(file, file.patch?.let(::parsePatch).orEmpty())
}
