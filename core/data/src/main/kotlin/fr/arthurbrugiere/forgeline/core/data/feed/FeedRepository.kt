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
import java.time.Duration
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
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
    @param:BackgroundScope private val scope: CoroutineScope,
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
            val starredIds = ids.map { STARRED + it }.toSet()
            combine(dao.observeAll(), dao.observeSyncs()) { events, syncs ->
                val mine = events.filter { it.accountId in ids }
                // What starred repositories published: shown with the rest, but never moving the horizon, which only
                // an account's own pages do.
                val starred = events.filter { it.accountId in starredIds }
                val states = syncs.filter { it.accountId in ids }
                // Accounts with older pages left end their loaded Feed somewhere: nothing below the latest of those ends shows yet.
                val horizon = states.filter { it.nextPage != null }
                    .mapNotNull { state -> mine.filter { it.accountId == state.accountId }.minOfOrNull { it.createdAtMillis } }
                    .maxOrNull()
                // A release can come twice, from a watched repository's events and from the starred ones: it shows once.
                val releases = HashSet<String>()
                FeedSnapshot(
                    events = (mine + starred).filter { horizon == null || it.createdAtMillis >= horizon }
                        .sortedByDescending { it.createdAtMillis }
                        .mapNotNull { it.toModel() }
                        .filter { event -> (event.action as? FeedAction.Released)?.let { releases.add("${event.repo.key}#${it.tag}") } ?: true },
                    syncedAtMillis = states.takeIf { it.size == ids.size }?.minOfOrNull { it.syncedAtMillis },
                    hasMore = states.any { it.nextPage != null },
                )
            }
        }
    }

    override suspend fun refresh(force: Boolean, onFirstFresh: () -> Unit): ForgeResult<Unit> {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return ForgeResult.Failure(ForgeError.Unauthorized)
        // Starred repositories take many seconds to read: in the background, never part of the refresh.
        scope.launch { syncStarred(force) }
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

    /**
     * Reads what each account's starred repositories published in the last [STARRED_WINDOW_DAYS] days (releases and,
     * on GitHub, announcements) and keeps it beside the account's own Feed. At most every half hour, or every five
     * minutes when [force]d: one ask is several slow requests. A failure keeps what was there and is asked again next time.
     */
    suspend fun syncStarred(force: Boolean = false) {
        coroutineScope { accounts.accounts.first().forEach { account -> launch { syncStarred(account, force) } } }
    }

    private suspend fun syncStarred(account: Account, force: Boolean) = starredLocks.getOrPut(account.id) { Mutex() }.withLock {
        val key = STARRED + account.id
        val wait = if (force) STARRED_FORCED_MILLIS else STARRED_INTERVAL_MILLIS
        dao.sync(key)?.let { if (clock.millis() - it.syncedAtMillis < wait) return@withLock }
        val token = accounts.token(account.id) ?: return@withLock
        val since = clock.instant().minus(Duration.ofDays(STARRED_WINDOW_DAYS))
        val result = clients.feed(account.forge).starredActivity(token, since)
        if (result is ForgeResult.Success) {
            dao.replace(key, result.value.map { it.on(account).toEntity(key) })
            dao.upsertSync(FeedSyncEntity(key, lastModified = null, pollIntervalSeconds = null, syncedAtMillis = clock.millis(), nextPage = null))
        }
    }

    private val starredLocks = ConcurrentHashMap<String, Mutex>()

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

        const val STARRED = STARRED_PREFIX
        const val STARRED_WINDOW_DAYS = 30L
        const val STARRED_INTERVAL_MILLIS = 30 * 60_000L
        const val STARRED_FORCED_MILLIS = 5 * 60_000L

        /** One Feed across accounts, so one reading mark. */
        const val MARK_LIST = "feed"
    }
}
