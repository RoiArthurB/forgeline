package fr.arthurbrugiere.forgeline.core.data.repo

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/** Keyed by the forge and the name the repo was opened with, which may be an old name of a moved repo. */
@Entity(tableName = "repo_cache")
data class RepoCacheEntity(
    @PrimaryKey val requestedName: String,
    val details: String,
    val readmePath: String?,
    val readmeMarkdown: String?,
    val fetchedAtMillis: Long,
)

@Dao
interface RepoDao {
    @Query("SELECT * FROM repo_cache WHERE requestedName = :requestedName")
    fun observe(requestedName: String): Flow<RepoCacheEntity?>

    @Query("SELECT fetchedAtMillis FROM repo_cache WHERE requestedName = :requestedName")
    suspend fun fetchedAt(requestedName: String): Long?

    @Upsert
    suspend fun upsert(entity: RepoCacheEntity)

    /** Deletes every repository kept from [host]. */
    @Query("DELETE FROM repo_cache WHERE requestedName LIKE :host || '/%'")
    suspend fun clear(host: String)
}

@Serializable
private data class StoredDetails(
    val forgeType: ForgeType = ForgeType.GITHUB,
    val host: String = ForgeInstance.GitHub.host,
    val owner: String,
    val name: String,
    val description: String?,
    val homepage: String?,
    val topics: List<String>,
    val stars: Int,
    val forks: Int,
    val watchers: Int,
    val language: String?,
    val license: String?,
    val defaultBranch: String,
    val ownerAvatarUrl: String?,
    val isFork: Boolean,
    val isArchived: Boolean,
    val pushedAtEpochSeconds: Long?,
    val hasActions: Boolean = true,
)

private val json = Json { ignoreUnknownKeys = true }

internal fun RepoId.cacheKey(): String = "${forge.host}/$fullName".lowercase()

internal fun RepoDetails.encode(): String = json.encodeToString(
    StoredDetails(
        id.forge.type, id.forge.host, id.owner, id.name, description, homepage, topics, stars, forks, watchers, language, license,
        defaultBranch, ownerAvatarUrl, isFork, isArchived, pushedAt?.epochSecond, hasActions,
    ),
)

internal fun RepoCacheEntity.decodeDetails(): RepoDetails = json.decodeFromString<StoredDetails>(details).run {
    RepoDetails(
        RepoId(owner, name, ForgeInstance(forgeType, host)), description, homepage, topics, stars, forks, watchers, language, license,
        defaultBranch, ownerAvatarUrl, isFork, isArchived, pushedAtEpochSeconds?.let(Instant::ofEpochSecond), hasActions,
    )
}

internal fun RepoCacheEntity.readme(): Readme? = if (readmePath != null && readmeMarkdown != null) Readme(readmePath, readmeMarkdown) else null
