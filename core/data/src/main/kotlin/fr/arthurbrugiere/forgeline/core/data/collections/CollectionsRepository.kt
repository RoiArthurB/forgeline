package fr.arthurbrugiere.forgeline.core.data.collections

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.RepoCollection
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMillis: Long,
    /** "github.com:login:listId" for one imported from a GitHub list; null for one made here. */
    val importedFrom: String? = null,
)

/** A repository in a collection, with what its row shows as it was when added: the list opens without the forge. */
@Entity(
    tableName = "collection_repos",
    primaryKeys = ["collectionId", "host", "owner", "name"],
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("host", "owner", "name")],
)
data class CollectionRepoEntity(
    val collectionId: String,
    val forgeType: String,
    val host: String,
    val owner: String,
    val name: String,
    val description: String?,
    val language: String?,
    val stars: Int,
    val ownerAvatarUrl: String?,
    val addedAtMillis: Long,
) {
    fun toModel() = RepoSummary(
        RepoId(owner, name, ForgeInstance(ForgeType.entries.firstOrNull { it.name == forgeType } ?: ForgeType.GITHUB, host)),
        description, language, stars, 0, false, null, ownerAvatarUrl,
    )

    companion object {
        fun of(collectionId: String, repo: RepoSummary, addedAtMillis: Long) = CollectionRepoEntity(
            collectionId, repo.id.forge.type.name, repo.id.forge.host, repo.id.owner, repo.id.name,
            repo.description, repo.language, repo.stars, repo.ownerAvatarUrl, addedAtMillis,
        )
    }
}

/** A collection with how many repositories it holds, as one query reads it. */
data class CollectionCount(val id: String, val name: String, val importedFrom: String?, val repoCount: Int)

@Dao
interface CollectionDao {
    @Query(
        "SELECT c.id, c.name, c.importedFrom, COUNT(r.collectionId) AS repoCount FROM collections c " +
            "LEFT JOIN collection_repos r ON r.collectionId = c.id GROUP BY c.id ORDER BY c.createdAtMillis",
    )
    fun observeAll(): Flow<List<CollectionCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: String): Flow<CollectionEntity?>

    @Query("SELECT * FROM collection_repos WHERE collectionId = :id ORDER BY addedAtMillis DESC, owner, name")
    fun observeRepos(id: String): Flow<List<CollectionRepoEntity>>

    @Query("SELECT collectionId FROM collection_repos WHERE host = :host AND owner = :owner AND name = :name")
    fun observeHolding(host: String, owner: String, name: String): Flow<List<String>>

    @Query("SELECT * FROM collections WHERE importedFrom = :importedFrom")
    suspend fun imported(importedFrom: String): CollectionEntity?

    @Query("SELECT host || '/' || owner || '/' || name FROM collection_repos WHERE collectionId = :id")
    suspend fun repoKeys(id: String): List<String>

    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    @Upsert
    suspend fun upsertRepos(repos: List<CollectionRepoEntity>)

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM collection_repos WHERE collectionId = :id AND host = :host AND owner = :owner AND name = :name")
    suspend fun removeRepo(id: String, host: String, owner: String, name: String)

    /** Adds what [repos] has that [id] doesn't: a repository already there keeps its place. */
    @Transaction
    suspend fun addMissing(id: String, repos: List<CollectionRepoEntity>): Int {
        val there = repoKeys(id).toSet()
        val missing = repos.filter { "${it.host}/${it.owner}/${it.name}" !in there }
        upsertRepos(missing)
        return missing.size
    }
}

/** What an import brought: the lists read, and the repositories they added. */
data class ImportReport(val lists: Int, val added: Int)

interface CollectionsRepository {
    fun observeAll(): Flow<List<RepoCollection>>

    /** The collection's name, null once deleted. */
    fun observeName(id: String): Flow<String?>

    /** Its repositories, the latest added first. */
    fun observeRepos(id: String): Flow<List<RepoSummary>>

    /** The collections [repo] is in. */
    fun observeHolding(repo: RepoId): Flow<Set<String>>

    /** Makes an empty collection named [name] and answers its id. */
    suspend fun create(name: String): String

    suspend fun rename(id: String, name: String)

    suspend fun delete(id: String)

    suspend fun add(id: String, repo: RepoSummary)

    suspend fun remove(id: String, repo: RepoId)

    /** The accounts whose lists can be brought over: those on GitHub, the only forge with any. */
    fun importableAccounts(accounts: List<Account>): List<Account> = accounts.filter { it.forge.type == ForgeType.GITHUB }

    /**
     * Brings [account]'s GitHub lists over as collections. Importing again adds what a list gained to its collection
     * and leaves the rest alone: a repository added or removed here stays so, and so does a renamed collection.
     */
    suspend fun import(account: Account): ForgeResult<ImportReport>
}

@Singleton
class DefaultCollectionsRepository @Inject constructor(
    private val dao: CollectionDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : CollectionsRepository {

    override fun observeAll(): Flow<List<RepoCollection>> = dao.observeAll().map { rows -> rows.map { RepoCollection(it.id, it.name, it.repoCount, it.importedFrom) } }

    override fun observeName(id: String): Flow<String?> = dao.observe(id).map { it?.name }

    override fun observeRepos(id: String): Flow<List<RepoSummary>> = dao.observeRepos(id).map { rows -> rows.map { it.toModel() } }

    override fun observeHolding(repo: RepoId): Flow<Set<String>> = dao.observeHolding(repo.forge.host, repo.owner, repo.name).map { it.toSet() }

    override suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(CollectionEntity(id, name.trim(), clock.millis()))
        return id
    }

    override suspend fun rename(id: String, name: String) = dao.rename(id, name.trim())

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun add(id: String, repo: RepoSummary) = dao.upsertRepos(listOf(CollectionRepoEntity.of(id, repo, clock.millis())))

    override suspend fun remove(id: String, repo: RepoId) = dao.removeRepo(id, repo.forge.host, repo.owner, repo.name)

    override suspend fun import(account: Account): ForgeResult<ImportReport> {
        if (account.forge.type != ForgeType.GITHUB) return ForgeResult.Failure(ForgeError.Unsupported)
        val token = accounts.token(account.id) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val lists = when (val read = clients.users(account.forge).starLists(token)) {
            is ForgeResult.Failure -> return read
            is ForgeResult.Success -> read.value
        }
        var added = 0
        val now = clock.millis()
        lists.forEachIndexed { index, list ->
            val origin = "${account.forge.host}:${account.user.login.lowercase()}:${list.id}"
            // Made one after the other, they keep the lists' order.
            val collection = dao.imported(origin) ?: CollectionEntity(UUID.randomUUID().toString(), list.name, now + index, origin).also { dao.upsert(it) }
            // The list's first repository is the one added last: kept in that order.
            added += dao.addMissing(collection.id, list.repos.mapIndexed { at, repo -> CollectionRepoEntity.of(collection.id, repo, now - at) })
        }
        return ForgeResult.Success(ImportReport(lists.size, added))
    }
}
