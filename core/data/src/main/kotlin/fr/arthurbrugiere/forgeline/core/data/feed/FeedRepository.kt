package fr.arthurbrugiere.forgeline.core.data.feed

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkDao
import fr.arthurbrugiere.forgeline.core.data.reading.advance
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class FeedSnapshot(val events: List<FeedEvent>, val syncedAtMillis: Long?, val hasMore: Boolean)

interface FeedRepository {
    /** The active account's Feed, newest first; empty when signed out. */
    fun observe(): Flow<FeedSnapshot>

    /** Replaces the Feed with its latest page. Unless [force]d, waits for the forge's poll interval and asks only for changes. */
    suspend fun refresh(force: Boolean = false): ForgeResult<Unit>

    /** Appends the next older page. */
    suspend fun loadMore(): ForgeResult<Unit>

    /** The newest activity read in the Feed so far (its time), null before any; per account. */
    suspend fun readUpTo(): Instant?

    /** Records that activity from [at] was read; only ever moves the mark to newer activity. */
    suspend fun markRead(itemKey: String, at: Instant)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultFeedRepository @Inject constructor(
    private val dao: FeedDao,
    private val api: FeedApi,
    private val accounts: AccountRepository,
    private val marks: ReadingMarkDao,
    private val clock: Clock,
) : FeedRepository {

    override suspend fun readUpTo(): Instant? {
        val account = accounts.activeAccount.first() ?: return null
        return marks.get("feed:${account.id}")?.position?.let(Instant::ofEpochMilli)
    }

    override suspend fun markRead(itemKey: String, at: Instant) {
        val account = accounts.activeAccount.first() ?: return
        marks.advance("feed:${account.id}", itemKey, at.toEpochMilli(), clock.millis())
    }

    private val lock = Mutex()

    override fun observe(): Flow<FeedSnapshot> = accounts.activeAccount.flatMapLatest { account ->
        if (account == null) {
            flowOf(FeedSnapshot(emptyList(), null, hasMore = false))
        } else {
            combine(dao.observe(account.id), dao.observeSync(account.id)) { events, sync ->
                FeedSnapshot(events.mapNotNull { it.toModel() }, sync?.syncedAtMillis, hasMore = sync?.nextPage != null)
            }
        }
    }

    override suspend fun refresh(force: Boolean): ForgeResult<Unit> = lock.withLock {
        val (account, token) = session() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val state = dao.sync(account.id)
        val interval = (state?.pollIntervalSeconds ?: DEFAULT_POLL_SECONDS) * 1_000L
        if (!force && state != null && clock.millis() - state.syncedAtMillis < interval) return ForgeResult.Success(Unit)
        when (val result = api.receivedEvents(token, account.user.login, page = 1, ifModifiedSince = if (force) null else state?.lastModified)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> {
                val page = result.value
                page.events?.let { events -> dao.replace(account.id, events.map { it.toEntity(account.id) }) }
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

    override suspend fun loadMore(): ForgeResult<Unit> = lock.withLock {
        val (account, token) = session() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val state = dao.sync(account.id) ?: return ForgeResult.Success(Unit)
        val next = state.nextPage ?: return ForgeResult.Success(Unit)
        when (val result = api.receivedEvents(token, account.user.login, page = next)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> {
                dao.insert(result.value.events.orEmpty().map { it.toEntity(account.id) })
                dao.upsertSync(state.copy(nextPage = result.value.nextPage))
                ForgeResult.Success(Unit)
            }
        }
    }

    private suspend fun session(): Pair<Account, String>? {
        val account = accounts.activeAccount.first() ?: return null
        val token = accounts.token(account.id) ?: return null
        return account to token
    }

    private companion object {
        const val DEFAULT_POLL_SECONDS = 60
    }
}
