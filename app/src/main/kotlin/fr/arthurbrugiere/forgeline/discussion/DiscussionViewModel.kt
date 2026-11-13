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
import fr.arthurbrugiere.forgeline.core.model.Reaction
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
    /** A reaction couldn't be given or taken back. */
    val reactionError: ForgeError? = null,
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

    /** What is being reacted to, by its id: one change at a time on each. */
    private val reacting = mutableSetOf<String>()

    /**
     * Gives [reaction] to the discussion's own text or one of its comments or replies, by its id ([subjectId]); takes
     * it back when the reader had given it. The counts shown are the forge's, once it has answered.
     */
    fun react(subjectId: String, reaction: Reaction) {
        val given = state.value.discussion?.reactionsOf(subjectId) ?: return
        if (!reacting.add(subjectId)) return
        viewModelScope.launch {
            when (val result = repos.reactInDiscussion(repo, subjectId, reaction, given.gives(reaction))) {
                is ForgeResult.Failure -> _state.update { it.copy(reactionError = result.error) }
                is ForgeResult.Success -> _state.update { it.copy(discussion = it.discussion?.withReactions(subjectId, result.value)) }
            }
            reacting -= subjectId
        }
    }

    fun reactionErrorShown() = _state.update { it.copy(reactionError = null) }

    /** What is being upvoted, by its id: one change at a time on each. */
    private val upvoting = mutableSetOf<String>()

    /** Upvotes the discussion or one of its comments by its id, or takes that back when the reader had upvoted it. */
    fun upvote(subjectId: String) {
        val upvotes = state.value.discussion?.upvotesOf(subjectId) ?: return
        if (!upvoting.add(subjectId)) return
        viewModelScope.launch {
            when (val result = repos.upvoteInDiscussion(repo, subjectId, !upvotes.given)) {
                // Said as a reaction is: both are a quick answer to someone's words.
                is ForgeResult.Failure -> _state.update { it.copy(reactionError = result.error) }
                is ForgeResult.Success -> _state.update { state ->
                    val discussion = state.discussion?.withUpvotes(subjectId, result.value)
                    state.copy(discussion = discussion, summary = discussion?.summary ?: state.summary)
                }
            }
            upvoting -= subjectId
        }
    }

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
