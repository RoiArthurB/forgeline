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
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What was just done to a pull request from its conversation: the conversation is read again to show it. */
enum class PullDone {
    MERGED,
    REVIEWED,

    /** Set to merge once its checks pass. */
    SCHEDULED,

    /** No longer set to merge once its checks pass. */
    UNSCHEDULED,

    /** A review of it was dismissed. */
    DISMISSED,

    /** Its branch is being brought up to date with the one it goes into. */
    UPDATED,

    /** A reviewer's suggestion was committed to its branch. */
    SUGGESTION_COMMITTED,
}

/** How a merge is asked for: the way, whether its branch goes with it, and whether it waits for the checks. */
data class MergeChoice(val method: MergeMethod, val deleteBranch: Boolean = false, val whenChecksPass: Boolean = false)

data class PullRequestUiState(
    /** What ran on the latest commit; null until read, and when it couldn't be (the conversation reads without it). */
    val checks: List<Check>? = null,
    /** Null signed out, and until the forge has said. */
    val mergeInfo: MergeInfo? = null,
    val signedIn: Boolean = false,
    val verdicts: Set<ReviewVerdict> = emptySet(),
    val isMerging: Boolean = false,
    val mergeError: ForgeError? = null,
    /** The remarks on lines of its change, each with its replies; null until read. */
    val threads: List<ReviewThread>? = null,
    val resolvesThreads: Boolean = false,
    val dismissesReviews: Boolean = false,
    /** Whether a reviewer's suggestion can be committed from here, on this forge. */
    val appliesSuggestions: Boolean = false,
    /** The thread a reply or a change is on its way to. */
    val busyThread: String? = null,
    /** The thread a reply was just added to, counted like [done]: its field empties. */
    val replied: Pair<String, Int>? = null,
    /** The login the reader is signed in with on its forge; null signed out. Their own remarks can be rewritten. */
    val me: String? = null,
    /** Whether remarks on lines take reactions on this forge. */
    val reactsToThreadComments: Boolean = false,
    /** Whether the reader's own remarks on lines can be rewritten, and taken back, on this forge. */
    val editsThreadComments: Boolean = false,
    val deletesThreadComments: Boolean = false,
    /** The remark just rewritten, by its id, counted like [done]: its editor closes. */
    val threadEdited: Pair<Long, Int>? = null,
    /** Why a reply, a change to a thread or a dismissal was refused, said once. */
    val threadError: ForgeError? = null,
    /** Its branch is being brought up to date; and why that was refused, said once. */
    val isUpdating: Boolean = false,
    val updateError: ForgeError? = null,
    val isSending: Boolean = false,
    val reviewError: ForgeError? = null,
    /** Counts what was done, so that doing the same thing twice is told twice. */
    val done: Pair<PullDone, Int>? = null,
) {
    /** Whether a check still runs: they are then read again now and then, while the conversation is in sight. */
    val checksRunning: Boolean get() = checks?.any { it.state == CheckState.PENDING } == true
}

/** A pull request beside its conversation: its checks, and merging or reviewing it for someone signed in on its forge. */
@HiltViewModel(assistedFactory = PullRequestViewModel.Factory::class)
class PullRequestViewModel @AssistedInject constructor(
    @Assisted private val ref: IssueRef,
    private val repository: PullRequestRepository,
    accounts: AccountRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(ref: IssueRef): PullRequestViewModel
    }

    private val _state = MutableStateFlow(
        PullRequestUiState(
            verdicts = repository.verdicts(ref.repo.forge),
            resolvesThreads = repository.resolvesThreads(ref.repo.forge),
            dismissesReviews = repository.dismissesReviews(ref.repo.forge),
            appliesSuggestions = repository.appliesSuggestions(ref.repo.forge),
            reactsToThreadComments = repository.reactsToThreadComments(ref.repo.forge),
            editsThreadComments = repository.editsThreadComments(ref.repo.forge),
            deletesThreadComments = repository.deletesThreadComments(ref.repo.forge),
        ),
    )
    val state: StateFlow<PullRequestUiState> = _state.asStateFlow()
    private var serial = 0

    init {
        viewModelScope.launch {
            // Read once signed out, and again when an account on the forge appears: only then is merging known.
            accounts.accounts.map { signedIn -> signedIn.firstOrNull { it.forge == ref.repo.forge }?.user?.login }.distinctUntilChanged().collect { me ->
                val signedIn = me != null
                _state.update { it.copy(signedIn = signedIn, me = me, mergeInfo = it.mergeInfo.takeIf { signedIn }) }
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val checks = (repository.checks(ref) as? ForgeResult.Success)?.value
            _state.update { it.copy(checks = checks ?: it.checks) }
        }
        loadThreads()
        if (_state.value.signedIn) {
            viewModelScope.launch {
                val info = (repository.mergeInfo(ref) as? ForgeResult.Success)?.value
                _state.update { it.copy(mergeInfo = info ?: it.mergeInfo) }
            }
        }
    }

    /**
     * Reads the checks again while some still run. Once none does, whether it can be merged is read again too: a check
     * can be what stood in the way.
     */
    fun pollChecks() {
        viewModelScope.launch {
            val checks = (repository.checks(ref) as? ForgeResult.Success)?.value ?: return@launch
            val settled = _state.value.checksRunning && checks.none { it.state == CheckState.PENDING }
            _state.update { it.copy(checks = checks) }
            if (settled && _state.value.signedIn) {
                val info = (repository.mergeInfo(ref) as? ForgeResult.Success)?.value
                _state.update { it.copy(mergeInfo = info ?: it.mergeInfo) }
            }
        }
    }

    fun merge(choice: MergeChoice) = mergeWork(if (choice.whenChecksPass) PullDone.SCHEDULED else PullDone.MERGED) {
        repository.merge(ref, choice.method, choice.deleteBranch, choice.whenChecksPass)
    }

    fun cancelMergeWhenChecksPass() = mergeWork(PullDone.UNSCHEDULED) { repository.cancelMergeWhenChecksPass(ref) }

    fun updateBranch() {
        if (_state.value.isUpdating) return
        _state.update { it.copy(isUpdating = true, updateError = null) }
        viewModelScope.launch {
            when (val result = repository.updateBranch(ref)) {
                is ForgeResult.Failure -> _state.update { it.copy(isUpdating = false, updateError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { it.copy(isUpdating = false, done = PullDone.UPDATED to ++serial) }
                    refresh()
                }
            }
        }
    }

    fun updateErrorShown() = _state.update { it.copy(updateError = null) }

    private fun loadThreads() {
        viewModelScope.launch {
            val threads = (repository.threads(ref) as? ForgeResult.Success)?.value
            _state.update { it.copy(threads = threads ?: it.threads) }
        }
    }

    fun reply(thread: ReviewThread, body: String) = threadWork(thread.id, onDone = { it.copy(replied = thread.id to ++serial) }) {
        repository.reply(ref, thread, body.trim())
    }

    fun setResolved(thread: ReviewThread, resolved: Boolean) = threadWork(thread.id) { repository.setResolved(ref, thread, resolved) }

    /** Rewrites the reader's own [comment] of [thread]; anyone else's is left as it is. */
    fun editThreadComment(thread: ReviewThread, comment: ThreadComment, body: String) {
        if (!wrote(comment) || body.isBlank()) return
        threadWork(thread.id, onDone = { it.copy(threadEdited = comment.id to ++serial) }) { repository.editThreadComment(ref, thread, comment, body.trim()) }
    }

    /** Takes back the reader's own [comment] of [thread]. */
    fun deleteThreadComment(thread: ReviewThread, comment: ThreadComment) {
        if (!wrote(comment) || !_state.value.deletesThreadComments) return
        threadWork(thread.id) { repository.deleteThreadComment(ref, thread, comment) }
    }

    /** Gives [reaction] to [comment] of [thread], or takes it back: the counts the forge answers replace the old. */
    fun toggleThreadReaction(thread: ReviewThread, comment: ThreadComment, reaction: Reaction) {
        if (!_state.value.signedIn) return
        viewModelScope.launch {
            when (val result = repository.toggleThreadReaction(ref, thread, comment, reaction)) {
                is ForgeResult.Failure -> _state.update { it.copy(threadError = result.error) }
                is ForgeResult.Success -> _state.update { state ->
                    state.copy(
                        threads = state.threads?.map { t ->
                            if (t.id != thread.id) t else t.copy(comments = t.comments.map { if (it.id == comment.id) it.copy(reactions = result.value) else it })
                        },
                    )
                }
            }
        }
    }

    private fun wrote(comment: ThreadComment): Boolean = _state.value.me?.let { comment.author?.login.equals(it, ignoreCase = true) } == true

    /**
     * Commits [suggestion] to the pull request's branch in place of the lines [thread] is on, as GitHub's own button
     * words it; refused ([ForgeError.Conflict]) where those lines have changed since.
     */
    fun applySuggestion(thread: ReviewThread, suggestion: List<String>) =
        threadWork(thread.id, onDone = { it.copy(done = PullDone.SUGGESTION_COMMITTED to ++serial) }) {
            repository.applySuggestion(ref, thread, suggestion, SUGGESTION_MESSAGE)
        }

    /** Dismisses a review, saying why: the conversation is read again to show it ([PullDone.DISMISSED]). */
    fun dismissReview(reviewId: Long, message: String) = threadWork("review-$reviewId", onDone = { it.copy(done = PullDone.DISMISSED to ++serial) }) {
        repository.dismissReview(ref, reviewId, message.trim())
    }

    fun threadErrorShown() = _state.update { it.copy(threadError = null) }

    /** One change to the threads at a time; once the forge took it, they are read again. */
    private fun threadWork(id: String, onDone: (PullRequestUiState) -> PullRequestUiState = { it }, ask: suspend () -> ForgeResult<Unit>) {
        if (_state.value.busyThread != null) return
        _state.update { it.copy(busyThread = id, threadError = null) }
        viewModelScope.launch {
            when (val result = ask()) {
                is ForgeResult.Failure -> _state.update { it.copy(busyThread = null, threadError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { onDone(it.copy(busyThread = null)) }
                    loadThreads()
                }
            }
        }
    }

    /** One merge, or change to one, at a time; once the forge took it, it is said ([done]) and read again. */
    private fun mergeWork(done: PullDone, ask: suspend () -> ForgeResult<Unit>) {
        if (_state.value.isMerging) return
        _state.update { it.copy(isMerging = true, mergeError = null) }
        viewModelScope.launch {
            when (val result = ask()) {
                is ForgeResult.Failure -> _state.update { it.copy(isMerging = false, mergeError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { it.copy(isMerging = false, done = done to ++serial) }
                    refresh()
                }
            }
        }
    }

    fun mergeErrorShown() = _state.update { it.copy(mergeError = null) }

    /** A review from the conversation: a verdict and what it says, without remarks on lines (those are written on the change). */
    fun submitReview(verdict: ReviewVerdict, body: String) {
        if (_state.value.isSending) return
        _state.update { it.copy(isSending = true, reviewError = null) }
        viewModelScope.launch {
            when (val result = repository.review(ref, verdict, body.trim())) {
                is ForgeResult.Failure -> _state.update { it.copy(isSending = false, reviewError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { it.copy(isSending = false, done = PullDone.REVIEWED to ++serial) }
                    // An approval can be what a merge was waiting for.
                    refresh()
                }
            }
        }
    }

    fun reviewErrorShown() = _state.update { it.copy(reviewError = null) }

    companion object {
        /** How often running checks are read again: GitHub's own page follows them about as often. */
        const val POLL_MILLIS = 30_000L

        /** A commit message is the repository's, not the reader's: it is in English, whatever the app's language. */
        private const val SUGGESTION_MESSAGE = "Apply suggestion from code review"
    }
}
