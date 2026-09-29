package fr.arthurbrugiere.forgeline.core.data.feed

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
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
import javax.inject.Inject
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

    @Query("DELETE FROM feed_previews WHERE fetchedAtMillis < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class PreviewFreshness(val key: String, val fetchedAtMillis: Long)

interface FeedPreviewRepository {
    fun observe(): Flow<FeedPreviews>

    /** Fetches what's missing or stale among [repos] and [pulls]; failures are left for a later visit. */
    suspend fun ensure(repos: Set<RepoId>, pulls: Set<IssueRef>)
}

class DefaultFeedPreviewRepository @Inject constructor(
    private val dao: FeedPreviewDao,
    private val repoApi: RepoApi,
    private val issueApi: IssueApi,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : FeedPreviewRepository {

    private val lock = Mutex()

    /** Keys asked for in this process, so scrolling back and forth never re-requests (or retries a failure). */
    private val requested = mutableSetOf<String>()

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
    }

    override suspend fun ensure(repos: Set<RepoId>, pulls: Set<IssueRef>) {
        val now = clock.millis()
        val wanted = lock.withLock {
            val fresh = dao.freshness().filter { now - it.fetchedAtMillis < MAX_AGE.inWholeMilliseconds }.map { it.key }.toSet()
            val keys = repos.map { it.previewKey() } + pulls.map { it.previewKey() }
            keys.filter { it !in fresh && it !in requested }.also { requested += it }
        }
        if (wanted.isEmpty()) return
        val account = accounts.activeAccount.first()
        val token = account?.let { accounts.token(it.id) }
        val permits = Semaphore(CONCURRENCY)
        val fetched = coroutineScope {
            wanted.map { key ->
                async {
                    permits.withPermit {
                        when {
                            key.startsWith(REPO) -> repoId(key.removePrefix(REPO))?.let { id ->
                                (repoApi.repo(token, id) as? ForgeResult.Success)?.value?.let {
                                    FeedPreviewEntity(key, it.description, it.language, it.stars, null, now)
                                }
                            }
                            else -> pullRef(key.removePrefix(PULL))?.let { ref ->
                                (issueApi.issue(token, ref) as? ForgeResult.Success)?.value?.let {
                                    FeedPreviewEntity(key, null, null, null, it.title, now)
                                }
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (fetched.isNotEmpty()) dao.upsert(fetched)
        dao.deleteOlderThan(now - KEEP.inWholeMilliseconds)
    }

    private companion object {
        const val REPO = "repo:"
        const val PULL = "pull:"
        const val CONCURRENCY = 4
        val MAX_AGE = 24.hours
        val KEEP = 7.days

        fun RepoId.previewKey() = REPO + fullName.lowercase()
        fun IssueRef.previewKey() = PULL + repo.fullName.lowercase() + "#" + number

        fun repoId(value: String): RepoId? = value.split('/').takeIf { it.size == 2 }?.let { RepoId(it[0], it[1]) }

        fun pullRef(value: String): IssueRef? {
            val repo = repoId(value.substringBefore('#')) ?: return null
            val number = value.substringAfter('#', "").toIntOrNull() ?: return null
            return IssueRef(repo, number)
        }
    }
}
