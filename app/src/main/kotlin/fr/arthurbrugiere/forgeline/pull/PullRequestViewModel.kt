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
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What was just done to a pull request from its conversation: the conversation is read again to show it. */
enum class PullDone { MERGED, REVIEWED }

data class PullRequestUiState(
    /** What ran on the latest commit; null until read, and when it couldn't be (the conversation reads without it). */
    val checks: List<Check>? = null,
    /** Null signed out, and until the forge has said. */
    val mergeInfo: MergeInfo? = null,
    val signedIn: Boolean = false,
    val verdicts: Set<ReviewVerdict> = emptySet(),
    val isMerging: Boolean = false,
    val mergeError: ForgeError? = null,
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

    private val _state = MutableStateFlow(PullRequestUiState(verdicts = repository.verdicts(ref.repo.forge)))
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
        if (_state.value.signedIn) {
            viewModelScope.launch {
                val info = (repository.mergeInfo(ref) as? ForgeResult.Success)?.value
                _state.update { it.copy(mergeInfo = info ?: it.mergeInfo) }
            }
        }
    }

    fun merge(method: MergeMethod) {
        if (_state.value.isMerging) return
        _state.update { it.copy(isMerging = true, mergeError = null) }
        viewModelScope.launch {
            when (val result = repository.merge(ref, method)) {
                is ForgeResult.Failure -> _state.update { it.copy(isMerging = false, mergeError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { it.copy(isMerging = false, done = PullDone.MERGED to ++serial) }
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
