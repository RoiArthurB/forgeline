package fr.arthurbrugiere.forgeline.core.data.inbox

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

data class InboxSnapshot(val threads: List<NotificationThread>, val syncedAtMillis: Long?)

sealed interface SyncResult {
    data class Updated(val threads: List<NotificationThread>) : SyncResult

    data object NotModified : SyncResult

    data object SignedOut : SyncResult

    data class Failed(val error: ForgeError) : SyncResult
}

interface InboxRepository {
    /** The active account's inbox, newest first; empty when signed out. */
    fun observe(): Flow<InboxSnapshot>

    /** Unless [force]d, waits for the forge's poll interval and asks only for changes. */
    suspend fun sync(force: Boolean = false): SyncResult

    suspend fun markRead(threadId: String): ForgeResult<Unit>

    suspend fun markDone(threadId: String): ForgeResult<Unit>

    suspend fun unsubscribe(threadId: String): ForgeResult<Unit>

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
    private val api: NotificationsApi,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : InboxRepository {

    private val syncLock = Mutex()

    override fun observe(): Flow<InboxSnapshot> = accounts.activeAccount.flatMapLatest { account ->
        if (account == null) {
            flowOf(InboxSnapshot(emptyList(), null))
        } else {
            combine(dao.observe(account.id), dao.observeSync(account.id), dao.observeStates()) { threads, sync, states ->
                val byRef = states.associateBy { IssueRef(RepoId(it.owner, it.name), it.number) }
                InboxSnapshot(
                    threads.map { entity ->
                        val thread = entity.toModel()
                        val state = thread.subject?.let { byRef[it] }?.state?.let { name -> SubjectState.entries.firstOrNull { it.name == name } }
                        thread.copy(state = state)
                    },
                    sync?.syncedAtMillis,
                )
            }
        }
    }

    override suspend fun sync(force: Boolean): SyncResult = syncThreads(force).also { result ->
        // Threads first, on screen at once; where their issues and pull requests stand follows.
        if (result is SyncResult.Updated || result is SyncResult.NotModified) refreshStates()
    }

    /** Asks where the inbox's issues and pull requests stand, for those never asked, moved on since, or asked long ago. */
    private suspend fun refreshStates() {
        val (account, token) = session() ?: return
        val known = dao.states().associateBy { IssueRef(RepoId(it.owner, it.name), it.number) }
        val now = clock.millis()
        val stale = dao.all(account.id).map { it.toModel() }.mapNotNull { thread ->
            val ref = thread.subject ?: return@mapNotNull null
            val state = known[ref]
            val fresh = state != null && state.threadUpdatedAtMillis >= thread.updatedAt.toEpochMilli() &&
                now - state.checkedAtMillis < STATE_MAX_AGE_MILLIS
            if (fresh) null else ref to thread.updatedAt.toEpochMilli()
        }.toMap()
        if (stale.isEmpty()) return
        val states = (api.subjectStates(token, stale.keys.toList()) as? ForgeResult.Success)?.value ?: return
        dao.upsertStates(states.map { (ref, state) -> SubjectStateEntity(ref.repo.owner, ref.repo.name, ref.number, state.name, stale.getValue(ref), now) })
    }

    private suspend fun syncThreads(force: Boolean): SyncResult = syncLock.withLock {
        val (account, token) = session() ?: return SyncResult.SignedOut
        val state = dao.sync(account.id)
        val interval = (state?.pollIntervalSeconds ?: DEFAULT_POLL_SECONDS) * 1_000L
        if (!force && state != null && clock.millis() - state.syncedAtMillis < interval) return SyncResult.NotModified
        when (val result = api.threads(token, if (force) null else state?.lastModified)) {
            is ForgeResult.Failure -> SyncResult.Failed(result.error)
            is ForgeResult.Success -> {
                val sync = result.value
                val threads = sync.threads
                if (threads != null) dao.replace(account.id, threads.map { it.toEntity(account.id) })
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

    override suspend fun markRead(threadId: String): ForgeResult<Unit> {
        val (account, token) = session() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        dao.setUnread(account.id, threadId, false)
        return api.markRead(token, threadId).also { if (it is ForgeResult.Failure) dao.setUnread(account.id, threadId, true) }
    }

    override suspend fun markDone(threadId: String): ForgeResult<Unit> {
        val (account, token) = session() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val removed = dao.get(account.id, threadId)
        dao.delete(account.id, threadId)
        return api.markDone(token, threadId).also { if (it is ForgeResult.Failure && removed != null) dao.insert(listOf(removed)) }
    }

    override suspend fun unsubscribe(threadId: String): ForgeResult<Unit> {
        val (_, token) = session() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return when (val result = api.unsubscribe(token, threadId)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> markDone(threadId)
        }
    }

    override suspend fun takeThreadsToNotify(): List<NotificationThread> {
        val account = accounts.activeAccount.first() ?: return emptyList()
        val state = dao.sync(account.id) ?: return emptyList()
        val since = state.notifiedUpToMillis ?: return emptyList()
        val fresh = dao.all(account.id).filter { it.unread && it.updatedAtMillis > since }.map { it.toModel() }
        if (fresh.isNotEmpty()) dao.upsertSync(state.copy(notifiedUpToMillis = fresh.maxOf { it.updatedAt.toEpochMilli() }))
        return fresh
    }

    private suspend fun session(): Pair<Account, String>? {
        val account = accounts.activeAccount.first() ?: return null
        val token = accounts.token(account.id) ?: return null
        return account to token
    }

    private fun List<NotificationThread>?.newestMillis(): Long = this?.maxOfOrNull { it.updatedAt.toEpochMilli() } ?: 0L

    private companion object {
        const val DEFAULT_POLL_SECONDS = 60

        /** A merge or close can happen without new activity on your thread: ask again after an hour anyway. */
        const val STATE_MAX_AGE_MILLIS = 60 * 60 * 1_000L
    }
}
