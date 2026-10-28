package fr.arthurbrugiere.forgeline.core.data.firstissues

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FirstIssue
import fr.arthurbrugiere.forgeline.core.model.FirstIssueSource
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The first issues last found, kept as JSON so the screen opens on them at once. One row: there is one list. */
@Entity(tableName = "first_issues")
data class FirstIssuesEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val fetchedAtMillis: Long,
)

@Dao
interface FirstIssuesDao {
    @Query("SELECT * FROM first_issues WHERE id = 0")
    fun observe(): Flow<FirstIssuesEntity?>

    @Query("SELECT * FROM first_issues WHERE id = 0")
    suspend fun get(): FirstIssuesEntity?

    @Upsert
    suspend fun upsert(entity: FirstIssuesEntity)

    @Query("DELETE FROM first_issues")
    suspend fun clear()
}

/** The issues found, newest first, and when; null before any were looked for. */
data class FirstIssues(val issues: List<FirstIssue>, val fetchedAtMillis: Long)

interface FirstIssuesRepository {
    fun observe(): Flow<FirstIssues?>

    /**
     * Looks again when what was found is older than [MAX_AGE_MILLIS], or always when [force]d: in the repositories
     * each account starred lately and in today's Trending. Fails only when every forge did.
     */
    suspend fun refresh(force: Boolean = false): ForgeResult<Unit>

    companion object {
        /** Issues for a first contribution sit open for weeks: a few hours is fresh enough. */
        const val MAX_AGE_MILLIS = 3 * 60 * 60_000L
    }
}

@Singleton
class DefaultFirstIssuesRepository @Inject constructor(
    private val dao: FirstIssuesDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val trending: TrendingRepository,
    private val clock: Clock,
    @param:Computation private val computation: CoroutineDispatcher,
) : FirstIssuesRepository {
    private val lock = Mutex()

    override fun observe(): Flow<FirstIssues?> = dao.observe().map { row ->
        row?.let { runCatching { FirstIssues(Stored.decode(it.json).issues.map(StoredIssue::toModel), it.fetchedAtMillis) }.getOrNull() }
    }.flowOn(computation)

    override suspend fun refresh(force: Boolean): ForgeResult<Unit> = lock.withLock {
        val now = clock.millis()
        val kept = dao.get()
        if (!force && kept != null && now - kept.fetchedAtMillis < FirstIssuesRepository.MAX_AGE_MILLIS) return ForgeResult.Success(Unit)
        val sources = sources()
        val byForge = sources.keys.groupBy { it.forge }
        val answers = coroutineScope {
            byForge.map { (forge, repos) -> async { clients.search(forge).firstIssues(accounts.tokenOn(forge), repos) } }.awaitAll()
        }
        val found = answers.filterIsInstance<ForgeResult.Success<List<fr.arthurbrugiere.forgeline.core.model.IssueSearchResult>>>()
        if (found.isEmpty() && answers.isNotEmpty()) return answers.first() as ForgeResult.Failure
        val issues = found.flatMap { it.value }
            .distinctBy { it.repo to it.issue.number }
            .sortedByDescending { it.issue.createdAt }
            .map { FirstIssue(it.repo, it.issue, sources[it.repo] ?: FirstIssueSource.TRENDING) }
        dao.upsert(FirstIssuesEntity(json = Stored(issues.map(StoredIssue::of)).encode(), fetchedAtMillis = now))
        ForgeResult.Success(Unit)
    }

    /**
     * Where to look, and why: each account's latest stars, then today's Trending, a starred one counting as starred.
     * Forges answering repository by repository (GitLab, Forgejo) are asked about fewer.
     */
    private suspend fun sources(): Map<RepoId, FirstIssueSource> = coroutineScope {
        val starred = accounts.accounts.first().map { account ->
            async {
                val token = accounts.token(account.id)
                (clients.users(account.forge).starred(token, account.user.login) as? ForgeResult.Success)?.value.orEmpty()
                    .map { it.id }.take(limitFor(account.forge, STARRED_PER_FORGE))
            }
        }
        // Read if it never was (Trending not opened yet); recent enough, it isn't asked again.
        trending.refresh(TrendingPeriod.DAILY)
        val trendingToday = trending.observe(TrendingPeriod.DAILY).first().repos.map { it.id }
            .groupBy { it.forge }
            .flatMap { (forge, repos) -> repos.take(limitFor(forge, TRENDING_PER_FORGE)) }
        val found = linkedMapOf<RepoId, FirstIssueSource>()
        starred.awaitAll().flatten().forEach { found[it] = FirstIssueSource.STARRED }
        trendingToday.forEach { found.putIfAbsent(it, FirstIssueSource.TRENDING) }
        found
    }

    // GitHub reads twenty repositories in one search; the others take two or three requests each.
    private fun limitFor(forge: ForgeInstance, githubLimit: Int) = if (forge.type == ForgeType.GITHUB) githubLimit else githubLimit / 3

    private companion object {
        const val STARRED_PER_FORGE = 30
        const val TRENDING_PER_FORGE = 25
    }
}

private val StoredJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class Stored(val issues: List<StoredIssue> = emptyList()) {
    fun encode() = StoredJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String) = StoredJson.decodeFromString(serializer(), json)
    }
}

@Serializable
private data class StoredLabel(val name: String, val color: String? = null)

@Serializable
private data class StoredIssue(
    val forgeType: String,
    val host: String,
    val owner: String,
    val name: String,
    val number: Int,
    val title: String,
    val authorLogin: String? = null,
    val authorAvatarUrl: String? = null,
    val comments: Int? = null,
    val createdAtMillis: Long,
    val labels: List<StoredLabel> = emptyList(),
    val source: String,
) {
    fun toModel(): FirstIssue {
        val forge = ForgeInstance(ForgeType.valueOf(forgeType), host)
        return FirstIssue(
            RepoId(owner, name, forge),
            IssueSummary(
                number, title, IssueState.OPEN, authorLogin?.let { ForgeUser(it, null, authorAvatarUrl) }, comments,
                Instant.ofEpochMilli(createdAtMillis), labels.map { Label(it.name, it.color) }, isPullRequest = false, isDraft = false,
            ),
            FirstIssueSource.entries.firstOrNull { it.name == source } ?: FirstIssueSource.TRENDING,
        )
    }

    companion object {
        fun of(found: FirstIssue) = StoredIssue(
            found.repo.forge.type.name, found.repo.forge.host, found.repo.owner, found.repo.name,
            found.issue.number, found.issue.title, found.issue.author?.login, found.issue.author?.avatarUrl, found.issue.comments,
            found.issue.createdAt.toEpochMilli(), found.issue.labels.map { StoredLabel(it.name, it.color) }, found.source.name,
        )
    }
}
