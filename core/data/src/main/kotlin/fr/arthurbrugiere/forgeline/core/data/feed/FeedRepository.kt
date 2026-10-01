package fr.arthurbrugiere.forgeline.core.data.feed

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkDao
import fr.arthurbrugiere.forgeline.core.data.reading.advance
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class FeedSnapshot(val events: List<FeedEvent>, val syncedAtMillis: Long?, val hasMore: Boolean)

interface FeedRepository {
    /**
     * Every signed-in account's Feed as one timeline, newest first; empty when signed out. Only events down to the
     * horizon show: the point every account has loaded back to, so rows never move when an older page arrives.
     */
    fun observe(): Flow<FeedSnapshot>

    /**
     * Replaces each account's Feed with its latest page. Unless [force]d, waits for each forge's poll interval and asks
     * only for changes. Returns once every forge has answered; [onFirstFresh] is called earlier, as soon as the first
     * forge's answer is saved, so a refresh indicator needn't wait for the slowest forge.
     */
    suspend fun refresh(force: Boolean = false, onFirstFresh: () -> Unit = {}): ForgeResult<Unit>

    /** Pages back the account whose loaded Feed ends the latest, which moves the horizon down. */
    suspend fun loadMore(): ForgeResult<Unit>

    /** The newest activity read in the Feed so far (its time), null before any. */
    suspend fun readUpTo(): Instant?

    /** Records that activity from [at] was read; only ever moves the mark to newer activity. */
    suspend fun markRead(itemKey: String, at: Instant)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultFeedRepository @Inject constructor(
    private val dao: FeedDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val marks: ReadingMarkDao,
    private val clock: Clock,
) : FeedRepository {

    override suspend fun readUpTo(): Instant? = marks.get(MARK_LIST)?.position?.let(Instant::ofEpochMilli)

    override suspend fun markRead(itemKey: String, at: Instant) = marks.advance(MARK_LIST, itemKey, at.toEpochMilli(), clock.millis())

    /** One lock per account: an account's refresh and paging never overlap, and accounts never wait for each other. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockOf(account: Account) = locks.getOrPut(account.id) { Mutex() }

    /** One page loaded at a time: the next choice of account depends on what the last page brought. */
    private val pagingLock = Mutex()

    override fun observe(): Flow<FeedSnapshot> = accounts.accounts.flatMapLatest { signedIn ->
        if (signedIn.isEmpty()) {
            flowOf(FeedSnapshot(emptyList(), null, hasMore = false))
        } else {
            val ids = signedIn.map { it.id }.toSet()
            combine(dao.observeAll(), dao.observeSyncs()) { events, syncs ->
                val mine = events.filter { it.accountId in ids }
                val states = syncs.filter { it.accountId in ids }
                // Accounts with older pages left end their loaded Feed somewhere: nothing below the latest of those ends shows yet.
                val horizon = states.filter { it.nextPage != null }
                    .mapNotNull { state -> mine.filter { it.accountId == state.accountId }.minOfOrNull { it.createdAtMillis } }
                    .maxOrNull()
                FeedSnapshot(
                    events = mine.filter { horizon == null || it.createdAtMillis >= horizon }.mapNotNull { it.toModel() },
                    syncedAtMillis = states.takeIf { it.size == ids.size }?.minOfOrNull { it.syncedAtMillis },
                    hasMore = states.any { it.nextPage != null },
                )
            }
        }
    }

    override suspend fun refresh(force: Boolean, onFirstFresh: () -> Unit): ForgeResult<Unit> {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return ForgeResult.Failure(ForgeError.Unauthorized)
        val reported = AtomicBoolean(false)
        val results = coroutineScope {
            signedIn.map { account ->
                async {
                    refresh(account, force).also { if (it is ForgeResult.Success && reported.compareAndSet(false, true)) onFirstFresh() }
                }
            }.awaitAll()
        }
        // One forge failing doesn't hide the others; it's reported only when every account failed.
        return results.firstOrNull { it is ForgeResult.Success } ?: results.first()
    }

    private suspend fun refresh(account: Account, force: Boolean): ForgeResult<Unit> = lockOf(account).withLock {
        val token = accounts.token(account.id) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val state = dao.sync(account.id)
        val interval = (state?.pollIntervalSeconds ?: DEFAULT_POLL_SECONDS) * 1_000L
        if (!force && state != null && clock.millis() - state.syncedAtMillis < interval) return ForgeResult.Success(Unit)
        val api = clients.feed(account.forge)
        when (val result = api.receivedEvents(token, account.user.login, page = 1, ifModifiedSince = if (force) null else state?.lastModified)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> {
                val page = result.value
                page.events?.let { events -> dao.replace(account.id, events.map { it.on(account).toEntity(account.id) }) }
                dao.upsertSync(
                    FeedSyncEntity(
                        accountId = account.id,
                        lastModified = page.lastModified,
                        pollIntervalSeconds = page.pollIntervalSeconds,
                        syncedAtMillis = clock.millis(),
                        // Unchanged: the pages already loaded are still valid.
                        nextPage = if (page.events == null) state?.nextPage else page.nextPage,
                    ),
                )
                ForgeResult.Success(Unit)
            }
        }
    }

    override suspend fun loadMore(): ForgeResult<Unit> = pagingLock.withLock {
        val signedIn = accounts.accounts.first()
        // The account whose loaded Feed ends the latest holds the horizon up: page it back.
        val behind = signedIn.mapNotNull { account ->
            val state = dao.sync(account.id)?.takeIf { it.nextPage != null } ?: return@mapNotNull null
            Triple(account, state, dao.oldest(account.id) ?: Long.MAX_VALUE)
        }.maxByOrNull { it.third } ?: return ForgeResult.Success(Unit)
        val (account, _, _) = behind
        lockOf(account).withLock {
            // A refresh may have run meanwhile: page on from where it left things.
            val state = dao.sync(account.id)?.takeIf { it.nextPage != null } ?: return ForgeResult.Success(Unit)
            val token = accounts.token(account.id) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
            when (val result = clients.feed(account.forge).receivedEvents(token, account.user.login, page = state.nextPage!!)) {
                is ForgeResult.Failure -> result
                is ForgeResult.Success -> {
                    dao.insert(result.value.events.orEmpty().map { it.on(account).toEntity(account.id) })
                    dao.upsertSync(state.copy(nextPage = result.value.nextPage))
                    ForgeResult.Success(Unit)
                }
            }
        }
    }

    /** An account's Feed is about repositories on its forge, whatever the client filled in. */
    private fun FeedEvent.on(account: Account) = copy(repo = repo.copy(forge = account.forge))

    private companion object {
        const val DEFAULT_POLL_SECONDS = 60

        /** One Feed across accounts, so one reading mark. */
        const val MARK_LIST = "feed"
    }
}
