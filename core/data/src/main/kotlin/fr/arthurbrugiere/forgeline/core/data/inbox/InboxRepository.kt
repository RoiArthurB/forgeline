package fr.arthurbrugiere.forgeline.core.data.inbox

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
     */
    suspend fun sync(force: Boolean = false, waitForFollowUps: Boolean = false): SyncResult

    // Threads are identified by their account and id: two forges can use the same thread id.
    suspend fun markRead(accountId: String, threadId: String): ForgeResult<Unit>

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
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val conversations: IssueRepository,
    private val clock: Clock,
    @param:BackgroundScope private val scope: CoroutineScope,
    @param:Computation private val computation: CoroutineDispatcher,
) : InboxRepository {

    /** One lock per account: two syncs of one account never overlap, and accounts never wait for each other. */
    private val syncLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockOf(account: Account) = syncLocks.getOrPut(account.id) { Mutex() }

    /** Follow-ups (states, conversations) run one round at a time: a newer sync's round waits for the current one. */
    private val followUpLock = Mutex()

    override fun observe(): Flow<InboxSnapshot> = accounts.accounts.flatMapLatest { signedIn ->
        if (signedIn.isEmpty()) {
            flowOf(InboxSnapshot(emptyList(), null))
        } else {
            val ids = signedIn.map { it.id }.toSet()
            combine(dao.observeAll(), dao.observeSyncs(), dao.observeStates(), doneDao.observe()) { threads, syncs, states, done ->
                val byRef = states.associateBy { it.ref() }
                val doneAt = done.associate { (it.accountId to it.threadId) to it.updatedAtMillis }
                InboxSnapshot(
                    threads.filter { it.accountId in ids }
                        // Done where the forge couldn't: gone until something new happens on it.
                        .filterNot { entity -> doneAt[entity.accountId to entity.id]?.let { entity.updatedAtMillis <= it } == true }
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

    override suspend fun sync(force: Boolean, waitForFollowUps: Boolean): SyncResult {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return SyncResult.SignedOut
        val results = coroutineScope { signedIn.map { account -> async { syncAccount(account, force) } }.awaitAll() }
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
        coroutineScope {
            synced.forEach { account ->
                launch { refreshStates(account) }
                launch { prefetchConversations(account, permits) }
            }
        }
    }

    /** Asks where the inbox's issues and pull requests stand, for those never asked, moved on since, or asked long ago. */
    private suspend fun refreshStates(account: Account) {
        val token = accounts.token(account.id) ?: return
        val known = dao.states().associateBy { it.ref() }
        val now = clock.millis()
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
                SubjectStateEntity(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, state.name, stale.getValue(ref), now)
            },
        )
    }

    private suspend fun syncAccount(account: Account, force: Boolean): SyncResult = lockOf(account).withLock {
        val token = accounts.token(account.id) ?: return SyncResult.SignedOut
        val state = dao.sync(account.id)
        val interval = (state?.pollIntervalSeconds ?: DEFAULT_POLL_SECONDS) * 1_000L
        if (!force && state != null && clock.millis() - state.syncedAtMillis < interval) return SyncResult.NotModified
        when (val result = clients.notifications(account.forge).threads(token, if (force) null else state?.lastModified)) {
            is ForgeResult.Failure -> SyncResult.Failed(result.error)
            is ForgeResult.Success -> {
                val sync = result.value
                // An account's threads are on its forge, whatever the client filled in.
                val threads = sync.threads?.map { it.copy(accountId = account.id, repo = it.repo.copy(forge = account.forge)) }
                if (threads != null) {
                    dao.replace(account.id, threads.map { it.toEntity(account.id) })
                    doneDao.prune(account.id, threads.map { it.id })
                    // Forges that say where a thread's subject stands (Forgejo) save asking for it.
                    val now = clock.millis()
                    val known = threads.mapNotNull { thread ->
                        val ref = thread.subject ?: return@mapNotNull null
                        val state = thread.state ?: return@mapNotNull null
                        SubjectStateEntity(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, state.name, thread.updatedAt.toEpochMilli(), now)
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
        return clients.notifications(account.forge).markRead(token, threadId)
            .also { if (it is ForgeResult.Failure) dao.setUnread(account.id, threadId, true) }
    }

    override suspend fun markDone(accountId: String, threadId: String): ForgeResult<Unit> {
        val (account, token) = session(accountId) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val api = clients.notifications(account.forge)
        val removed = dao.get(account.id, threadId)
        if (!api.supportsDone) {
            // The forge only marks it read: the Inbox remembers it was done, at its current activity.
            val result = api.markDone(token, threadId)
            if (result is ForgeResult.Success && removed != null) {
                dao.setUnread(account.id, threadId, false)
                doneDao.upsert(DoneEntity(account.id, threadId, removed.updatedAtMillis))
            }
            return result
        }
        dao.delete(account.id, threadId)
        return api.markDone(token, threadId).also { if (it is ForgeResult.Failure && removed != null) dao.insert(listOf(removed)) }
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

    private fun SubjectStateEntity.ref() = IssueRef(RepoId(owner, name, ForgeInstance.of(host)), number)

    private fun List<NotificationThread>?.newestMillis(): Long = this?.maxOfOrNull { it.updatedAt.toEpochMilli() } ?: 0L

    companion object {
        const val DEFAULT_POLL_SECONDS = 60

        /** A merge or close can happen without new activity on your thread: ask again after an hour anyway. */
        const val STATE_MAX_AGE_MILLIS = 60 * 60 * 1_000L

        /** Conversations loaded ahead per sync: two requests each, so a busy inbox doesn't eat the rate limit. */
        const val PREFETCHED_CONVERSATIONS = 10

        /** Conversations loaded at once, across accounts: far forges answer slowly, so waiting on one at a time adds up. */
        const val PREFETCH_CONCURRENCY = 6
    }
}
