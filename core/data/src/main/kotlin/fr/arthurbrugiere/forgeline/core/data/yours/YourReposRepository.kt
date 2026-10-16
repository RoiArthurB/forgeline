package fr.arthurbrugiere.forgeline.core.data.yours

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.di.Computation
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.YourRepos
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One account's reachable repositories and organisations, kept as JSON so the You tab opens on them at once. */
@Entity(tableName = "your_repos")
data class YourReposEntity(
    @PrimaryKey val accountId: String,
    val json: String,
    val fetchedAtMillis: Long,
)

@Dao
interface YourReposDao {
    @Query("SELECT * FROM your_repos")
    fun observeAll(): Flow<List<YourReposEntity>>

    @Query("SELECT * FROM your_repos WHERE accountId = :accountId")
    suspend fun get(accountId: String): YourReposEntity?

    @Upsert
    suspend fun upsert(entity: YourReposEntity)

    /** Deletes the lists of every account no longer signed in. */
    @Query("DELETE FROM your_repos WHERE accountId NOT IN (:accountIds)")
    suspend fun keepOnly(accountIds: List<String>)
}

interface YourReposRepository {
    /** The signed-in accounts' repositories, as last read; null before any was. */
    fun observe(): Flow<YourRepos?>

    /**
     * Reads each account's list again when it is older than [MAX_AGE_MILLIS], or always when [force]d. One forge
     * failing doesn't hide the others; it's reported only when every account failed.
     */
    suspend fun refresh(force: Boolean = false): ForgeResult<Unit>

    companion object {
        /** A list is kept an hour: repositories are pushed to all day, but rarely created or moved. */
        const val MAX_AGE_MILLIS = 60 * 60_000L
    }
}

@Singleton
class DefaultYourReposRepository @Inject constructor(
    private val dao: YourReposDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val clock: Clock,
    @param:Computation private val computation: CoroutineDispatcher,
) : YourReposRepository {

    override fun observe(): Flow<YourRepos?> = combine(accounts.accounts, dao.observeAll()) { signedIn, rows ->
        val byAccount = rows.associateBy { it.accountId }
        val read = signedIn.mapNotNull { account -> byAccount[account.id]?.let { account to it } }
        if (read.isEmpty()) return@combine null
        val stored = read.mapNotNull { (account, row) -> runCatching { Stored.decode(row.json) }.getOrNull()?.let { Triple(account, row, it) } }
        YourRepos.of(
            accounts = signedIn.map { it.user to it.forge },
            repos = stored.flatMap { (account, _, it) -> it.repos.map { repo -> repo.toModel(account.forge) } },
            organizations = stored.flatMap { (account, _, it) -> it.orgs.map { org -> UserSummary(org.login, org.avatarUrl, true, account.forge) } },
            syncedAtMillis = read.minOf { it.second.fetchedAtMillis },
        )
    }.flowOn(computation)

    override suspend fun refresh(force: Boolean): ForgeResult<Unit> {
        val signedIn = accounts.accounts.first()
        if (signedIn.isEmpty()) return ForgeResult.Failure(ForgeError.Unauthorized)
        val results = coroutineScope { signedIn.map { async { refresh(it, force) } }.awaitAll() }
        return results.firstOrNull { it is ForgeResult.Success } ?: results.first()
    }

    private suspend fun refresh(account: Account, force: Boolean): ForgeResult<Unit> {
        val now = clock.millis()
        val kept = dao.get(account.id)
        // A list written by an earlier version lacks what this one shows (whose each repository is): read again.
        val current = kept?.let { runCatching { Stored.decode(it.json).version }.getOrNull() } == Stored.VERSION
        if (!force && current && kept != null && now - kept.fetchedAtMillis < YourReposRepository.MAX_AGE_MILLIS) return ForgeResult.Success(Unit)
        val token = accounts.token(account.id) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val users = clients.users(account.forge)
        return coroutineScope {
            val orgs = async { (users.organizations(token) as? ForgeResult.Success)?.value.orEmpty() }
            when (val repos = users.reachableRepos(token)) {
                is ForgeResult.Failure -> repos
                is ForgeResult.Success -> {
                    dao.upsert(YourReposEntity(account.id, Stored(repos.value.map(StoredRepo::of), orgs.await().map { StoredOrg(it.login, it.avatarUrl) }).encode(), now))
                    ForgeResult.Success(Unit)
                }
            }
        }
    }
}

private val StoredJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class Stored(val repos: List<StoredRepo> = emptyList(), val orgs: List<StoredOrg> = emptyList(), val version: Int = 1) {
    fun encode() = StoredJson.encodeToString(serializer(), copy(version = VERSION))

    companion object {
        /** 2: whether an organisation owns each repository. */
        const val VERSION = 2

        fun decode(json: String) = StoredJson.decodeFromString(serializer(), json)
    }
}

@Serializable
private data class StoredOrg(val login: String, val avatarUrl: String? = null)

@Serializable
private data class StoredRepo(
    val owner: String,
    val name: String,
    val description: String? = null,
    val language: String? = null,
    val stars: Int = 0,
    val forks: Int = 0,
    val isFork: Boolean = false,
    val updatedAtMillis: Long? = null,
    val ownerAvatarUrl: String? = null,
    val isPrivate: Boolean = false,
    val pushedAtMillis: Long? = null,
    val ownerIsOrganization: Boolean? = null,
) {
    fun toModel(forge: ForgeInstance) = RepoSummary(
        RepoId(owner, name, forge), description, language, stars, forks, isFork,
        updatedAtMillis?.let(Instant::ofEpochMilli), ownerAvatarUrl, isPrivate, pushedAtMillis?.let(Instant::ofEpochMilli), ownerIsOrganization,
    )

    companion object {
        fun of(repo: RepoSummary) = StoredRepo(
            repo.id.owner, repo.id.name, repo.description, repo.language, repo.stars, repo.forks, repo.isFork,
            repo.updatedAt?.toEpochMilli(), repo.ownerAvatarUrl, repo.isPrivate, repo.pushedAt?.toEpochMilli(), repo.ownerIsOrganization,
        )
    }
}
