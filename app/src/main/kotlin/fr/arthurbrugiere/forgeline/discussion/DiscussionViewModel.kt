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
import fr.arthurbrugiere.forgeline.core.model.DiscussionComment
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
    /** What is being sent: [DiscussionViewModel.COMMENT] for a comment on the discussion, a comment's id for a reply under it, `answer:` and its id for an answer picked or taken back. */
    val sending: String? = null,
    /** What was last sent and went through, with a count that tells two alike apart: its field is emptied. */
    val sent: Pair<String, Long>? = null,
    /** Why the last comment, reply or answer didn't go through. */
    val sendError: ForgeError? = null,
) {
    /** Whether the reader may comment: the discussion is read (only signed in) and not locked. */
    val canComment: Boolean get() = discussion?.id != null && !discussion.locked

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

    private var serial = 0L

    /** Comments on the discussion, or replies under [replyTo]; once it went through, the discussion is read again. */
    fun send(body: String, replyTo: DiscussionComment? = null) {
        val discussion = state.value.discussion ?: return
        write(replyTo?.id ?: COMMENT) { repos.commentOnDiscussion(repo, discussion, body.trim(), replyTo?.id) }
    }

    /** Picks [comment] as the discussion's answer, or takes that back. */
    fun setAnswer(comment: DiscussionComment, answer: Boolean) = write("answer:${comment.id}") { repos.setDiscussionAnswer(repo, comment.id, answer) }

    fun sendErrorShown() = _state.update { it.copy(sendError = null) }

    /** One write at a time. */
    private fun write(key: String, call: suspend () -> ForgeResult<Unit>) {
        if (state.value.sending != null) return
        _state.update { it.copy(sending = key, sendError = null) }
        viewModelScope.launch {
            when (val result = call()) {
                is ForgeResult.Success -> {
                    _state.update { it.copy(sending = null, sent = key to ++serial) }
                    refresh()
                }
                is ForgeResult.Failure -> _state.update { it.copy(sending = null, sendError = result.error) }
            }
        }
    }

    companion object {
        /** What [DiscussionUiState.sending] holds while a comment on the discussion itself is sent. */
        const val COMMENT = "comment"
    }
}
