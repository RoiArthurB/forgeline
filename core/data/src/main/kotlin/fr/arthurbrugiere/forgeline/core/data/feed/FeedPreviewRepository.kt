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
import fr.arthurbrugiere.forgeline.core.forge.FeedDetail
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.FeedPreviews
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.model.firstImage
import fr.arthurbrugiere.forgeline.core.model.releaseSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineDispatcher
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * One Feed preview, kept as JSON: a repository (`repo:host/owner/name`), a release (`release:host/owner/name tag`)
 * or an issue or pull request (`talk:host/owner/name#12`, `!12` for a GitLab merge request).
 */
@Entity(tableName = "feed_previews")
data class FeedPreviewEntity(
    @PrimaryKey val key: String,
    val json: String,
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

    /** Deletes the previews of [host]'s repositories, releases and conversations. */
    @Query(
        "DELETE FROM feed_previews WHERE key LIKE 'repo:' || :host || '/%' OR key LIKE 'release:' || :host || '/%' " +
            "OR key LIKE 'talk:' || :host || '/%'",
    )
    suspend fun clear(host: String)

    @Query("DELETE FROM feed_previews WHERE fetchedAtMillis < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class PreviewFreshness(val key: String, val fetchedAtMillis: Long)

interface FeedPreviewRepository {
    fun observe(): Flow<FeedPreviews>

    /**
     * Fetches what's missing or stale among [keys]. The fetches finish even if the caller leaves; a failed one is
     * tried again on a later visit, after a short wait.
     */
    suspend fun ensure(keys: Set<FeedDetailKey>)
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
        val releases = mutableMapOf<ReleaseRef, ReleasePreview>()
        val conversations = mutableMapOf<IssueRef, ConversationPreview>()
        rows.forEach { row ->
            // A row this version can't read (written by another one) is skipped, and fetched again when it's stale.
            val stored = runCatching { PreviewJson.decodeFromString<StoredPreview>(row.json) }.getOrNull() ?: return@forEach
            when (val key = keyOf(row.key)) {
                is FeedDetailKey.Repo -> stored.repo?.let { repos[key.id] = it.toModel() }
                is FeedDetailKey.Release -> stored.release?.let { releases[key.ref] = it.toModel() }
                is FeedDetailKey.Conversation -> stored.conversation?.let { conversations[key.ref] = it.toModel() }
                null -> Unit
            }
        }
        FeedPreviews(repos, releases, conversations)
    }.flowOn(computation)

    override suspend fun ensure(keys: Set<FeedDetailKey>) {
        val now = clock.millis()
        val wanted = lock.withLock {
            val fresh = dao.freshness().filter { now - it.fetchedAtMillis < MAX_AGE.inWholeMilliseconds }.map { it.key }.toSet()
            keys.associateBy { it.storageKey() }.filterKeys { key ->
                key !in fresh && key !in inFlight && failedAt[key]?.let { now - it < RETRY_AFTER.inWholeMilliseconds } != true
            }.also { inFlight += it.keys }
        }
        if (wanted.isEmpty()) return
        // In the app's scope: leaving the Feed mid-fetch no longer loses the answers.
        scope.async { fetch(wanted, now) }.await()
    }

    private suspend fun fetch(wanted: Map<String, FeedDetailKey>, now: Long) {
        val fetched = try {
            coroutineScope {
                wanted.values.groupBy { it.forge() }.map { (forge, keys) -> async { details(forge, keys) } }.awaitAll()
                    .fold(emptyMap<FeedDetailKey, FeedDetail>()) { all, part -> all + part }
            }
        } finally {
            lock.withLock { inFlight -= wanted.keys }
        }
        lock.withLock {
            wanted.forEach { (storage, key) -> if (key in fetched) failedAt -= storage else failedAt[storage] = now }
        }
        fetched.map { (key, detail) -> FeedPreviewEntity(key.storageKey(), PreviewJson.encodeToString(detail.stored()), now) }
            .takeIf { it.isNotEmpty() }?.let { dao.upsert(it) }
        dao.deleteOlderThan(now - KEEP.inWholeMilliseconds)
    }

    /**
     * The details of [keys], all on [forge]: in one go where the forge answers many at once, otherwise one by one.
     * What couldn't be had is left out: one bad answer never takes the others down with it.
     */
    private suspend fun details(forge: ForgeInstance, keys: List<FeedDetailKey>): Map<FeedDetailKey, FeedDetail> {
        val token = accounts.tokenOn(forge)
        if (token != null) {
            when (val together = guarded { clients.feed(forge).details(token, keys) }) {
                is ForgeResult.Success -> return together.value
                is ForgeResult.Failure -> return emptyMap()
                null -> Unit
            }
        }
        val permits = Semaphore(CONCURRENCY)
        return coroutineScope {
            keys.map { key -> async { permits.withPermit { guarded { oneByOne(token, key) }?.let { key to it } } } }.awaitAll().filterNotNull().toMap()
        }
    }

    /** One row's details with the forge's ordinary calls. */
    private suspend fun oneByOne(token: String?, key: FeedDetailKey): FeedDetail? = when (key) {
        is FeedDetailKey.Repo -> (clients.repos(key.id.forge).repo(token, key.id) as? ForgeResult.Success)?.value?.let {
            FeedDetail.Repo(RepoPreview(it.description, it.language, it.stars, it.pushedAt))
        }
        is FeedDetailKey.Release -> coroutineScope {
            val repos = clients.repos(key.ref.repo.forge)
            val list = async { (repos.releases(token, key.ref.repo) as? ForgeResult.Success)?.value }
            val repo = async { (repos.repo(token, key.ref.repo) as? ForgeResult.Success)?.value }
            (repos.release(token, key.ref.repo, key.ref.tag) as? ForgeResult.Success)?.value?.let { release ->
                val tags = list.await().orEmpty()
                val at = tags.indexOfFirst { it.tag == key.ref.tag }
                FeedDetail.Release(
                    ReleasePreview(
                        summary = releaseSummary(release.body),
                        previousTag = if (at < 0) null else tags.drop(at + 1).firstOrNull { !it.isPrerelease }?.tag,
                        imageUrl = firstImage(release.body),
                        logoUrl = repo.await()?.ownerAvatarUrl,
                        reactions = release.reactions.filterValues { it > 0 },
                    ),
                )
            }
        }
        is FeedDetailKey.Conversation -> (clients.issues(key.ref.repo.forge).issue(token, key.ref) as? ForgeResult.Success)?.value?.let { issue ->
            // GitLab lists a merge request without counting its lines: nothing counted is shown as unknown, not as none.
            val size = issue.pullRequest?.takeIf { it.additions + it.deletions > 0 }
            FeedDetail.Conversation(
                ConversationPreview(issue.title, issue.labels, issue.comments, size?.additions, size?.deletions, issue.pullRequest?.changedFiles?.takeIf { it > 0 }),
            )
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val REPO = "repo:"
        const val RELEASE = "release:"
        const val TALK = "talk:"
        /** Rows looked up at once one by one: far forges answer slowly, so small waves add up. */
        const val CONCURRENCY = 8
        val MAX_AGE = 24.hours

        /** A failed preview waits this long before being asked again. */
        val RETRY_AFTER = 1.minutes
        val KEEP = 7.days

        val PreviewJson = Json { ignoreUnknownKeys = true }

        fun FeedDetailKey.forge(): ForgeInstance = when (this) {
            is FeedDetailKey.Repo -> id.forge
            is FeedDetailKey.Release -> ref.repo.forge
            is FeedDetailKey.Conversation -> ref.repo.forge
        }

        fun RepoId.storage() = key.lowercase(Locale.ROOT)

        // A tag never holds a space (git refuses one), so a space parts it from its repository.
        fun FeedDetailKey.storageKey(): String = when (this) {
            is FeedDetailKey.Repo -> REPO + id.storage()
            is FeedDetailKey.Release -> RELEASE + ref.repo.storage() + " " + ref.tag
            // "!" for a merge request where those are numbered apart (GitLab), so it isn't kept as the issue of that number.
            is FeedDetailKey.Conversation ->
                TALK + ref.repo.storage() + (if (ref.repo.forge.type.numbersMergeRequestsApart && ref.isPullRequest == true) "!" else "#") + ref.number
        }

        fun keyOf(value: String): FeedDetailKey? = when {
            value.startsWith(REPO) -> RepoId.fromKey(value.removePrefix(REPO))?.let { FeedDetailKey.Repo(it) }
            value.startsWith(RELEASE) -> value.removePrefix(RELEASE).let { rest ->
                val space = rest.indexOf(' ').takeIf { it > 0 } ?: return null
                RepoId.fromKey(rest.substring(0, space))?.let { FeedDetailKey.Release(ReleaseRef(it, rest.substring(space + 1))) }
            }
            value.startsWith(TALK) -> value.removePrefix(TALK).let { rest ->
                val mark = rest.lastIndexOfAny(charArrayOf('#', '!')).takeIf { it >= 0 } ?: return null
                val repo = RepoId.fromKey(rest.substring(0, mark)) ?: return null
                val number = rest.substring(mark + 1).toIntOrNull() ?: return null
                FeedDetailKey.Conversation(IssueRef(repo, number, isPullRequest = if (repo.forge.type.numbersMergeRequestsApart) rest[mark] == '!' else null))
            }
            else -> null
        }
    }
}

@Serializable
private data class StoredPreview(
    val repo: StoredRepo? = null,
    val release: StoredRelease? = null,
    val conversation: StoredConversation? = null,
)

@Serializable
private data class StoredRepo(val description: String? = null, val language: String? = null, val stars: Int = 0, val pushedAtMillis: Long? = null) {
    fun toModel() = RepoPreview(description, language, stars, pushedAtMillis?.let(Instant::ofEpochMilli))
}

@Serializable
private data class StoredRelease(
    val summary: String? = null,
    val previousTag: String? = null,
    val imageUrl: String? = null,
    val logoUrl: String? = null,
    val reactions: Map<String, Int> = emptyMap(),
) {
    fun toModel() = ReleasePreview(
        summary, previousTag, imageUrl, logoUrl,
        reactions.mapNotNull { (name, count) -> Reaction.entries.firstOrNull { it.name == name }?.let { it to count } }.toMap(),
    )
}

@Serializable
private data class StoredLabel(val name: String, val color: String? = null)

@Serializable
private data class StoredConversation(
    val title: String? = null,
    val labels: List<StoredLabel> = emptyList(),
    val comments: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
) {
    fun toModel() = ConversationPreview(title, labels.map { Label(it.name, it.color) }, comments, additions, deletions, changedFiles)
}

private fun FeedDetail.stored(): StoredPreview = when (this) {
    is FeedDetail.Repo -> StoredPreview(repo = StoredRepo(preview.description, preview.language, preview.stars, preview.pushedAt?.toEpochMilli()))
    is FeedDetail.Release -> StoredPreview(
        release = StoredRelease(
            preview.summary, preview.previousTag, preview.imageUrl, preview.logoUrl, preview.reactions.mapKeys { it.key.name },
        ),
    )
    is FeedDetail.Conversation -> StoredPreview(
        conversation = StoredConversation(
            preview.title, preview.labels.map { StoredLabel(it.name, it.color) }, preview.comments, preview.additions, preview.deletions, preview.changedFiles,
        ),
    )
}
