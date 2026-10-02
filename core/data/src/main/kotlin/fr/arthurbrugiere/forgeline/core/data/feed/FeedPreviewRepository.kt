package fr.arthurbrugiere.forgeline.core.data.feed

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineDispatcher
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** A repository preview (`repo:owner/name`) or a pull request title (`pull:owner/name#12`). */
@Entity(tableName = "feed_previews")
data class FeedPreviewEntity(
    @PrimaryKey val key: String,
    val description: String?,
    val language: String?,
    val stars: Int?,
    val title: String?,
    val fetchedAtMillis: Long,
)

@Dao
interface FeedPreviewDao {
    @Query("SELECT * FROM feed_previews")
    fun observeAll(): Flow<List<FeedPreviewEntity>>

    @Query("SELECT key, fetchedAtMillis FROM feed_previews")
    suspend fun freshness(): List<PreviewFreshness>

    @Upsert
    suspend fun upsert(previews: List<FeedPreviewEntity>)

    /** Deletes the previews of [host]'s repositories and pull requests. */
    @Query("DELETE FROM feed_previews WHERE key LIKE 'repo:' || :host || '/%' OR key LIKE 'pull:' || :host || '/%'")
    suspend fun clear(host: String)

    @Query("DELETE FROM feed_previews WHERE fetchedAtMillis < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class PreviewFreshness(val key: String, val fetchedAtMillis: Long)

interface FeedPreviewRepository {
    fun observe(): Flow<FeedPreviews>

    /**
     * Fetches what's missing or stale among [repos] and [pulls]. The fetches finish even if the caller leaves; a failed
     * one is tried again on a later visit, after a short wait.
     */
    suspend fun ensure(repos: Set<RepoId>, pulls: Set<IssueRef>)
}

@Singleton
class DefaultFeedPreviewRepository @Inject constructor(
    private val dao: FeedPreviewDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val clock: Clock,
    @param:BackgroundScope private val scope: CoroutineScope,
    @param:Computation private val computation: CoroutineDispatcher,
) : FeedPreviewRepository {

    private val lock = Mutex()

    /** Keys being fetched now, so scrolling back and forth never asks twice at once. */
    private val inFlight = mutableSetOf<String>()

    /**
     * When each key last failed. Regression: failures (and fetches cut off by leaving the Feed) were remembered as
     * asked for the whole process, so a pull request's title stayed blank until the app restarted.
     */
    private val failedAt = mutableMapOf<String, Long>()

    override fun observe(): Flow<FeedPreviews> = dao.observeAll().map { rows ->
        val repos = mutableMapOf<RepoId, RepoPreview>()
        val pulls = mutableMapOf<IssueRef, String>()
        rows.forEach { row ->
            when {
                row.key.startsWith(REPO) -> repoId(row.key.removePrefix(REPO))?.let { repos[it] = RepoPreview(row.description, row.language, row.stars ?: 0) }
                row.key.startsWith(PULL) -> pullRef(row.key.removePrefix(PULL))?.let { ref -> row.title?.let { pulls[ref] = it } }
            }
        }
        FeedPreviews(repos, pulls)
    }.flowOn(computation)

    override suspend fun ensure(repos: Set<RepoId>, pulls: Set<IssueRef>) {
        val now = clock.millis()
        val wanted = lock.withLock {
            val fresh = dao.freshness().filter { now - it.fetchedAtMillis < MAX_AGE.inWholeMilliseconds }.map { it.key }.toSet()
            val keys = repos.map { it.previewKey() } + pulls.map { it.previewKey() }
            keys.filter { key ->
                key !in fresh && key !in inFlight && failedAt[key]?.let { now - it < RETRY_AFTER.inWholeMilliseconds } != true
            }.also { inFlight += it }
        }
        if (wanted.isEmpty()) return
        // In the app's scope: leaving the Feed mid-fetch no longer loses the answers.
        scope.async { fetch(wanted, now) }.await()
    }

    private suspend fun fetch(wanted: List<String>, now: Long) {
        val permits = Semaphore(CONCURRENCY)
        val fetched = try {
            coroutineScope {
                wanted.map { key -> async { permits.withPermit { key to preview(key, now) } } }.awaitAll()
            }
        } finally {
            lock.withLock { inFlight -= wanted.toSet() }
        }
        lock.withLock {
            fetched.forEach { (key, preview) -> if (preview == null) failedAt[key] = now else failedAt -= key }
        }
        fetched.mapNotNull { it.second }.takeIf { it.isNotEmpty() }?.let { dao.upsert(it) }
        dao.deleteOlderThan(now - KEEP.inWholeMilliseconds)
    }

    /** One preview, or null when it couldn't be had: one bad answer never takes the others down with it. */
    private suspend fun preview(key: String, now: Long): FeedPreviewEntity? = try {
        when {
            key.startsWith(REPO) -> repoId(key.removePrefix(REPO))?.let { id ->
                (clients.repos(id.forge).repo(accounts.tokenOn(id.forge), id) as? ForgeResult.Success)?.value?.let {
                    FeedPreviewEntity(key, it.description, it.language, it.stars, null, now)
                }
            }
            else -> pullRef(key.removePrefix(PULL))?.let { ref ->
                (clients.issues(ref.repo.forge).title(accounts.tokenOn(ref.repo.forge), ref) as? ForgeResult.Success)?.value?.let {
                    FeedPreviewEntity(key, null, null, null, it, now)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val REPO = "repo:"
        const val PULL = "pull:"
        /** Previews asked at once: far forges answer slowly, so small waves add up. */
        const val CONCURRENCY = 8
        val MAX_AGE = 24.hours

        /** A failed preview waits this long before being asked again. */
        val RETRY_AFTER = 1.minutes
        val KEEP = 7.days

        fun RepoId.previewKey() = REPO + key.lowercase(Locale.ROOT)
        fun IssueRef.previewKey() = PULL + repo.key.lowercase(Locale.ROOT) + "#" + number

        fun repoId(value: String): RepoId? = RepoId.fromKey(value)

        fun pullRef(value: String): IssueRef? {
            val repo = repoId(value.substringBefore('#')) ?: return null
            val number = value.substringAfter('#', "").toIntOrNull() ?: return null
            return IssueRef(repo, number)
        }
    }
}
