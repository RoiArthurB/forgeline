package fr.arthurbrugiere.forgeline.core.data.trending

import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Each forge's own ranking, kept apart: the page merges them as it reads them. */
@Entity(tableName = "trending_repos", primaryKeys = ["host", "period", "rank"])
data class TrendingRepoEntity(
    val host: String,
    val forgeType: String,
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

@Entity(tableName = "trending_fetches", primaryKeys = ["host", "period"])
data class TrendingFetchEntity(
    val host: String,
    val period: String,
    val fetchedAtMillis: Long,
)


/** A forge's Trending history measured on the phone: opaque to the app, handed back to the next measurement. */
@Entity(tableName = "trending_measurements")
data class TrendingMeasurementEntity(
    @PrimaryKey val host: String,
    val state: String,
    val measuredAtMillis: Long,
)

@Dao
interface TrendingDao {
    @Query("SELECT * FROM trending_measurements WHERE host = :host")
    suspend fun measurement(host: String): TrendingMeasurementEntity?

    @Upsert
    suspend fun upsertMeasurement(measurement: TrendingMeasurementEntity)

    @Query("SELECT host, '' AS state, measuredAtMillis FROM trending_measurements")
    fun observeMeasurements(): Flow<List<TrendingMeasurementEntity>>

    /** Every forge's ranking for [period], each in its own order. */
    @Query("SELECT * FROM trending_repos WHERE period = :period ORDER BY host, rank")
    fun observeRepos(period: String): Flow<List<TrendingRepoEntity>>

    @Query("SELECT * FROM trending_fetches WHERE period = :period")
    fun observeFetches(period: String): Flow<List<TrendingFetchEntity>>

    @Query("SELECT fetchedAtMillis FROM trending_fetches WHERE host = :host AND period = :period")
    suspend fun fetchedAt(host: String, period: String): Long?

    @Query("DELETE FROM trending_repos WHERE host = :host AND period = :period")
    suspend fun clear(host: String, period: String)

    @Insert
    suspend fun insert(repos: List<TrendingRepoEntity>)

    @Upsert
    suspend fun upsertFetch(fetch: TrendingFetchEntity)


    @Transaction
    suspend fun replace(host: String, period: String, repos: List<TrendingRepoEntity>, fetchedAtMillis: Long) {
        clear(host, period)
        insert(repos)
        upsertFetch(TrendingFetchEntity(host, period, fetchedAtMillis))
    }
}

@Serializable
private data class StoredUser(val login: String, val avatarUrl: String?)

private val json = Json { ignoreUnknownKeys = true }

internal fun TrendingRepo.toEntity(period: TrendingPeriod, rank: Int) = TrendingRepoEntity(
    host = id.forge.host,
    forgeType = id.forge.type.name,
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
    id = RepoId(owner, name, ForgeInstance(ForgeType.valueOf(forgeType), host)),
    description = description,
    language = language,
    languageColor = languageColor,
    stars = stars,
    forks = forks,
    periodStars = periodStars,
    builtBy = json.decodeFromString<List<StoredUser>>(builtBy).map { ForgeUser(it.login, null, it.avatarUrl) },
    ownerAvatarUrl = ownerAvatarUrl,
)
