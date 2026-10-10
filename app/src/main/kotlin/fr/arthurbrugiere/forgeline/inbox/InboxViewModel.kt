package fr.arthurbrugiere.forgeline.inbox

import fr.arthurbrugiere.forgeline.core.model.UndoDelay
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
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import javax.inject.Inject

enum class InboxFilter { UNREAD, PARTICIPATING, ALL }

/**
 * The Inbox's two sections: what's waiting on you first, newest first; then everything else by owner (user or
 * organisation), then repository, each ordered by its newest activity, threads newest first.
 */
enum class InboxSection { NEEDS_YOU, OTHERS }

/**
 * Keeps things in the order they were first seen in. What is new takes its natural place among them; what leaves
 * and comes back (an Undo) finds its place again.
 */
internal class StableOrder<K> {
    private val known = mutableListOf<K>()

    /** [natural] as it would be ordered from scratch, rearranged so that nothing already placed moves. */
    fun arrange(natural: List<K>): List<K> {
        natural.forEachIndexed { index, key ->
            if (key in known) return@forEachIndexed
            // Before the first one already placed that it would naturally come before.
            val next = natural.subList(index + 1, natural.size).firstOrNull { it in known }
            if (next == null) known += key else known.add(known.indexOf(next), key)
        }
        val present = natural.toSet()
        return known.filter { it in present }
    }
}

data class SectionGroup(val section: InboxSection, val threads: List<NotificationThread>)

/** What a swipe or the menu did to a thread; it waits [InboxViewModel.UNDO_MILLIS] before reaching the forge. */
enum class InboxAction { READ, UNREAD, DONE, UNSUBSCRIBE }

/** The latest action that can still be undone. [serial] tells two identical actions apart. */
/**
 * [key] is the thread's [NotificationThread.key]: its account and id. [others] are the threads the same gesture took
 * with it, a whole repository's: one Undo brings them all back.
 */
data class PendingUndo(val key: String, val action: InboxAction, val serial: Long, val others: List<String> = emptyList()) {
    val keys: List<String> get() = listOf(key) + others
}

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
    /**
     * One tab per account, after an "All" tab, when the Inbox is split per forge (a setting, with several accounts);
     * empty otherwise.
     */
    val accountTabs: List<Account> = emptyList(),
    /** The account whose tab is shown, when there are tabs. */
    val selectedAccountId: String? = null,
    /** Rows say which forge they're from only when more than one forge is signed in. */
    val showForge: Boolean = false,
    /** The threads picked to be acted on together, by [NotificationThread.key]; empty when none is being picked. */
    val selected: Set<String> = emptySet(),
) {
    val threads: List<NotificationThread> get() = groups.flatMap { it.threads }

    val selectedThreads: List<NotificationThread> get() = if (selected.isEmpty()) emptyList() else threads.filter { it.key in selected }
}

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val inbox: InboxRepository,
    settings: UserSettingsRepository,
    accounts: AccountRepository,
    @param:BackgroundScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val filter = savedState.getStateFlow(FILTER_KEY, InboxFilter.UNREAD)
    private val selectedAccount = savedState.getStateFlow<String?>(ACCOUNT_KEY, null)

    private data class Split(val tabs: List<Account>, val selected: String?, val showForge: Boolean)

    private val split = combine(accounts.accounts, settings.settings, selectedAccount) { signedIn, settings, selected ->
        val tabs = if (settings.separateInboxPerForge && signedIn.size > 1) signedIn else emptyList()
        // Nothing selected is "All": the unified list stays the default, even split.
        Split(tabs, selected?.takeIf { id -> tabs.any { it.id == id } }, signedIn.map { it.forge }.distinct().size > 1)
    }
    private val status = MutableStateFlow(Status())

    private data class Status(val isRefreshing: Boolean = false, val error: ForgeError? = null, val actionFailed: Boolean = false)

    /** Actions shown as done but still undoable, by thread, with the timer that sends them. */
    private val pending = MutableStateFlow<Map<String, InboxAction>>(emptyMap())
    private val timers = mutableMapOf<String, Job>()
    private val undo = MutableStateFlow<PendingUndo?>(null)
    private var serial = 0L

    /** How long an action can be taken back, as chosen in Settings. */
    private val undoDelay = settings.settings.map { it.undoDelay }.stateIn(viewModelScope, SharingStarted.Eagerly, UndoDelay.SEC_5)

    /**
     * Where each owner and repository stands under Everything else, per list (a filter, an account). Regression:
     * they were ordered by their newest thread each time, so marking that thread done sent its repository down the
     * list, under the finger. They now stay put until the list is refreshed.
     */
    private val placed = mutableMapOf<Pair<InboxFilter, String?>, Pair<StableOrder<Pair<ForgeInstance, String>>, StableOrder<RepoId>>>()
    private val refreshes = MutableStateFlow(0)
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<InboxUiState> = combine(
        combine(inbox.observe(), pending, refreshes) { snapshot, pending, _ -> snapshot to pending },
        filter,
        status,
        combine(settings.settings.map { it.inboxCheckInterval != InboxCheckInterval.OFF }, split) { checks, split -> checks to split },
        combine(undo, selected) { undo, selected -> undo to selected },
    ) { (snapshot, pending), filter, status, (backgroundChecks, split), (undo, selected) ->
        val groups = snapshot.threads.applying(pending)
            .filter { filter.matches(it) && (split.selected == null || it.accountId == split.selected) }
            .bySection(placed.getOrPut(filter to split.selected) { StableOrder<Pair<ForgeInstance, String>>() to StableOrder() })
        InboxUiState(
            filter = filter,
            groups = groups,
            // Only what is listed can be picked: a thread that left the list (done elsewhere, another filter) is let go.
            selected = if (selected.isEmpty()) selected else groups.flatMapTo(HashSet()) { group -> group.threads.map { it.key } }.intersect(selected),
            accountTabs = split.tabs,
            selectedAccountId = split.selected,
            showForge = split.showForge,
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
        clearSelection()
        savedState[FILTER_KEY] = filter
    }

    /** Shows one account's threads; null shows every account's. */
    fun selectAccount(accountId: String?) {
        savedState[ACCOUNT_KEY] = accountId
    }

    fun refresh() {
        // Asked for: the list is drawn up afresh, newest activity first.
        placed.clear()
        refreshes.update { it + 1 }
        sync(force = true)
    }

    /**
     * The Inbox is on screen again: what was read or done somewhere else since is asked for, quietly, and no more
     * often than the forge allows.
     */
    fun shown() {
        viewModelScope.launch { inbox.sync(force = false) }
    }

    fun opened(thread: NotificationThread) {
        if (thread.unread) viewModelScope.launch { inbox.markRead(thread.accountId, thread.id) }
    }

    fun markRead(thread: NotificationThread) = hold(listOf(thread), InboxAction.READ)

    /** Back among the unread ones: for later, or read by mistake. */
    fun markUnread(thread: NotificationThread) = hold(listOf(thread), InboxAction.UNREAD)

    fun markDone(thread: NotificationThread) = hold(listOf(thread), InboxAction.DONE)

    /** Marks [threads] done in one go: a repository's, swiped away by its heading. */
    fun markAllDone(threads: List<NotificationThread>) = hold(threads, InboxAction.DONE)

    fun unsubscribe(thread: NotificationThread) = hold(listOf(thread), InboxAction.UNSUBSCRIBE)

    /** Picks [thread], or lets it go: the first one picked starts the selection, the last one let go ends it. */
    fun toggleSelected(thread: NotificationThread) = selected.update { if (thread.key in it) it - thread.key else it + thread.key }

    /** Picks every thread listed, under the filter and account shown. */
    fun selectAll() {
        selected.value = state.value.threads.mapTo(HashSet()) { it.key }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /** Does [action] to every thread picked, in one go with one Undo, and ends the selection. */
    fun actOnSelected(action: InboxAction) {
        val picked = state.value.selectedThreads.filter {
            when (action) {
                InboxAction.READ -> it.unread
                InboxAction.UNREAD -> !it.unread
                InboxAction.DONE, InboxAction.UNSUBSCRIBE -> true
            }
        }
        clearSelection()
        hold(picked, action)
    }

    /** Takes back [undo]'s action before it reaches the forge: the thread comes back as it was. */
    fun undo(undo: PendingUndo) {
        // Those already sent are with the forge; the others come back.
        val held = undo.keys.filter { timers.remove(it)?.apply { cancel() } != null }
        if (held.isEmpty()) return
        pending.update { it - held.toSet() }
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
     * Shows [action] as done right away but holds it for the delay chosen in Settings ([UNDO_MILLIS] untouched), so an accidental swipe can be taken back
     * (the forge has no way to undo "done"). Several actions can wait at once; Undo offers the latest.
     */
    private fun hold(threads: List<NotificationThread>, action: InboxAction) {
        val keys = threads.map { it.key }.distinct()
        if (keys.isEmpty()) return
        val wait = undoDelay.value.millis
        if (wait == 0L) {
            // Nothing to take back: each one goes to the forge now, shown as done until the forge has answered.
            pending.update { it + keys.associateWith { action } }
            keys.forEach { key -> timers.remove(key)?.cancel(); send(key, action) }
            return
        }
        val next = PendingUndo(keys.first(), action, ++serial, keys.drop(1))
        pending.update { it + keys.associateWith { action } }
        undo.value = next
        // Each thread waits on its own: a later action on one of them replaces only that one's.
        keys.forEach { key ->
            timers.remove(key)?.cancel()
            timers[key] = viewModelScope.launch {
                delay(wait)
                timers.remove(key)
                undo.update { if (it == next) null else it }
                send(key, action)
            }
        }
    }

    /** [key] is a [NotificationThread.key]: the account, then the thread's id. */
    private fun send(key: String, action: InboxAction) {
        val accountId = key.substringBefore('|')
        val threadId = key.substringAfter('|')
        appScope.launch {
            val result = when (action) {
                InboxAction.READ -> inbox.markRead(accountId, threadId)
                InboxAction.UNREAD -> inbox.markUnread(accountId, threadId)
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

    private fun List<NotificationThread>.applying(pending: Map<String, InboxAction>): List<NotificationThread> =
        if (pending.isEmpty()) this else mapNotNull { thread ->
            when (pending[thread.key]) {
                null -> thread
                InboxAction.READ -> thread.copy(unread = false)
                InboxAction.UNREAD -> thread.copy(unread = true)
                InboxAction.DONE, InboxAction.UNSUBSCRIBE -> null
            }
        }

    private fun InboxFilter.matches(thread: NotificationThread) = when (this) {
        InboxFilter.UNREAD -> thread.unread
        InboxFilter.PARTICIPATING -> thread.isParticipating
        InboxFilter.ALL -> true
    }

    // Threads arrive newest first, so grouping keeps that order: an owner's (or repo's) first thread is its newest,
    // and it places the whole group, the first time. Owners are compared case-insensitively, as the forge does.
    private fun List<NotificationThread>.bySection(
        placed: Pair<StableOrder<Pair<ForgeInstance, String>>, StableOrder<RepoId>>,
    ): List<SectionGroup> =
        groupBy { if (it.needsYou) InboxSection.NEEDS_YOU else InboxSection.OTHERS }
            .toSortedMap()
            .map { (section, threads) ->
                SectionGroup(
                    section,
                    if (section == InboxSection.OTHERS) {
                        val owners = threads.groupBy { it.repo.forge to it.repo.owner.lowercase() }
                        val repos = threads.groupBy { it.repo }
                        val repoOrder = placed.second.arrange(repos.keys.toList())
                        placed.first.arrange(owners.keys.toList()).flatMap { owner ->
                            repoOrder.filter { it.forge to it.owner.lowercase() == owner }.flatMap(repos::getValue)
                        }
                    } else {
                        threads
                    },
                )
            }

    companion object {
        const val UNDO_MILLIS = 5_000L
        private const val FILTER_KEY = "filter"
        private const val ACCOUNT_KEY = "account"
    }
}
