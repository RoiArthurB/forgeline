package fr.arthurbrugiere.forgeline.core.data.trending

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Entity(tableName = "trending_repos", primaryKeys = ["period", "rank"])
data class TrendingRepoEntity(
    val period: String,
    val rank: Int,
    val owner: String,
    val name: String,
    val description: String?,
    val language: String?,
    val languageColor: String?,
    val stars: Int,
    val forks: Int,
    val periodStars: Int,
    val ownerAvatarUrl: String?,
    /** JSON list of contributors; only read back whole, never queried. */
    val builtBy: String,
)

@Entity(tableName = "trending_fetches")
data class TrendingFetchEntity(
    @PrimaryKey val period: String,
    val fetchedAtMillis: Long,
)

/** How far down a period's list the user read; kept apart from the fetches so a refresh doesn't clear it. */
@Entity(tableName = "trending_marks")
data class TrendingMarkEntity(
    @PrimaryKey val period: String,
    val rank: Int,
    val markedAtMillis: Long,
)

@Dao
interface TrendingDao {
    @Query("SELECT * FROM trending_repos WHERE period = :period ORDER BY rank")
    fun observeRepos(period: String): Flow<List<TrendingRepoEntity>>

    @Query("SELECT fetchedAtMillis FROM trending_fetches WHERE period = :period")
    fun observeFetchedAt(period: String): Flow<Long?>

    @Query("SELECT fetchedAtMillis FROM trending_fetches WHERE period = :period")
    suspend fun fetchedAt(period: String): Long?

    @Query("DELETE FROM trending_repos WHERE period = :period")
    suspend fun clear(period: String)

    @Insert
    suspend fun insert(repos: List<TrendingRepoEntity>)

    @Upsert
    suspend fun upsertFetch(fetch: TrendingFetchEntity)

    @Query("SELECT * FROM trending_marks WHERE period = :period")
    suspend fun mark(period: String): TrendingMarkEntity?

    @Upsert
    suspend fun upsertMark(mark: TrendingMarkEntity)

    @Transaction
    suspend fun replace(period: String, repos: List<TrendingRepoEntity>, fetchedAtMillis: Long) {
        clear(period)
        insert(repos)
        upsertFetch(TrendingFetchEntity(period, fetchedAtMillis))
    }
}

@Serializable
private data class StoredUser(val login: String, val avatarUrl: String?)

private val json = Json { ignoreUnknownKeys = true }

internal fun TrendingRepo.toEntity(period: TrendingPeriod, rank: Int) = TrendingRepoEntity(
    period = period.name,
    rank = rank,
    owner = id.owner,
    name = id.name,
    description = description,
    language = language,
    languageColor = languageColor,
    stars = stars,
    forks = forks,
    periodStars = periodStars,
    builtBy = json.encodeToString(builtBy.map { StoredUser(it.login, it.avatarUrl) }),
    ownerAvatarUrl = ownerAvatarUrl,
)

internal fun TrendingRepoEntity.toModel() = TrendingRepo(
    id = RepoId(owner, name),
    description = description,
    language = language,
    languageColor = languageColor,
    stars = stars,
    forks = forks,
    periodStars = periodStars,
    builtBy = json.decodeFromString<List<StoredUser>>(builtBy).map { ForgeUser(it.login, null, it.avatarUrl) },
    ownerAvatarUrl = ownerAvatarUrl,
)
