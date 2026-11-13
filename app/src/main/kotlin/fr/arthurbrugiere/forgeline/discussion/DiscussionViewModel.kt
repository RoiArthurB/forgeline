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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

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
    /** The comments and replies past the first are being read, after the first are shown. */
    val readingRest: Boolean = false,
    /** Where the list is to go, once: the id of a comment or reply, or [DiscussionViewModel.END]. */
    val scrollTo: String? = null,
    /** Deleted from here: the page has nothing left to show, and goes back. */
    val deleted: Boolean = false,
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

    /** Reading the comments and replies past the first ones; a new read of the discussion starts it over. */
    private var rest: Job? = null
    private var refreshing: Job? = null
    private var openedAtUnread = false

    /**
     * Once the whole discussion is read, sends the list to the first comment or reply written since [lastRead]; to the
     * end when the forge doesn't say when it was last read, or nothing came since but the discussion itself.
     */
    fun openAtUnread(lastRead: Instant?) {
        if (openedAtUnread) return
        openedAtUnread = true
        viewModelScope.launch {
            refreshing?.join()
            rest?.join()
            val discussion = state.value.discussion ?: return@launch
            // Started since: all of it is new, and it starts at the top.
            if (lastRead != null && discussion.summary.createdAt?.isAfter(lastRead) == true) return@launch
            val firstNew = lastRead?.let { discussion.inReadingOrder().firstOrNull { it.createdAt?.isAfter(lastRead) == true } }
            _state.update { it.copy(scrollTo = firstNew?.id ?: END) }
        }
    }

    fun scrolled() = _state.update { it.copy(scrollTo = null) }
    private var reads = 0

    /**
     * Reads the discussion again. What was read stays on screen meanwhile, and when the forge can't be asked. The first
     * comments show at once; those after them, and replies past the first, are read next and added as they come.
     */
    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        refreshing = viewModelScope.launch {
            val result = repos.discussion(repo, number)
            if (result is ForgeResult.Success) rest?.cancel()
            _state.update {
                when (result) {
                    // What was read past the first page stays until it is read again: the list doesn't shrink under the reader.
                    is ForgeResult.Success -> it.copy(discussion = result.value.keeping(it.discussion), summary = result.value.summary, isLoading = false)
                    is ForgeResult.Failure -> it.copy(isLoading = false, error = result.error)
                }
            }
            if (result is ForgeResult.Success) rest = viewModelScope.launch { readRest(result.value) }
        }
    }

    /**
     * Reads the comments after [read]'s first ones, then the replies past each comment's first ones, a page at a time.
     * Once all are read, what was kept from before and not seen again (deleted since) goes.
     */
    private suspend fun readRest(read: Discussion) {
        if (read.commentsAfter == null && read.comments.none { it.repliesAfter != null }) return
        // A read called off finishes after the next began: it leaves that one's saying so alone.
        val mine = ++reads
        _state.update { it.copy(readingRest = true) }
        try {
            val seen = read.comments.mapTo(HashSet()) { it.id }
            var after = read.commentsAfter
            var pages = 0
            while (after != null && pages++ < MAX_PAGES) {
                val discussion = state.value.discussion ?: return
                val page = (repos.discussionComments(repo, discussion, after) as? ForgeResult.Success)?.value ?: return
                page.items.mapTo(seen) { it.id }
                change { it.copy(comments = it.comments.merging(page.items), commentsAfter = page.next) }
                after = page.next
            }
            if (after == null) change { it.copy(comments = it.comments.filter { comment -> comment.id in seen }) }
            state.value.discussion?.comments.orEmpty().filter { it.repliesAfter != null }.forEach { readReplies(it) }
        } finally {
            if (reads == mine) _state.update { it.copy(readingRest = false) }
        }
    }

    private suspend fun readReplies(comment: DiscussionComment) {
        val seen = comment.replies.mapTo(HashSet()) { it.id }
        var after = comment.repliesAfter
        var pages = 0
        while (after != null && pages++ < MAX_PAGES) {
            val page = (repos.discussionReplies(repo, comment.id, after) as? ForgeResult.Success)?.value ?: return
            page.items.mapTo(seen) { it.id }
            val next = page.next
            changeComment(comment.id) { it.copy(replies = it.replies.merging(page.items), repliesAfter = next) }
            after = next
        }
        if (after == null) changeComment(comment.id) { it.copy(replies = it.replies.filter { reply -> reply.id in seen }) }
    }

    private fun change(change: (Discussion) -> Discussion) = _state.update { it.copy(discussion = it.discussion?.let(change)) }

    private fun changeComment(id: String, change: (DiscussionComment) -> DiscussionComment) = change { discussion ->
        discussion.copy(comments = discussion.comments.map { if (it.id == id) change(it) else it })
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

    /** Rewrites [comment], one of the discussion's comments or replies; read again once it went through. */
    fun editComment(comment: DiscussionComment, body: String) = write("edit:${comment.id}") { repos.editDiscussionComment(repo, comment.id, body.trim()) }

    fun deleteComment(comment: DiscussionComment) = write("delete:${comment.id}") { repos.deleteDiscussionComment(repo, comment.id) }

    /** Rewrites the discussion's title and text. */
    fun editDiscussion(title: String, body: String) {
        val discussion = state.value.discussion ?: return
        write(EDIT_DISCUSSION) { repos.editDiscussion(repo, discussion, title.trim(), body.trim()) }
    }

    /** Deletes the discussion with its comments: nothing is read again, the page goes. */
    fun deleteDiscussion() {
        val discussion = state.value.discussion ?: return
        if (state.value.sending != null) return
        _state.update { it.copy(sending = DELETE_DISCUSSION, sendError = null) }
        viewModelScope.launch {
            when (val result = repos.deleteDiscussion(repo, discussion)) {
                is ForgeResult.Success -> _state.update { it.copy(sending = null, deleted = true) }
                is ForgeResult.Failure -> _state.update { it.copy(sending = null, sendError = result.error) }
            }
        }
    }

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

        /** What [DiscussionUiState.scrollTo] holds for the end of the discussion, where the reader's turn is. */
        const val END = "end"

        /** How many pages of comments, and of each comment's replies, are read past the first: the forge's site has more. */
        const val MAX_PAGES = 20

        /** What [DiscussionUiState.sending] holds while the discussion's title and text are rewritten, or it is deleted. */
        const val EDIT_DISCUSSION = "edit:discussion"
        const val DELETE_DISCUSSION = "delete:discussion"
    }
}

/**
 * This discussion as just read, keeping what [before] read past its first page (comments, and replies past each
 * comment's first), until that is read again: where the forge says nothing was left out, nothing is kept.
 */
internal fun Discussion.keeping(before: Discussion?): Discussion {
    if (before == null) return this
    val earlier = before.comments.associateBy { it.id }
    val firstIds = comments.mapTo(HashSet()) { it.id }
    val first = comments.map { comment -> earlier[comment.id]?.let { comment.keepingReplies(it) } ?: comment }
    return copy(comments = if (commentsAfter == null) first else first + before.comments.filter { it.id !in firstIds })
}

private fun DiscussionComment.keepingReplies(before: DiscussionComment): DiscussionComment {
    if (repliesAfter == null) return this
    val ids = replies.mapTo(HashSet()) { it.id }
    return copy(replies = replies + before.replies.filter { it.id !in ids })
}

/** These comments with [page]'s: one already here takes its new version in place (keeping replies read past its first), a new one goes at the end. */
internal fun List<DiscussionComment>.merging(page: List<DiscussionComment>): List<DiscussionComment> {
    val fresh = page.associateBy { it.id }
    val here = mapTo(HashSet()) { it.id }
    return map { old -> fresh[old.id]?.keepingReplies(old) ?: old } + page.filter { it.id !in here }
}

/** Its comments and replies in the order they are read: each comment, then the replies it drew. */
internal fun Discussion.inReadingOrder(): List<DiscussionComment> = comments.flatMap { listOf(it) + it.replies }
