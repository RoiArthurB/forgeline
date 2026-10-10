package fr.arthurbrugiere.forgeline.core.data.inbox

import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineDispatcher
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import javax.inject.Singleton

data class InboxSnapshot(val threads: List<NotificationThread>, val syncedAtMillis: Long?)

sealed interface SyncResult {
    data class Updated(val threads: List<NotificationThread>) : SyncResult

    data object NotModified : SyncResult

    data object SignedOut : SyncResult

    data class Failed(val error: ForgeError) : SyncResult
}

interface InboxRepository {
    /** Every signed-in account's inbox in one list, newest first; empty when signed out. */
    fun observe(): Flow<InboxSnapshot>

    /**
     * Syncs every account at once, each on its own forge and poll interval. Unless [force]d, each waits for its forge's
     * poll interval and asks only for changes. Updated when any account changed; Failed only when every account failed.
     *
     * Returns as soon as the threads are in: where their issues stand and the conversations loaded ahead follow in
     * the background, unless [waitForFollowUps] (a background check, which must finish its work before it ends).
     *
     * [onlyIfNew] lets a forge answer "nothing new" without listing its threads: cheap, for a background check, but
     * blind to what was read or done on the forge's own site. The Inbox on screen always asks for the list.
     */
    suspend fun sync(force: Boolean = false, waitForFollowUps: Boolean = false, onlyIfNew: Boolean = false): SyncResult

    // Threads are identified by their account and id: two forges can use the same thread id.
    suspend fun markRead(accountId: String, threadId: String): ForgeResult<Unit>

    /**
     * Back among the unread ones, after it was read. It stays unread here until it is read or done here, whatever
     * the forge can be told.
     */
    suspend fun markUnread(accountId: String, threadId: String): ForgeResult<Unit>

    suspend fun markDone(accountId: String, threadId: String): ForgeResult<Unit>

    suspend fun unsubscribe(accountId: String, threadId: String): ForgeResult<Unit>

    /**
     * Unread threads with activity newer than anything notified before, and marks them notified.
     * The first sync for an account only sets that baseline, so signing in never floods the phone.
     */
    suspend fun takeThreadsToNotify(): List<NotificationThread>
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultInboxRepository @Inject constructor(
    private val dao: InboxDao,
    private val doneDao: DoneDao,
    private val keptUnreadDao: KeptUnreadDao,
    private val baselineDao: BaselineDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val conversations: IssueRepository,
    private val clock: Clock,
    @param:BackgroundScope private val scope: CoroutineScope,
    @param:Computation private val computation: CoroutineDispatcher,
    private val settings: UserSettingsRepository,
) : InboxRepository {

    /** One lock per account: two syncs of one account never overlap, and accounts never wait for each other. */
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockOf(account: Account) = syncLocks.getOrPut(account.id) { Mutex() }

    /** Follow-ups (states, conversations) run one round at a time: a newer sync's round waits for the current one. */
    private val followUpLock = Mutex()

    /**
     * When each thread was last read or done from here, by [NotificationThread.key]. A list asked for before that
     * still calls the thread unread: it is out of date, not new activity.
     */
    private val handledHere = ConcurrentHashMap<String, Long>()

    override fun observe(): Flow<InboxSnapshot> = accounts.accounts.flatMapLatest { signedIn ->
        if (signedIn.isEmpty()) {
            flowOf(InboxSnapshot(emptyList(), null))
        } else {
            val ids = signedIn.map { it.id }.toSet()
            combine(dao.observeAll(), dao.observeSyncs(), dao.observeStates(), doneDao.observe()) { threads, syncs, states, done ->
                val byRef = states.associateBy { it.ref() }
                val doneKeys = done.mapTo(HashSet()) { it.accountId to it.threadId }
                InboxSnapshot(
                    threads.filter { it.accountId in ids }
                        // Done where the forge couldn't: gone until something new happens on it, which is when it
                        // is unread again. Not by its date: marking it read moves that too.
                        .filterNot { entity -> !entity.unread && (entity.accountId to entity.id) in doneKeys }
                        .map { entity ->
                        val thread = entity.toModel()
                        val state = thread.subject?.let { byRef[it] }?.state?.let { name -> SubjectState.entries.firstOrNull { it.name == name } }
                        thread.copy(state = state)
                    },
                    // The Inbox is as fresh as its stalest account.
                    syncs.filter { it.accountId in ids }.takeIf { it.size == ids.size }?.minOfOrNull { it.syncedAtMillis },
                )
            }
        }
        // Regression: the list was built on whichever thread read it, which was the main thread.
    }.flowOn(computation)

    override suspend fun sync(force: Boolean, waitForFollowUps: Boolean, onlyIfNew: Boolean): SyncResult {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return SyncResult.SignedOut
        val results = coroutineScope { signedIn.map { account -> async { syncAccount(account, force, onlyIfNew) } }.awaitAll() }
        // Threads first, on screen at once; where their issues and pull requests stand follows, off the refresh.
        val synced = signedIn.zip(results).filter { (_, result) -> result is SyncResult.Updated || result is SyncResult.NotModified }.map { it.first }
        if (synced.isNotEmpty()) {
            if (waitForFollowUps) followUp(synced) else scope.launch { followUp(synced) }
        }
        val updated = results.filterIsInstance<SyncResult.Updated>()
        return when {
            updated.isNotEmpty() -> SyncResult.Updated(updated.flatMap { it.threads })
            results.any { it == SyncResult.NotModified } -> SyncResult.NotModified
            else -> results.firstOrNull { it is SyncResult.Failed } ?: SyncResult.SignedOut
        }
    }

    /**
     * Loads the conversations waiting on you ahead of time (newest first, a few per sync), so opening one, or tapping
     * its phone notification, shows it at once. Read ones count too: you reopen them, and "Needs you" lists them until
     * they're done. When there are more than fit, unread ones go first. Ones kept since their latest activity are skipped.
     */
    private suspend fun prefetchConversations(account: Account, permits: Semaphore) {
        val waiting = dao.all(account.id).asSequence()
            .map { it.toModel() }
            .filter { it.needsYou }
            // Stable: newest first within unread, then within read.
            .sortedByDescending { it.unread }
            .mapNotNull { thread -> thread.subject?.let { it to thread.updatedAt } }
            .take(PREFETCHED_CONVERSATIONS)
            .toList()
        coroutineScope {
            waiting.forEach { (ref, activityAt) -> launch { permits.withPermit { conversations.prefetch(ref, activityAt) } } }
        }
    }

    /** Every account's subject states and conversations ahead, all at once, a few conversations at a time. */
    private suspend fun followUp(synced: List<Account>) = followUpLock.withLock {
        val permits = Semaphore(PREFETCH_CONCURRENCY)
        // Loading ahead can be switched off in Settings: it costs requests and data for conversations never opened.
        val ahead = settings.settings.first().loadConversationsAhead
        coroutineScope {
            synced.forEach { account ->
                launch { refreshStates(account) }
                if (ahead) launch { prefetchConversations(account, permits) }
            }
        }
    }

    /** Asks where the inbox's issues and pull requests stand, for those never asked, moved on since, or asked long ago. */
    private suspend fun refreshStates(account: Account) {
        val token = accounts.token(account.id) ?: return
        val now = clock.millis()
        // A conversation still in the inbox has its state asked (or sent with its thread) far more often than this.
        dao.pruneStates(now - STATE_KEEP_MILLIS)
        val known = dao.states().associateBy { it.ref() }
        val stale = dao.all(account.id).map { it.toModel() }.mapNotNull { thread ->
            val ref = thread.subject ?: return@mapNotNull null
            val state = known[ref]
            val fresh = state != null && state.threadUpdatedAtMillis >= thread.updatedAt.toEpochMilli() &&
                now - state.checkedAtMillis < STATE_MAX_AGE_MILLIS
            if (fresh) null else ref to thread.updatedAt.toEpochMilli()
        }.toMap()
        if (stale.isEmpty()) return
        val states = (clients.notifications(account.forge).subjectStates(token, stale.keys.toList()) as? ForgeResult.Success)?.value ?: return
        dao.upsertStates(
            states.map { (ref, state) ->
                SubjectStateEntity(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, state.name, stale.getValue(ref), now, ref.storedKind)
            },
        )
    }

    private suspend fun syncAccount(account: Account, force: Boolean, onlyIfNew: Boolean): SyncResult = lockOf(account).withLock {
        val token = accounts.token(account.id) ?: return SyncResult.SignedOut
        val state = dao.sync(account.id)
        val interval = (state?.pollIntervalSeconds ?: DEFAULT_POLL_SECONDS) * 1_000L
        if (!force && state != null && clock.millis() - state.syncedAtMillis < interval) return SyncResult.NotModified
        val askedAt = clock.millis()
        when (val result = clients.notifications(account.forge).threads(token, if (onlyIfNew && !force) state?.lastModified else null)) {
            is ForgeResult.Failure -> {
                // The forge turned the token down: nothing will sync for this account until it signs in again.
                if (result.error == ForgeError.Unauthorized) accounts.markSignInEnded(account.id)
                SyncResult.Failed(result.error)
            }
            is ForgeResult.Success -> {
                val sync = result.value
                // An account's threads are on its forge, whatever the client filled in.
                val threads = sync.threads?.map { listed ->
                    val thread = listed.copy(accountId = account.id, repo = listed.repo.copy(forge = account.forge))
                    val handledAt = handledHere[thread.key]
                    when {
                        !thread.unread || handledAt == null -> thread
                        // Read or done from here after this list was asked for: the list is the one out of date.
                        handledAt > askedAt -> thread.copy(unread = false)
                        // Unread again since: new activity.
                        else -> thread.also { handledHere.remove(thread.key) }
                    }
                }?.let { listed ->
                    // Marked unread here: unread for as long as the forge, which mostly can't be told, lists it read.
                    val kept = keptUnreadDao.of(account.id).toSet()
                    keptUnreadDao.prune(account.id, listed.filter { !it.unread && it.id in kept }.map { it.id })
                    listed.map { if (it.id in kept) it.copy(unread = true) else it }
                }?.let { listed ->
                    // Done here, and nothing happened on it since: done still, though the forge goes on calling it
                    // unread (one that takes a thread away without marking it read). Its date, not its flag, is news.
                    val doneAt = doneDao.of(account.id).associate { it.threadId to it.updatedAtMillis }
                    listed.map { thread ->
                        val done = doneAt[thread.id]
                        if (thread.unread && done != null && thread.updatedAt.toEpochMilli() <= done) thread.copy(unread = false) else thread
                    }
                }
                if (threads != null) {
                    val before = dao.all(account.id).associateBy { it.id }
                    dao.replace(account.id, threads.map { it.toEntity(account.id) })
                    if (settings.settings.first().readElsewhereIsDone) {
                        // The forge doesn't tell a thread read on its site from one marked done there (GitHub lists
                        // both as read): either way it was dealt with, so it leaves the Inbox like one done here.
                        // The first time for an account, that goes for every thread kept as read: the versions that
                        // didn't follow the forge's site let the done ones back in among them.
                        val followed = baselineDao.count(account.id) > 0
                        val done = doneDao.of(account.id).mapTo(HashSet()) { it.threadId }
                        threads.filter { !it.unread && it.id !in done && (!followed || before[it.id]?.unread != false) && !handledHere.containsKey(it.key) }
                            .forEach { doneDao.upsert(DoneEntity(account.id, it.id, it.updatedAt.toEpochMilli())) }
                        if (!followed) baselineDao.upsert(BaselineEntity(account.id))
                    }
                    // Done is remembered while the thread stays listed and read: unread again, it is back for good.
                    doneDao.prune(account.id, threads.filterNot { it.unread }.map { it.id })
                    // Forges that say where a thread's subject stands (Forgejo) save asking for it.
                    val now = clock.millis()
                    val known = threads.mapNotNull { thread ->
                        val ref = thread.subject ?: return@mapNotNull null
                        val state = thread.state ?: return@mapNotNull null
                        SubjectStateEntity(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, state.name, thread.updatedAt.toEpochMilli(), now, ref.storedKind)
                    }
                    if (known.isNotEmpty()) dao.upsertStates(known)
                }
                dao.upsertSync(
                    InboxSyncEntity(
                        accountId = account.id,
                        lastModified = sync.lastModified,
                        pollIntervalSeconds = sync.pollIntervalSeconds,
                        syncedAtMillis = clock.millis(),
                        // First sync: everything already there counts as seen.
                        notifiedUpToMillis = state?.notifiedUpToMillis ?: threads.newestMillis(),
                    ),
                )
                if (threads == null) SyncResult.NotModified else SyncResult.Updated(threads)
            }
        }
    }

    override suspend fun markRead(accountId: String, threadId: String): ForgeResult<Unit> {
        val (account, token) = session(accountId) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        dao.setUnread(account.id, threadId, false)
        keptUnreadDao.delete(account.id, threadId)
        handledHere["${account.id}|$threadId"] = clock.millis()
        return clients.notifications(account.forge).markRead(token, threadId)
            .also { if (it is ForgeResult.Failure) dao.setUnread(account.id, threadId, true) }
    }

    override suspend fun markUnread(accountId: String, threadId: String): ForgeResult<Unit> {
        val (account, token) = session(accountId) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        dao.setUnread(account.id, threadId, true)
        keptUnreadDao.upsert(KeptUnreadEntity(account.id, threadId))
        handledHere.remove("${account.id}|$threadId")
        return clients.notifications(account.forge).markUnread(token, threadId).also {
            if (it is ForgeResult.Failure) {
                dao.setUnread(account.id, threadId, false)
                keptUnreadDao.delete(account.id, threadId)
            }
        }
    }

    override suspend fun markDone(accountId: String, threadId: String): ForgeResult<Unit> {
        val (account, token) = session(accountId) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val api = clients.notifications(account.forge)
        val removed = dao.get(account.id, threadId)
        handledHere["${account.id}|$threadId"] = clock.millis()
        // Gone at once where the forge takes it away; where it only marks it read (Forgejo), once it has.
        if (api.supportsDone) dao.delete(account.id, threadId)
        val result = api.markDone(token, threadId)
        if (result is ForgeResult.Failure) {
            if (api.supportsDone && removed != null) dao.insert(listOf(removed))
            return result
        }
        keptUnreadDao.delete(account.id, threadId)
        if (removed != null) {
            if (!api.supportsDone) dao.setUnread(account.id, threadId, false)
            // Remembered on every forge: GitHub goes on listing a done thread among the read ones, and nothing in
            // its answer says it was done. It stays away until it is unread again.
            doneDao.upsert(DoneEntity(account.id, threadId, removed.updatedAtMillis))
        }
        return result
    }

    override suspend fun unsubscribe(accountId: String, threadId: String): ForgeResult<Unit> {
        val (account, token) = session(accountId) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return when (val result = clients.notifications(account.forge).unsubscribe(token, threadId)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> markDone(accountId, threadId)
        }
    }

    override suspend fun takeThreadsToNotify(): List<NotificationThread> = accounts.accounts.first().flatMap { account ->
        val state = dao.sync(account.id) ?: return@flatMap emptyList()
        val since = state.notifiedUpToMillis ?: return@flatMap emptyList()
        val fresh = dao.all(account.id).filter { it.unread && it.updatedAtMillis > since }.map { it.toModel() }
        if (fresh.isNotEmpty()) dao.upsertSync(state.copy(notifiedUpToMillis = fresh.maxOf { it.updatedAt.toEpochMilli() }))
        fresh
    }

    private suspend fun session(accountId: String): Pair<Account, String>? {
        val account = accounts.accounts.first().firstOrNull { it.id == accountId } ?: return null
        val token = accounts.token(account.id) ?: return null
        return account to token
    }

    private fun SubjectStateEntity.ref() = IssueRef(RepoId(owner, name, ForgeInstance.of(host)), number, isPullRequest)

    /** The kind as it is stored: only where it tells two conversations apart, so the others keep one row each. */
    private val IssueRef.storedKind: Boolean get() = repo.forge.type.numbersMergeRequestsApart && isPullRequest == true

    private fun List<NotificationThread>?.newestMillis(): Long = this?.maxOfOrNull { it.updatedAt.toEpochMilli() } ?: 0L

    companion object {
        const val DEFAULT_POLL_SECONDS = 60

        /** A merge or close can happen without new activity on your thread: ask again after an hour anyway. */
        const val STATE_MAX_AGE_MILLIS = 60 * 60 * 1_000L

        /** How long a state is kept without being asked again. Regression: states were kept for ever. */
        const val STATE_KEEP_MILLIS = 7 * 24 * 60 * 60 * 1_000L

        /** Conversations loaded ahead per sync: two requests each, so a busy inbox doesn't eat the rate limit. */
        const val PREFETCHED_CONVERSATIONS = 10

        /** Conversations loaded at once, across accounts: far forges answer slowly, so waiting on one at a time adds up. */
        const val PREFETCH_CONCURRENCY = 6
    }
}
