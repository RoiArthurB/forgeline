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
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class InboxFilter { UNREAD, PARTICIPATING, ALL }

data class RepoGroup(val repo: RepoId, val threads: List<NotificationThread>)

data class InboxUiState(
    val filter: InboxFilter = InboxFilter.UNREAD,
    val groups: List<RepoGroup> = emptyList(),
    val syncedAtMillis: Long? = null,
    val isRefreshing: Boolean = false,
    val error: ForgeError? = null,
    val actionFailed: Boolean = false,
    /** Whether the Inbox is checked in the background, which is when notifications matter. */
    val backgroundChecks: Boolean = false,
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val inbox: InboxRepository,
    settings: UserSettingsRepository,
) : ViewModel() {

    private val filter = savedState.getStateFlow(FILTER_KEY, InboxFilter.UNREAD)
    private val status = MutableStateFlow(Status())

    private data class Status(val isRefreshing: Boolean = false, val error: ForgeError? = null, val actionFailed: Boolean = false)

    val state: StateFlow<InboxUiState> = combine(
        inbox.observe(),
        filter,
        status,
        settings.settings.map { it.inboxCheckInterval != InboxCheckInterval.OFF },
    ) { snapshot, filter, status, backgroundChecks ->
        InboxUiState(
            filter = filter,
            groups = snapshot.threads.filter { filter.matches(it) }.groupByRepo(),
            syncedAtMillis = snapshot.syncedAtMillis,
            isRefreshing = status.isRefreshing,
            error = status.error,
            actionFailed = status.actionFailed,
            backgroundChecks = backgroundChecks,
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
        if (thread.unread) viewModelScope.launch { inbox.markRead(thread.id) }
    }

    fun markRead(thread: NotificationThread) = act { inbox.markRead(thread.id) }

    fun markDone(thread: NotificationThread) = act { inbox.markDone(thread.id) }

    fun unsubscribe(thread: NotificationThread) = act { inbox.unsubscribe(thread.id) }

    fun errorShown() = status.update { it.copy(error = null) }

    fun actionFailureShown() = status.update { it.copy(actionFailed = false) }

    private fun sync(force: Boolean) {
        status.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val result = inbox.sync(force)
            status.update { it.copy(isRefreshing = false, error = (result as? SyncResult.Failed)?.error) }
        }
    }

    private fun act(action: suspend () -> ForgeResult<Unit>) {
        viewModelScope.launch {
            if (action() is ForgeResult.Failure) status.update { it.copy(actionFailed = true) }
        }
    }

    private fun MutableStateFlow<Status>.update(change: (Status) -> Status) {
        value = change(value)
    }

    private fun InboxFilter.matches(thread: NotificationThread) = when (this) {
        InboxFilter.UNREAD -> thread.unread
        InboxFilter.PARTICIPATING -> thread.isParticipating
        InboxFilter.ALL -> true
    }

    // Threads arrive newest first, so the first thread of each repo orders the groups.
    private fun List<NotificationThread>.groupByRepo(): List<RepoGroup> =
        groupBy { it.repo }.map { (repo, threads) -> RepoGroup(repo, threads) }

    private companion object {
        const val FILTER_KEY = "filter"
    }
}
