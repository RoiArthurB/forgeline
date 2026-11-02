package fr.arthurbrugiere.forgeline.forge.forgejo.trending

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Repository
import fr.arthurbrugiere.forgeline.forge.forgejo.api.SearchResults
import fr.arthurbrugiere.forgeline.forge.forgejo.count
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
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
        val top = mutableListOf<Repository>()
        for (page in 1..topPages) {
            val repos = page("source", "stars", page)
            top += repos
            if (repos.size < PAGE_SIZE || (repos.lastOrNull()?.starsCount ?: 0) < MIN_PERIOD_STARS) break
        }
        val forks = mutableListOf<Repository>()
        for (page in 1..forkPages) {
            val batch = page("fork", "created", page)
            forks += batch
            if (batch.size < PAGE_SIZE) break
        }
        return Crawl(
            at = at,
            top = top.distinctBy { it.id }.mapNotNull { it.toCrawled() },
            forks = forks.mapNotNull { fork -> fork.parent?.id?.let { parent -> instant(fork.createdAt)?.let { CrawledFork(it, parent) } } },
        )
    }

    private suspend fun page(mode: String, sort: String, page: Int): List<Repository> {
        val url = "${forge.webUrl}/api/v1/repos/search?mode=$mode&sort=$sort&order=desc&limit=$PAGE_SIZE&page=$page"
        var failure: String? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(retryDelayMillis)
            try {
                val response = http.get(url) { token?.let { header(HttpHeaders.Authorization, "token $it") } }
                if (response.status.isSuccess()) return json.decodeFromString<SearchResults>(response.bodyAsText()).`data`.orEmpty()
                failure = "HTTP ${response.status.value}"
            } catch (e: IOException) {
                failure = e.toString()
            }
        }
        throw IOException("Couldn't read $url: $failure")
    }

    /** A repository the search names without its id, owner or name can't be followed: it is left out. */
    private fun Repository.toCrawled(): CrawledRepo? {
        val created = instant(createdAt) ?: return null
        val owner = owner?.login ?: return null
        // The repository's own avatar is shown before its owner's; empty when it has none.
        val avatar = avatarUrl?.ifBlank { null } ?: this.owner?.avatarUrl
        return CrawledRepo(id ?: return null, owner, name ?: return null, description?.ifBlank { null }, language?.ifBlank { null }, starsCount.count(), forksCount.count(), created, avatar)
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
