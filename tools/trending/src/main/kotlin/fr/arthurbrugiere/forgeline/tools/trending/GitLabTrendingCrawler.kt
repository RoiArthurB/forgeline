package fr.arthurbrugiere.forgeline.tools.trending

import fr.arthurbrugiere.forgeline.forge.forgejo.TrendingEntry
import fr.arthurbrugiere.forgeline.forge.forgejo.TrendingFile
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.Crawl
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.CrawledRepo
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.MIN_PERIOD_STARS
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Reads gitlab.com for Trending, which GitLab doesn't have (its Explore tab was removed in GitLab 19.0): its projects
 * by stars, measured the same way as Codeberg's (see docs/GITLAB.md#trending). Anonymous, like Codeberg's job.
 *
 * Two differences from Forgejo: GitLab can't list only forks, so there are no new forks to break ties with, and its
 * project list has no language, so [withLanguages] asks for it only for the projects that made a list.
 */
class GitLabTrendingCrawler(
    private val http: HttpClient,
    private val host: String = "gitlab.com",
    private val topPages: Int = TOP_PAGES,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = 10_000,
) {
    private val webUrl = "https://$host"

    suspend fun crawl(): Crawl {
        val at = clock.instant()
        val top = mutableListOf<ProjectJson>()
        for (page in 1..topPages) {
            val projects = read<List<ProjectJson>>("$webUrl/api/v4/projects?order_by=star_count&sort=desc&simple=true&per_page=$PAGE_SIZE&page=$page")
            top += projects
            if (projects.size < PAGE_SIZE || (projects.lastOrNull()?.stars ?: 0) < MIN_PERIOD_STARS) break
        }
        return Crawl(at, top.distinctBy { it.id }.mapNotNull { it.toCrawled() }, forks = emptyList())
    }

    /**
     * [file] with each listed project's main language: one request per project, at most three lists of 25. A project
     * whose languages can't be read keeps none rather than fail the day.
     */
    suspend fun withLanguages(file: TrendingFile, crawl: Crawl): TrendingFile {
        val ids = crawl.top.associate { (it.owner to it.name) to it.id }
        val listed = (file.daily + file.weekly + file.monthly).map { it.owner to it.name }.distinct()
        val languages = listed.associateWith { key -> ids[key]?.let { language(it) } }
        fun List<TrendingEntry>.named() = map { it.copy(language = languages[it.owner to it.name]) }
        return file.copy(daily = file.daily.named(), weekly = file.weekly.named(), monthly = file.monthly.named())
    }

    /** The language with the largest share, as GitLab names it. */
    private suspend fun language(id: Long): String? = try {
        read<Map<String, Double>>("$webUrl/api/v4/projects/$id/languages").maxByOrNull { it.value }?.key
    } catch (e: IOException) {
        null
    }

    private suspend inline fun <reified T> read(url: String): T {
        var failure: String? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(retryDelayMillis)
            try {
                val response = http.get(url)
                if (response.status.isSuccess()) return json.decodeFromString<T>(response.bodyAsText())
                failure = "HTTP ${response.status.value}"
            } catch (e: IOException) {
                failure = e.toString()
            } catch (e: SerializationException) {
                failure = e.toString()
            }
        }
        throw IOException("Couldn't read $url: $failure")
    }

    private fun ProjectJson.toCrawled() = runCatching { OffsetDateTime.parse(createdAt).toInstant() }.getOrNull()?.let {
        CrawledRepo(id, namespace.fullPath, path, description?.ifBlank { null }, null, stars, forks, it, namespace.avatarUrl?.let(::absolute))
    }

    // Uploaded avatars come as paths on the forge; Gravatar ones as full addresses.
    private fun absolute(url: String) = if (url.startsWith("/")) webUrl + url else url

    companion object {
        /** The top 5,000 by stars: every project with 11 stars or more on 2026-10-01, about three minutes. */
        const val TOP_PAGES = 50
        const val PAGE_SIZE = 100
        private const val ATTEMPTS = 3
    }
}

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class NamespaceJson(@SerialName("full_path") val fullPath: String, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class ProjectJson(
    val id: Long,
    val path: String,
    val namespace: NamespaceJson,
    val description: String? = null,
    @SerialName("star_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    @SerialName("created_at") val createdAt: String,
)
