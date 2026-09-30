package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * A Forgejo instance's Trending, which Forgejo doesn't have: a daily job measures it for everyone and publishes
 * [TrendingFile] at [url] (see docs/CODEBERG.md). One request, and none of them to the forge itself.
 */
class ForgejoTrendingApi(
    private val httpClient: HttpClient,
    override val forge: ForgeInstance,
    private val url: String,
) : TrendingApi {

    override suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>> = forgejoCall {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) return@forgejoCall ForgeResult.Failure(ForgeError.Http(response.status.value, null))
        val file = try {
            ForgejoJson.decodeFromString<TrendingFile>(response.bodyAsText())
        } catch (e: SerializationException) {
            return@forgejoCall ForgeResult.Failure(ForgeError.Http(response.status.value, "Unreadable trending file"))
        }
        ForgeResult.Success(file.list(period).map { it.toModel(forge) })
    }
}

/** What the daily job publishes: the three lists, each already in its final order. */
@Serializable
data class TrendingFile(
    val generatedAt: String,
    val daily: List<TrendingEntry> = emptyList(),
    val weekly: List<TrendingEntry> = emptyList(),
    val monthly: List<TrendingEntry> = emptyList(),
) {
    fun list(period: TrendingPeriod) = when (period) {
        TrendingPeriod.DAILY -> daily
        TrendingPeriod.WEEKLY -> weekly
        TrendingPeriod.MONTHLY -> monthly
    }
}

@Serializable
data class TrendingEntry(
    val owner: String,
    val name: String,
    val description: String? = null,
    val language: String? = null,
    val stars: Int,
    val forks: Int,
    val periodStars: Int,
    val periodForks: Int = 0,
    val ownerAvatarUrl: String? = null,
) {
    // No colour: Forgejo names languages without one, and the app colours them from its own table.
    fun toModel(forge: ForgeInstance) = TrendingRepo(
        id = RepoId(owner, name, forge),
        description = description,
        language = language,
        languageColor = null,
        stars = stars,
        forks = forks,
        periodStars = periodStars,
        builtBy = emptyList(),
        ownerAvatarUrl = ownerAvatarUrl,
    )
}
