package fr.arthurbrugiere.forgeline.core.data.feed

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkDao
import fr.arthurbrugiere.forgeline.core.data.reading.advance
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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
     * Replaces each account's Feed with its latest page and what its starred repositories published. Unless [force]d,
     * reads each only when due (the forge's poll interval, half an hour for the starred activity) and asks only for
     * changes. Every forge is read before anything is saved, then all of it in one change: the timeline changes once
     * per refresh, never while a slow forge is still answering.
     */
    suspend fun refresh(force: Boolean = false): ForgeResult<Unit>

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
    @param:Computation private val computation: CoroutineDispatcher,
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
                // A release can come twice, from a watched repository's events and from the starred ones, and so can a
                // new repository (GitHub's events used to say it; it's now asked apart): each shows once.
                val releases = HashSet<String>()
                val created = (mine + starred).filter { it.action == "created_repo" && !it.id.startsWith(NEW_REPO) }
                    .mapTo(HashSet()) { "${it.host}/${it.owner}/${it.name}".lowercase() }
                FeedSnapshot(
                    events = (mine + starred).filter { horizon == null || it.createdAtMillis >= horizon }
                        .sortedByDescending { it.createdAtMillis }
                        .mapNotNull { it.toModel() }
                        .filter { event -> (event.action as? FeedAction.Released)?.let { releases.add("${event.repo.key}#${it.tag}") } ?: true }
                        .filter { event -> !event.id.startsWith(NEW_REPO) || created.add(event.repo.key.lowercase()) },
                    syncedAtMillis = states.takeIf { it.size == ids.size }?.minOfOrNull { it.syncedAtMillis },
                    hasMore = states.any { it.nextPage != null },
                )
            }
        }
        // Regression: the timeline was sorted and merged on whichever thread read it, which was the main thread.
    }.flowOn(computation)

    /** One refresh at a time: the next one starts from what the last one saved. */
    private val refreshLock = Mutex()

    override suspend fun refresh(force: Boolean): ForgeResult<Unit> = refreshLock.withLock {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return ForgeResult.Failure(ForgeError.Unauthorized)
        // Regression: each forge's answer, then the starred activity, landed as it came, so rows moved under the reader
        // and some arrived below "Where you left off" a refresh later. Everything is read first, then shown in one change.
        val reads = coroutineScope { signedIn.map { account -> async { read(account, force) } }.awaitAll() }
        // Paging holds an account's lock while it loads: its page is kept or replaced whole, never half-written.
        val held = signedIn.sortedBy { it.id }.map(::lockOf).onEach { it.lock() }
        try {
            val events = mutableMapOf<String, List<FeedEventEntity>>()
            val syncs = mutableListOf<FeedSyncEntity>()
            reads.forEach { read -> read.into(events, syncs) }
            if (events.isNotEmpty() || syncs.isNotEmpty()) dao.commit(events, syncs)
        } finally {
            held.forEach { it.unlock() }
        }
        // One forge failing doesn't hide the others; it's reported only when every account failed.
        val results = reads.map { it.result }
        results.firstOrNull { it is ForgeResult.Success } ?: results.first()
    }

    /** What one account's refresh read, saved with every other account's. */
    private inner class AccountRead(
        val account: Account,
        val page: ForgeResult<FeedPage>?,
        val starred: List<FeedEventEntity>?,
        val starredAtMillis: Long,
    ) {
        val result: ForgeResult<Unit> = if (page is ForgeResult.Failure) page else ForgeResult.Success(Unit)

        suspend fun into(events: MutableMap<String, List<FeedEventEntity>>, syncs: MutableList<FeedSyncEntity>) {
            (page as? ForgeResult.Success)?.value?.let { page ->
                page.events?.let { events[account.id] = it.map { event -> event.on(account).toEntity(account.id) } }
                syncs += FeedSyncEntity(
                    accountId = account.id,
                    lastModified = page.lastModified,
                    pollIntervalSeconds = page.pollIntervalSeconds,
                    syncedAtMillis = clock.millis(),
                    // Unchanged: the pages already loaded, paging included, are still valid.
                    nextPage = if (page.events == null) dao.sync(account.id)?.nextPage else page.nextPage,
                )
            }
            starred?.let {
                val key = STARRED + account.id
                events[key] = it
                syncs += FeedSyncEntity(key, lastModified = null, pollIntervalSeconds = null, syncedAtMillis = starredAtMillis, nextPage = null)
            }
        }
    }

    /**
     * Reads [account]'s latest page and what its starred repositories published, both at once, each when due: its own
     * events after the forge's poll interval, the starred activity every half hour (one ask is several slow requests).
     * A [force]d refresh reads both. Null where nothing was asked or the starred activity couldn't be read.
     */
    private suspend fun read(account: Account, force: Boolean): AccountRead = coroutineScope {
        val token = accounts.token(account.id) ?: return@coroutineScope AccountRead(account, ForgeResult.Failure(ForgeError.Unauthorized), null, 0)
        val state = dao.sync(account.id)
        val starredState = dao.sync(STARRED + account.id)
        val interval = (state?.pollIntervalSeconds ?: DEFAULT_POLL_SECONDS) * 1_000L
        val starred = async {
            if (!force && starredState != null && clock.millis() - starredState.syncedAtMillis < STARRED_INTERVAL_MILLIS) return@async null
            val since = clock.instant().minus(Duration.ofDays(STARRED_WINDOW_DAYS))
            // A failure keeps what was there and is asked again next time.
            (clients.feed(account.forge).starredActivity(token, since) as? ForgeResult.Success)?.value?.map { it.on(account).toEntity(STARRED + account.id) }
        }
        val page = if (!force && state != null && clock.millis() - state.syncedAtMillis < interval) {
            null
        } else {
            clients.feed(account.forge).receivedEvents(token, account.user.login, page = 1, ifModifiedSince = if (force) null else state?.lastModified)
        }
        // The forge turned the token down: nothing will sync for this account until it signs in again.
        if ((page as? ForgeResult.Failure)?.error == ForgeError.Unauthorized) accounts.markSignInEnded(account.id)
        AccountRead(account, page, starred.await(), clock.millis())
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

        const val STARRED = STARRED_PREFIX

        /** How the repositories GitHub's events no longer announce are named when they're asked apart. */
        const val NEW_REPO = "new-repo:"
        const val STARRED_WINDOW_DAYS = 30L
        const val STARRED_INTERVAL_MILLIS = 30 * 60_000L

        /** One Feed across accounts, so one reading mark. */
        const val MARK_LIST = "feed"
    }
}
