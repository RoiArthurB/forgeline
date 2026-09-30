package fr.arthurbrugiere.forgeline.tools.trending

import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.OffsetDateTime

/**
 * `codeberg-trending <previous state.json> <output directory>`: reads Codeberg once, within [TOP_PAGES] +
 * [FORK_PAGES] anonymous requests, and writes `state.json` and `trending.json` to the output directory. A missing or
 * empty previous state starts the history afresh.
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) { "Usage: codeberg-trending <previous state.json> <output directory>" }
    val previous = File(args[0]).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        ?.let { json.decodeFromString<TrendingState>(it) } ?: TrendingState()
    val crawl = forgejoHttpClient(OkHttp.create()).use { Crawler(it).crawl() }
    val state = previous.record(crawl)
    val output = File(args[1]).apply { mkdirs() }
    File(output, "state.json").writeText(json.encodeToString(state))
    File(output, "trending.json").writeText(json.encodeToString(state.lists(crawl)))
    println("Tracked ${state.repos.size} repositories over ${state.days.size} days; ${crawl.forks.size} recent forks read.")
}

internal val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

const val TOP_PAGES = 28
const val FORK_PAGES = 2

class Crawler(
    private val http: HttpClient,
    private val host: String = "https://codeberg.org",
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = 10_000,
) {
    suspend fun crawl(): Crawl {
        val at = clock.instant()
        val top = (1..TOP_PAGES).flatMap { page("source", "stars", it) }.distinctBy { it.id }
        val forks = (1..FORK_PAGES).flatMap { page("fork", "created", it) }
        return Crawl(
            at = at,
            top = top.mapNotNull { it.toCrawled() },
            forks = forks.mapNotNull { fork -> fork.parent?.let { parent -> instant(fork.createdAt)?.let { CrawledFork(it, parent.id) } } },
        )
    }

    /** A page, retried twice: a run that can't read the whole ranking fails rather than publish a partial one. */
    private suspend fun page(mode: String, sort: String, page: Int): List<SearchRepoJson> {
        val url = "$host/api/v1/repos/search?mode=$mode&sort=$sort&order=desc&limit=50&page=$page"
        var failure: String? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(retryDelayMillis)
            try {
                val response = http.get(url)
                if (response.status.isSuccess()) return json.decodeFromString<SearchJson>(response.bodyAsText()).data
                failure = "HTTP ${response.status.value}"
            } catch (e: IOException) {
                failure = e.toString()
            }
        }
        error("Couldn't read $url: $failure")
    }

    private fun SearchRepoJson.toCrawled() = instant(createdAt)?.let {
        CrawledRepo(id, owner.login, name, description?.ifBlank { null }, language?.ifBlank { null }, stars, forks, it, owner.avatarUrl)
    }

    private fun instant(value: String?) = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private companion object {
        const val ATTEMPTS = 3
    }
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
