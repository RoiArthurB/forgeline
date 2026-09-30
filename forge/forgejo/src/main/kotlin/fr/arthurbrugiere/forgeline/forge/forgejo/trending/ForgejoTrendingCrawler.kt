package fr.arthurbrugiere.forgeline.forge.forgejo.trending

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Reads a Forgejo instance for Trending: its repositories by stars, then its newest forks. Used by the daily job
 * that publishes Codeberg's Trending, and on the phone for any other server. Pages are read until [topPages], the
 * server runs out, or repositories fall under [MIN_PERIOD_STARS] (they can't trend), so a small server costs a few
 * requests. A page is retried twice; a crawl that can't read the ranking throws rather than record a partial one.
 */
class ForgejoTrendingCrawler(
    private val http: HttpClient,
    private val forge: ForgeInstance,
    private val token: String? = null,
    private val topPages: Int = TOP_PAGES,
    private val forkPages: Int = FORK_PAGES,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = 10_000,
) {
    suspend fun crawl(): Crawl {
        val at = clock.instant()
        val top = mutableListOf<SearchRepoJson>()
        for (page in 1..topPages) {
            val repos = page("source", "stars", page)
            top += repos
            if (repos.size < PAGE_SIZE || (repos.lastOrNull()?.stars ?: 0) < MIN_PERIOD_STARS) break
        }
        val forks = mutableListOf<SearchRepoJson>()
        for (page in 1..forkPages) {
            val batch = page("fork", "created", page)
            forks += batch
            if (batch.size < PAGE_SIZE) break
        }
        return Crawl(
            at = at,
            top = top.distinctBy { it.id }.mapNotNull { it.toCrawled() },
            forks = forks.mapNotNull { fork -> fork.parent?.let { parent -> instant(fork.createdAt)?.let { CrawledFork(it, parent.id) } } },
        )
    }

    private suspend fun page(mode: String, sort: String, page: Int): List<SearchRepoJson> {
        val url = "${forge.webUrl}/api/v1/repos/search?mode=$mode&sort=$sort&order=desc&limit=$PAGE_SIZE&page=$page"
        var failure: String? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(retryDelayMillis)
            try {
                val response = http.get(url) { token?.let { header(HttpHeaders.Authorization, "token $it") } }
                if (response.status.isSuccess()) return json.decodeFromString<SearchJson>(response.bodyAsText()).data
                failure = "HTTP ${response.status.value}"
            } catch (e: IOException) {
                failure = e.toString()
            }
        }
        throw IOException("Couldn't read $url: $failure")
    }

    private fun SearchRepoJson.toCrawled() = instant(createdAt)?.let {
        CrawledRepo(id, owner.login, name, description?.ifBlank { null }, language?.ifBlank { null }, stars, forks, it, owner.avatarUrl)
    }

    private fun instant(value: String?) = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    companion object {
        /** Codeberg's budget: the top 1,400 (every source repository with 13 stars or more, 2026-09-29), then 100 forks. */
        const val TOP_PAGES = 28
        const val FORK_PAGES = 2
        const val PAGE_SIZE = 50
        private const val ATTEMPTS = 3
    }
}

internal val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class SearchJson(val data: List<SearchRepoJson> = emptyList())

@Serializable
private data class OwnerJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class ParentJson(val id: Long)

@Serializable
private data class SearchRepoJson(
    val id: Long,
    val name: String,
    val owner: OwnerJson,
    val description: String? = null,
    val language: String? = null,
    @SerialName("stars_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    val parent: ParentJson? = null,
)
