package fr.arthurbrugiere.forgeline.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.data.inbox.SyncResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import fr.arthurbrugiere.forgeline.di.ApplicationScope
import javax.inject.Inject

enum class InboxFilter { UNREAD, PARTICIPATING, ALL }

/**
 * The Inbox's two sections: what's waiting on you first, newest first; then everything else by owner (user or
 * organisation), then repository, each ordered by its newest activity, threads newest first.
 */
enum class InboxSection { NEEDS_YOU, OTHERS }

data class SectionGroup(val section: InboxSection, val threads: List<NotificationThread>)

/** What a swipe or the menu did to a thread; it waits [InboxViewModel.UNDO_MILLIS] before reaching the forge. */
enum class InboxAction { READ, DONE, UNSUBSCRIBE }

/** The latest action that can still be undone. [serial] tells two identical actions apart. */
/** [key] is the thread's [NotificationThread.key]: its account and id. */
data class PendingUndo(val key: String, val action: InboxAction, val serial: Long)

data class InboxUiState(
    val filter: InboxFilter = InboxFilter.UNREAD,
    val groups: List<SectionGroup> = emptyList(),
    val syncedAtMillis: Long? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
    val actionFailed: Boolean = false,
    /** Whether the Inbox is checked in the background, which is when notifications matter. */
    val backgroundChecks: Boolean = false,
    val undo: PendingUndo? = null,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val inbox: InboxRepository,
    settings: UserSettingsRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val filter = savedState.getStateFlow(FILTER_KEY, InboxFilter.UNREAD)
    private val status = MutableStateFlow(Status())

    private data class Status(val isRefreshing: Boolean = false, val error: ForgeError? = null, val actionFailed: Boolean = false)

    /** Actions shown as done but still undoable, by thread, with the timer that sends them. */
    private val pending = MutableStateFlow<Map<String, InboxAction>>(emptyMap())
    private val timers = mutableMapOf<String, Job>()
    private val undo = MutableStateFlow<PendingUndo?>(null)
    private var serial = 0L

    val state: StateFlow<InboxUiState> = combine(
        combine(inbox.observe(), pending) { snapshot, pending -> snapshot to pending },
        filter,
        status,
        settings.settings.map { it.inboxCheckInterval != InboxCheckInterval.OFF },
        undo,
    ) { (snapshot, pending), filter, status, backgroundChecks, undo ->
        InboxUiState(
            filter = filter,
            groups = snapshot.threads.applying(pending).filter { filter.matches(it) }.bySection(),
            syncedAtMillis = snapshot.syncedAtMillis,
            isRefreshing = status.isRefreshing,
            error = status.error,
            actionFailed = status.actionFailed,
            backgroundChecks = backgroundChecks,
            undo = undo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState(filter = filter.value))

    init {
        sync(force = false)
    }

    fun selectFilter(filter: InboxFilter) {
        savedState[FILTER_KEY] = filter
    }

    fun refresh() = sync(force = true)

    fun opened(thread: NotificationThread) {
        if (thread.unread) viewModelScope.launch { inbox.markRead(thread.accountId, thread.id) }
    }

    fun markRead(thread: NotificationThread) = hold(thread, InboxAction.READ)

    fun markDone(thread: NotificationThread) = hold(thread, InboxAction.DONE)

    fun unsubscribe(thread: NotificationThread) = hold(thread, InboxAction.UNSUBSCRIBE)

    /** Takes back [undo]'s action before it reaches the forge: the thread comes back as it was. */
    fun undo(undo: PendingUndo) {
        timers.remove(undo.key)?.cancel() ?: return
        pending.update { it - undo.key }
        this.undo.update { if (it == undo) null else it }
    }

    override fun onCleared() {
        // Leaving the Inbox doesn't cancel what the user asked for: every held action is sent now.
        timers.keys.toList().forEach { id -> timers.remove(id)?.cancel(); pending.value[id]?.let { send(id, it) } }
    }

    fun errorShown() = status.update { it.copy(error = null) }

    fun actionFailureShown() = status.update { it.copy(actionFailed = false) }

    private fun sync(force: Boolean) {
        status.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val result = inbox.sync(force)
            status.update { it.copy(isRefreshing = false, error = (result as? SyncResult.Failed)?.error) }
        }
    }

    /**
     * Shows [action] as done right away but holds it for [UNDO_MILLIS], so an accidental swipe can be taken back
     * (the forge has no way to undo "done"). Several actions can wait at once; Undo offers the latest.
     */
    private fun hold(thread: NotificationThread, action: InboxAction) {
        timers.remove(thread.key)?.cancel()
        val next = PendingUndo(thread.key, action, ++serial)
        pending.update { it + (thread.key to action) }
        undo.value = next
        timers[thread.key] = viewModelScope.launch {
            delay(UNDO_MILLIS)
            timers.remove(thread.key)
            undo.update { if (it == next) null else it }
            send(thread.key, action)
        }
    }

    /** [key] is a [NotificationThread.key]: the account, then the thread's id. */
    private fun send(key: String, action: InboxAction) {
        val accountId = key.substringBefore('|')
        val threadId = key.substringAfter('|')
        appScope.launch {
            val result = when (action) {
                InboxAction.READ -> inbox.markRead(accountId, threadId)
                InboxAction.DONE -> inbox.markDone(accountId, threadId)
                InboxAction.UNSUBSCRIBE -> inbox.unsubscribe(accountId, threadId)
            }
            // The repository now reflects the outcome (or restored the thread), so stop overriding it, unless a
            // newer action on the same thread is waiting.
            viewModelScope.launch {
                if (key !in timers) pending.update { it - key }
                if (result is ForgeResult.Failure) status.update { it.copy(actionFailed = true) }
            }
        }
    }

    private fun <T> MutableStateFlow<T>.update(change: (T) -> T) {
        value = change(value)
    }

    private fun List<NotificationThread>.applying(pending: Map<String, InboxAction>): List<NotificationThread> =
        if (pending.isEmpty()) this else mapNotNull { thread ->
            when (pending[thread.key]) {
                null -> thread
                InboxAction.READ -> thread.copy(unread = false)
                InboxAction.DONE, InboxAction.UNSUBSCRIBE -> null
            }
        }

    private fun InboxFilter.matches(thread: NotificationThread) = when (this) {
        InboxFilter.UNREAD -> thread.unread
        InboxFilter.PARTICIPATING -> thread.isParticipating
        InboxFilter.ALL -> true
    }

    // Threads arrive newest first, so grouping keeps that order: an owner's (or repo's) first thread is its newest,
    // and it places the whole group. Owners are compared case-insensitively, as the forge does.
    private fun List<NotificationThread>.bySection(): List<SectionGroup> =
        groupBy { if (it.needsYou) InboxSection.NEEDS_YOU else InboxSection.OTHERS }
            .toSortedMap()
            .map { (section, threads) ->
                SectionGroup(
                    section,
                    if (section == InboxSection.OTHERS) {
                        threads.groupBy { it.repo.forge to it.repo.owner.lowercase() }.values.flatMap { owned -> owned.groupBy { it.repo }.values.flatten() }
                    } else {
                        threads
                    },
                )
            }

    companion object {
        const val UNDO_MILLIS = 5_000L
        private const val FILTER_KEY = "filter"
    }
}
