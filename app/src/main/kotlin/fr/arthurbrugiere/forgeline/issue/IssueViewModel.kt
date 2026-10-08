package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.ui.PickedPicture
import fr.arthurbrugiere.forgeline.core.model.AttachmentRule
import fr.arthurbrugiere.forgeline.core.model.Reaction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import java.time.LocalDate
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the conversation's list is asked to go. */
sealed interface ScrollTarget {
    /** The end of the conversation, where the reader's turn is. */
    data object End : ScrollTarget

    /** One entry of the timeline, by its place in [IssueUiState.items]. */
    data class Item(val index: Int) : ScrollTarget
}

data class IssueUiState(
    val ref: IssueRef,
    val issue: IssueDetails? = null,
    val items: List<TimelineItem> = emptyList(),
    val nextPage: Int? = null,
    /** The last page of the conversation, where the forge says. */
    val lastPage: Int? = null,
    /** Where the list goes next, asked once: the screen moves there and says so. */
    val scrollTo: ScrollTarget? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: ForgeError? = null,
    /** The comment being written; kept while it is sent and when sending fails. */
    val draft: String = "",
    /** Counts the times [draft] was put there by something other than typing: the cursor then goes after it. */
    val draftPlaced: Int = 0,
    val isCommenting: Boolean = false,
    val commentError: ForgeError? = null,
    /** A comment was posted but more of the conversation is still to load, so it can't be shown in its place yet. */
    val commentPostedOutOfSight: Boolean = false,
    /** Whether the reader may close or reopen this conversation: its author, or someone who manages the repository. */
    val canChangeState: Boolean = false,
    /** What the reader may do in the conversation's repository beyond reading it. */
    val access: RepoAccess = RepoAccess.NONE,
    val isChangingState: Boolean = false,
    val stateError: ForgeError? = null,
    /** What the forge's API can do to a conversation; [actions] is what the reader may do of it here. */
    val supported: Set<ConversationAction> = emptySet(),
    val manage: ManageUiState = ManageUiState(),
    /** The login the reader is signed in with on this conversation's forge; null signed out. */
    val me: String? = null,
    /** The comment being rewritten: [draft] holds its text, and sending replaces it instead of adding one. */
    val editing: Long? = null,
    /** A comment couldn't be deleted. */
    val deleteError: ForgeError? = null,
    /** A reaction couldn't be given or taken back. */
    val reactionError: ForgeError? = null,
    /** Who the forge's API lets put a picture in a comment. */
    val attachments: AttachmentRule = AttachmentRule.NOBODY,
    val isAttaching: Boolean = false,
    /** A picture couldn't be attached: 413 for one too large, whoever found it so. */
    val attachError: ForgeError? = null,
    /** Where the issue went once transferred: this screen gives way to it. */
    val movedTo: IssueRef? = null,
    /** The issue was deleted: there is nothing left to show. */
    val deleted: Boolean = false,
) {
    /** The comment being rewritten is among those shown, so it is rewritten where it stands, not in the reader's turn. */
    val isEditingInPlace: Boolean get() = editing != null && items.any { it is TimelineItem.Comment && it.id == editing }

    /** One's own words can be rewritten; nobody else's, whatever the forge would allow. */
    fun canEdit(comment: TimelineItem.Comment): Boolean = wrote(comment.author)

    /** One's own words can be taken back, and whoever can write to the repository may remove anyone's. */
    fun canDelete(comment: TimelineItem.Comment): Boolean = wrote(comment.author) || (me != null && access >= RepoAccess.WRITE)

    /** The title and the description are their author's to change, and whoever can write to the repository's. */
    val canEditIssue: Boolean get() = issue != null && (wrote(issue.author) || (me != null && access >= RepoAccess.WRITE))

    /** Whether the reader may put a picture in a comment here. */
    val canAttach: Boolean
        get() = when (attachments) {
            AttachmentRule.NOBODY -> false
            AttachmentRule.ANYONE -> me != null
            AttachmentRule.AUTHOR_OR_WRITER -> issue != null && (wrote(issue.author) || (me != null && access >= RepoAccess.WRITE))
        }

    private fun wrote(author: ForgeUser?): Boolean = me != null && author != null && author.login.equals(me, ignoreCase = true)

    /** What the reader may do to this conversation: the forge can, their role allows it, and it applies. */
    val actions: Set<ConversationAction>
        get() {
            val issue = issue ?: return emptySet()
            // Pinning, transferring and deleting are for issues; a pull request is closed without a reason.
            val isIssue = issue.pullRequest == null
            return supported.filterTo(mutableSetOf()) { action ->
                when (action) {
                    ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE -> access >= RepoAccess.TRIAGE
                    ConversationAction.CLOSE_REASON -> isIssue && issue.state == IssueState.OPEN && canChangeState
                    ConversationAction.LOCK -> access >= RepoAccess.WRITE
                    ConversationAction.PIN, ConversationAction.TRANSFER -> isIssue && access >= RepoAccess.WRITE
                    ConversationAction.DELETE -> isIssue && access == RepoAccess.ADMIN
                    ConversationAction.DUE_DATE, ConversationAction.DEPENDENCIES -> access >= RepoAccess.TRIAGE
                    // Whoever the repository lets track time: the forge already took it away from anyone else.
                    ConversationAction.TIME_TRACKING -> true
                }
            }
        }
}

/** What the sheet that manages a conversation needs: the choices the repository offers, and how the last change went. */
data class ManageUiState(
    val labels: Loadable<List<Label>> = Loadable.Idle,
    val assignable: Loadable<List<ForgeUser>> = Loadable.Idle,
    val milestones: Loadable<List<Milestone>> = Loadable.Idle,
    val tracking: Loadable<TimeTracking> = Loadable.Idle,
    val dependencies: Loadable<List<LinkedIssue>> = Loadable.Idle,
    /** Null until the forge has said. */
    val pinned: Boolean? = null,
    val isWorking: Boolean = false,
    val error: ForgeError? = null,
    /** A change went through: the sheet closes on what it changed. */
    val done: Boolean = false,
)

@HiltViewModel(assistedFactory = IssueViewModel.Factory::class)
class IssueViewModel @AssistedInject constructor(
    @Assisted private val ref: IssueRef,
    private val repository: IssueRepository,
    private val savedState: SavedStateHandle,
    private val drafts: IssueDrafts,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(ref: IssueRef): IssueViewModel
    }

    private val _state = MutableStateFlow(
        repository.cached(ref).let { cached ->
            // The draft outlives the screen and the app being stopped: a comment half written is not to be typed again.
            val draft = savedState.get<String>(DRAFT_KEY)?.takeIf { it.isNotEmpty() } ?: drafts.comment(ref)
            IssueUiState(ref, cached?.issue, cached?.firstPage?.items.orEmpty(), cached?.firstPage?.nextPage, cached?.firstPage?.lastPage, draft = draft)
        },
    )
    val state: StateFlow<IssueUiState> = _state.asStateFlow()

    private var refreshing: Job? = null
    private val paging = Mutex()
    private var openedAtUnread = false

    /** The comment being written when another one started being rewritten: it comes back afterwards. */
    private var setAside = ""

    init {
        // Not seen this session: the copy kept on disk shows while the forge answers, unless the answer comes first.
        if (_state.value.issue == null) {
            viewModelScope.launch {
                val stored = repository.stored(ref) ?: return@launch
                _state.update { state ->
                    if (state.issue != null || state.items.isNotEmpty()) {
                        state
                    } else {
                        state.copy(issue = stored.issue, items = stored.firstPage?.items.orEmpty(), nextPage = stored.firstPage?.nextPage, lastPage = stored.firstPage?.lastPage)
                    }
                }
                checkPermissions()
            }
        }
        checkPermissions()
        refresh()
        // Written in an earlier launch: it comes back, unless the reader has started writing or rewriting meanwhile.
        if (_state.value.draft.isEmpty()) {
            viewModelScope.launch {
                val kept = drafts.storedComment(ref)
                if (kept.isEmpty()) return@launch
                _state.update { if (it.draft.isEmpty() && it.editing == null) it.copy(draft = kept, draftPlaced = it.draftPlaced + 1) else it }
            }
        }
        // Changed from elsewhere in the app (its title and text, from the form that edits them): what is kept is read again.
        viewModelScope.launch {
            repository.changed.collect { changed ->
                if (changed == ref) repository.cached(ref)?.issue?.let { issue -> _state.update { it.copy(issue = issue) } }
            }
        }
    }

    /**
     * Asks what the reader may do here (comment on a locked conversation, close it), once it is known who opened it.
     * Asked again when the accounts signed in change.
     */
    fun checkPermissions() {
        val issue = _state.value.issue ?: return
        viewModelScope.launch {
            val access = repository.access(ref.repo)
            val allowed = repository.canChangeState(ref, issue.author?.login)
            // What the forge only does to issues is not offered on a pull request.
            val supported = repository.actions(ref.repo) - if (issue.pullRequest != null) repository.issueOnly(ref.repo) else emptySet()
            val me = repository.me(ref.repo.forge)
            _state.update { it.copy(canChangeState = allowed, access = access, supported = supported, me = me, attachments = repository.attachments(ref.repo)) }
        }
    }

    /** Closes an open conversation, reopens a closed one. A merged pull request stays merged. */
    fun toggleOpen() {
        val issue = _state.value.issue ?: return
        if (_state.value.isChangingState || issue.state == IssueState.MERGED) return
        val open = issue.state != IssueState.OPEN
        _state.update { it.copy(isChangingState = true, stateError = null) }
        viewModelScope.launch {
            when (val result = repository.setOpen(ref, open)) {
                is ForgeResult.Failure -> _state.update { it.copy(isChangingState = false, stateError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { state ->
                        state.copy(isChangingState = false, issue = state.issue?.copy(state = if (open) IssueState.OPEN else IssueState.CLOSED))
                    }
                    // The forge's own account of it: the line that closes the conversation, and who wrote it.
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(isRefreshing = true, error = null) }
        refreshing = viewModelScope.launch {
            val issue = async { repository.issue(ref) }
            val firstPage = async { repository.timeline(ref, page = 1) }
            val issueResult = issue.await()
            val pageResult = firstPage.await()
            _state.update { state ->
                state.copy(
                    issue = (issueResult as? ForgeResult.Success)?.value ?: state.issue,
                    items = (pageResult as? ForgeResult.Success)?.value?.items ?: state.items,
                    nextPage = if (pageResult is ForgeResult.Success) pageResult.value.nextPage else state.nextPage,
                    lastPage = if (pageResult is ForgeResult.Success) pageResult.value.lastPage else state.lastPage,
                    isRefreshing = false,
                    error = (issueResult as? ForgeResult.Failure)?.error ?: (pageResult as? ForgeResult.Failure)?.error,
                )
            }
            checkPermissions()
        }
    }

    fun loadMore() {
        if (_state.value.nextPage == null || _state.value.isLoadingMore) return
        viewModelScope.launch {
            paging.withLock {
                val page = _state.value.nextPage ?: return@withLock
                _state.update { it.copy(isLoadingMore = true) }
                when (val result = repository.timeline(ref, page)) {
                    is ForgeResult.Success -> _state.update {
                        it.copy(items = it.items + result.value.items, nextPage = result.value.nextPage, isLoadingMore = false)
                    }
                    is ForgeResult.Failure -> _state.update { it.copy(isLoadingMore = false, error = result.error) }
                }
            }
        }
    }

    /** Goes to the end of the conversation, loading what is left of it first. */
    fun toEnd() {
        viewModelScope.launch {
            refreshing?.join()
            if (loadRest()) _state.update { it.copy(scrollTo = ScrollTarget.End) }
        }
    }

    /**
     * Opens the conversation where the reader left it: at the first entry newer than [lastRead], or at the latest one
     * when the forge doesn't say when that was. Once per screen: coming back to it leaves the list where it is.
     */
    fun openAtUnread(lastRead: Instant?) {
        if (openedAtUnread) return
        openedAtUnread = true
        viewModelScope.launch {
            // The forge's answer, not the copy kept: what is new is exactly what the copy lacks.
            refreshing?.join()
            if (!loadRest()) return@launch
            val items = _state.value.items
            if (items.isEmpty()) return@launch
            val firstNew = if (lastRead == null) -1 else items.indexOfFirst { it.createdAt?.isAfter(lastRead) == true }
            _state.update { it.copy(scrollTo = ScrollTarget.Item(if (firstNew >= 0) firstNew else items.lastIndex)) }
        }
    }

    fun scrolled() = _state.update { it.copy(scrollTo = null) }

    /**
     * Loads every page still to come, all at once when the forge says how many there are. False when one failed: what
     * came before it stays, and the rest can be asked again.
     */
    private suspend fun loadRest(): Boolean = paging.withLock {
        while (true) {
            val next = _state.value.nextPage ?: break
            _state.update { it.copy(isLoadingMore = true) }
            val last = _state.value.lastPage?.takeIf { it > next } ?: next
            val results = coroutineScope { (next..last).map { page -> async { repository.timeline(ref, page) } }.awaitAll() }
            val loaded = results.takeWhile { it is ForgeResult.Success }.map { (it as ForgeResult.Success).value }
            val failure = results.firstOrNull { it is ForgeResult.Failure } as? ForgeResult.Failure
            _state.update { state ->
                state.copy(
                    items = state.items + loaded.flatMap { it.items },
                    // A forge that kept announcing the same page would be asked forever.
                    nextPage = if (failure == null) loaded.last().nextPage?.takeIf { it > last } else next + loaded.size,
                    isLoadingMore = false,
                    error = failure?.error,
                )
            }
            if (failure != null) return@withLock false
        }
        true
    }

    fun errorShown() = _state.update { it.copy(error = null) }

    fun draftChanged(text: String) {
        // A comment being rewritten is not the comment being written: only that one is kept for later.
        if (_state.value.editing == null) {
            savedState[DRAFT_KEY] = text
            drafts.keepComment(ref, text)
        }
        _state.update { it.copy(draft = text, commentError = null) }
    }

    /** Starts a reply to [text]: quoted at the end of the comment being written, which the list then goes to. */
    fun quote(text: String) {
        if (text.isBlank()) return
        val quoted = text.trim().lines().joinToString("\n") { "> $it".trimEnd() }
        val written = _state.value.draft.trimEnd()
        draftChanged(if (written.isEmpty()) "$quoted\n\n" else "$written\n\n$quoted\n\n")
        _state.update { it.copy(scrollTo = ScrollTarget.End, draftPlaced = it.draftPlaced + 1) }
    }

    /** Opens the comment [id] to be rewritten, in its place; what was being written in the reader's turn waits. */
    fun startEditing(id: Long) {
        val state = _state.value
        if (state.isCommenting) return
        val comment = state.items.firstOrNull { it is TimelineItem.Comment && it.id == id } as? TimelineItem.Comment ?: return
        if (state.editing == null) setAside = state.draft
        // It is rewritten where it stands, so that is where the list goes: the top of the comment, clear of the keyboard.
        val at = ScrollTarget.Item(state.items.indexOf(comment))
        _state.update { it.copy(editing = id, draft = comment.body, commentError = null, scrollTo = at, draftPlaced = it.draftPlaced + 1) }
    }

    /** Leaves the comment as it was, and gives back what was being written. */
    fun cancelEditing() {
        if (_state.value.editing == null || _state.value.isCommenting) return
        _state.update { it.copy(editing = null, draft = setAside, commentError = null, draftPlaced = it.draftPlaced + 1) }
        setAside = ""
    }

    /** Deletes the comment [id]. It stays in the list until the forge has let it go. */
    fun deleteComment(id: Long) {
        viewModelScope.launch {
            when (val result = repository.deleteComment(ref, id)) {
                is ForgeResult.Failure -> _state.update { it.copy(deleteError = result.error) }
                is ForgeResult.Success -> {
                    // Deleted while it was being rewritten: there is nothing left to rewrite.
                    if (_state.value.editing == id) cancelEditing()
                    _state.update { state ->
                        state.copy(
                            items = state.items.filterNot { it is TimelineItem.Comment && it.id == id },
                            issue = state.issue?.let { it.copy(comments = (it.comments - 1).coerceAtLeast(0)) },
                        )
                    }
                }
            }
        }
    }

    fun deleteErrorShown() = _state.update { it.copy(deleteError = null) }

    /** What is being reacted to, a comment or the conversation's own text (null): one change at a time on each. */
    private val reacting = mutableSetOf<Long?>()

    /**
     * Gives [reaction] to the comment [commentId], or to the conversation's own text when null; takes it back when the
     * reader had given it. The counts shown are the forge's, once it has answered.
     */
    fun react(commentId: Long?, reaction: Reaction) {
        if (!reacting.add(commentId)) return
        viewModelScope.launch {
            when (val result = repository.toggleReaction(ref, commentId, reaction)) {
                is ForgeResult.Failure -> _state.update { it.copy(reactionError = result.error) }
                is ForgeResult.Success -> _state.update { state ->
                    if (commentId == null) {
                        state.copy(issue = state.issue?.copy(reactions = result.value))
                    } else {
                        state.copy(items = state.items.map { if (it is TimelineItem.Comment && it.id == commentId) it.copy(reactions = result.value) else it })
                    }
                }
            }
            reacting -= commentId
        }
    }

    fun reactionErrorShown() = _state.update { it.copy(reactionError = null) }

    /**
     * Uploads the picture the reader chose, then writes what shows it at the end of the comment, on a line of its own.
     * A null [picture] couldn't be read; one without bytes is larger than a forge takes.
     */
    fun attach(picture: PickedPicture?) {
        if (_state.value.isAttaching) return
        val bytes = picture?.bytes
        if (picture == null || bytes == null) {
            _state.update { it.copy(attachError = if (picture == null) ForgeError.Unreadable else ForgeError.Http(413, null)) }
            return
        }
        _state.update { it.copy(isAttaching = true, attachError = null) }
        viewModelScope.launch {
            when (val result = repository.attach(ref, picture.name, picture.mimeType, bytes)) {
                is ForgeResult.Failure -> _state.update { it.copy(isAttaching = false, attachError = result.error) }
                is ForgeResult.Success -> {
                    // Read now, not when the upload started: the reader may have gone on writing meanwhile.
                    val written = _state.value.draft.trimEnd()
                    draftChanged(if (written.isEmpty()) "${result.value}\n" else "$written\n\n${result.value}\n")
                    _state.update { it.copy(isAttaching = false, draftPlaced = it.draftPlaced + 1) }
                }
            }
        }
    }

    fun attachErrorShown() = _state.update { it.copy(attachError = null) }

    /** Sends the comment as rewritten. Its text stays in the reader's turn until the forge has taken it. */
    private fun sendEdit(id: Long, body: String) {
        _state.update { it.copy(isCommenting = true, commentError = null) }
        viewModelScope.launch {
            when (val result = repository.editComment(ref, id, body)) {
                is ForgeResult.Failure -> _state.update { it.copy(isCommenting = false, commentError = result.error) }
                is ForgeResult.Success -> {
                    _state.update { state ->
                        state.copy(
                            isCommenting = false,
                            editing = null,
                            draft = setAside,
                            draftPlaced = state.draftPlaced + 1,
                            items = state.items.map { if (it is TimelineItem.Comment && it.id == id) it.copy(body = body) else it },
                        )
                    }
                    setAside = ""
                }
            }
        }
    }

    /** Posts the draft. It stays until the forge has taken it, so a failure loses nothing. */
    fun sendComment() {
        val body = _state.value.draft.trim()
        if (body.isEmpty() || _state.value.isCommenting) return
        _state.value.editing?.let { return sendEdit(it, body) }
        _state.update { it.copy(isCommenting = true, commentError = null) }
        viewModelScope.launch {
            when (val result = repository.comment(ref, body)) {
                is ForgeResult.Failure -> _state.update { it.copy(isCommenting = false, commentError = result.error) }
                is ForgeResult.Success -> {
                    savedState[DRAFT_KEY] = ""
                    drafts.keepComment(ref, "")
                    _state.update { state ->
                        // At the end of a conversation loaded whole; otherwise it shows once the rest is loaded.
                        val atEnd = state.nextPage == null
                        state.copy(
                            draft = "",
                            draftPlaced = state.draftPlaced + 1,
                            isCommenting = false,
                            items = if (atEnd) state.items + result.value else state.items,
                            issue = state.issue?.let { it.copy(comments = it.comments + 1) },
                            commentPostedOutOfSight = !atEnd,
                        )
                    }
                }
            }
        }
    }

    /** Closes the issue saying why, where the forge keeps that. */
    fun close(reason: CloseReason) = manage({ repository.setOpen(ref, open = false, reason) })

    /** The sheet opened: the last change's outcome is forgotten, and whether the issue is pinned is asked once. */
    fun manageOpened() {
        _state.update { it.copy(manage = it.manage.copy(error = null, done = false)) }
        if (ConversationAction.PIN !in _state.value.actions || _state.value.manage.pinned != null) return
        viewModelScope.launch {
            val pinned = (repository.isPinned(ref) as? ForgeResult.Success)?.value
            _state.update { it.copy(manage = it.manage.copy(pinned = pinned)) }
        }
    }

    fun manageDoneShown() = _state.update { it.copy(manage = it.manage.copy(done = false)) }

    fun loadLabels() = load({ it.labels }, { repository.labels(ref.repo) }) { manage, value -> manage.copy(labels = value) }

    fun loadAssignable() = load({ it.assignable }, { repository.assignable(ref.repo) }) { manage, value -> manage.copy(assignable = value) }

    fun loadMilestones() = load({ it.milestones }, { repository.milestones(ref.repo) }) { manage, value -> manage.copy(milestones = value) }

    /** Loads one of the repository's lists the first time its page opens, and again after a failure. */
    private fun <T> load(current: (ManageUiState) -> Loadable<T>, ask: suspend () -> ForgeResult<T>, put: (ManageUiState, Loadable<T>) -> ManageUiState) {
        val now = current(_state.value.manage)
        if (now is Loadable.Loaded || now == Loadable.Loading) return
        _state.update { it.copy(manage = put(it.manage, Loadable.Loading)) }
        viewModelScope.launch {
            val loaded = when (val result = ask()) {
                is ForgeResult.Success -> Loadable.Loaded(result.value)
                is ForgeResult.Failure -> Loadable.Failed(result.error)
            }
            _state.update { it.copy(manage = put(it.manage, loaded)) }
        }
    }

    fun loadTracking() = load({ it.tracking }, { repository.timeTracking(ref) }) { manage, value -> manage.copy(tracking = value) }

    fun loadDependencies() = load({ it.dependencies }, { repository.dependencies(ref) }) { manage, value -> manage.copy(dependencies = value) }

    fun setDueDate(date: LocalDate?) = manage({ repository.setDueDate(ref, date) })

    /** Starts the reader's timer on this conversation, or stops it, which records the time it ran. */
    fun toggleTimer() {
        val tracking = (_state.value.manage.tracking as? Loadable.Loaded)?.value ?: return
        // What was loaded is out of date once the timer changed: it is asked again when the page reopens.
        manage({ repository.setTimerRunning(ref, tracking.runningSince == null) }) { it.copy(manage = it.manage.copy(tracking = Loadable.Idle)) }
    }

    fun addTime(seconds: Long) {
        if (seconds <= 0) return
        manage({ repository.addTime(ref, seconds) }) { it.copy(manage = it.manage.copy(tracking = Loadable.Idle)) }
    }

    /** Makes this conversation depend on [written]: a number, "#12", or "owner/name#12" for one in another repository. */
    fun addDependency(written: String) {
        val on = dependencyTarget(ref, written) ?: return
        manage({ repository.addDependency(ref, on) }) { it.copy(manage = it.manage.copy(dependencies = Loadable.Idle)) }
    }

    fun removeDependency(on: IssueRef) =
        manage({ repository.removeDependency(ref, on) }) { it.copy(manage = it.manage.copy(dependencies = Loadable.Idle)) }

    fun setLabels(names: List<String>) = manage({ repository.setLabels(ref, names) })

    fun setAssignees(logins: List<String>) = manage({ repository.setAssignees(ref, logins) })

    fun setMilestone(milestone: Milestone?) = manage({ repository.setMilestone(ref, milestone) })

    fun toggleLocked() {
        val locked = _state.value.issue?.isLocked ?: return
        manage({ repository.setLocked(ref, !locked) })
    }

    fun togglePinned() {
        val pinned = _state.value.manage.pinned ?: return
        manage({ repository.setPinned(ref, !pinned) }) { it.copy(manage = it.manage.copy(pinned = !pinned)) }
    }

    /** Moves the issue to [destination], "owner/name" or a name alone for a repository of the same owner. */
    fun transfer(destination: String) {
        val to = transferTarget(ref.repo, destination) ?: return
        manage({ repository.transfer(ref, to) }, reload = false) { state, moved -> state.copy(movedTo = moved) }
    }

    fun delete() = manage({ repository.delete(ref) }, reload = false) { it.copy(deleted = true) }

    /** Starts a new issue in the same repository from this one's title and description, to be changed before it is sent. */
    fun duplicate() {
        val issue = _state.value.issue ?: return
        drafts.keep(ref.repo, IssueDraft(issue.title, issue.body.orEmpty()))
    }

    private fun manage(change: suspend () -> ForgeResult<Unit>, reload: Boolean = true, then: (IssueUiState) -> IssueUiState = { it }) =
        manage(change, reload) { state, _ -> then(state) }

    /**
     * One change at a time. Once the forge took it, the sheet closes and the conversation loads again ([reload]) so
     * the header and the line that tells of the change come from the forge.
     */
    private fun <T> manage(change: suspend () -> ForgeResult<T>, reload: Boolean, then: (IssueUiState, T) -> IssueUiState) {
        if (_state.value.manage.isWorking) return
        _state.update { it.copy(manage = it.manage.copy(isWorking = true, error = null)) }
        viewModelScope.launch {
            when (val result = change()) {
                is ForgeResult.Failure -> _state.update { it.copy(manage = it.manage.copy(isWorking = false, error = result.error)) }
                is ForgeResult.Success -> {
                    _state.update { then(it.copy(manage = it.manage.copy(isWorking = false, done = true)), result.value) }
                    if (reload) refresh()
                }
            }
        }
    }

    fun commentNoticeShown() = _state.update { it.copy(commentPostedOutOfSight = false) }

    private companion object {
        const val DRAFT_KEY = "draft"
    }
}

/**
 * The conversation [written] names, seen from [from]: "12" or "#12" in the same repository, "owner/name#12" in
 * another. Null when it names nothing, or [from] itself.
 */
fun dependencyTarget(from: IssueRef, written: String): IssueRef? {
    val text = written.trim()
    val number = text.substringAfterLast('#').toIntOrNull()?.takeIf { it > 0 } ?: return null
    val repo = when {
        '#' !in text || text.startsWith('#') -> from.repo
        else -> text.substringBeforeLast('#').split('/').takeIf { it.size == 2 && it.none(String::isBlank) }?.let { RepoId(it[0], it[1], from.repo.forge) } ?: return null
    }
    return IssueRef(repo, number).takeUnless { it.number == from.number && it.repo.fullName.equals(from.repo.fullName, ignoreCase = true) }
}

/**
 * The repository [written] names, seen from [from]: "owner/name", or a name alone for one of the same owner. Null
 * when it names nothing, or [from] itself.
 */
fun transferTarget(from: RepoId, written: String): RepoId? {
    val parts = written.trim().removeSuffix("/").split('/')
    val target = when {
        parts.any { it.isBlank() || it.any(Char::isWhitespace) } -> return null
        parts.size == 1 -> RepoId(from.owner, parts[0], from.forge)
        parts.size == 2 -> RepoId(parts[0], parts[1], from.forge)
        else -> return null
    }
    return target.takeUnless { it.owner.equals(from.owner, ignoreCase = true) && it.name.equals(from.name, ignoreCase = true) }
}
