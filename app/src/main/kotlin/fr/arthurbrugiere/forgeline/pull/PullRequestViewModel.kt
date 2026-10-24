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
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
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
    /** The thread a reply or a change is on its way to. */
    val busyThread: String? = null,
    /** The thread a reply was just added to, counted like [done]: its field empties. */
    val replied: Pair<String, Int>? = null,
    /** Why a reply, a change to a thread or a dismissal was refused, said once. */
    val threadError: ForgeError? = null,
    /** Its branch is being brought up to date; and why that was refused, said once. */
    val isUpdating: Boolean = false,
    val updateError: ForgeError? = null,
    val isSending: Boolean = false,
    val reviewError: ForgeError? = null,
    /** Counts what was done, so that doing the same thing twice is told twice. */
    val done: Pair<PullDone, Int>? = null,
)

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
        ),
    )
    val state: StateFlow<PullRequestUiState> = _state.asStateFlow()
    private var serial = 0

    init {
        viewModelScope.launch {
            // Read once signed out, and again when an account on the forge appears: only then is merging known.
            accounts.accounts.map { signedIn -> signedIn.any { it.forge == ref.repo.forge } }.distinctUntilChanged().collect { signedIn ->
                _state.update { it.copy(signedIn = signedIn, mergeInfo = it.mergeInfo.takeIf { signedIn }) }
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
}
