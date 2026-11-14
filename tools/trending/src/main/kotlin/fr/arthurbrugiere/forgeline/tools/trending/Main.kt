package fr.arthurbrugiere.forgeline.tools.trending

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.ForgejoTrendingCrawler
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.TrendingState
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.lists
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.record
import fr.arthurbrugiere.forgeline.forge.gitlab.trending.GitLabTrendingCrawler
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `trending <codeberg|gitlab> <previous state.json> <output directory>`: reads the forge once and writes
 * `state.json` and `trending.json` to the output directory. A missing or empty previous state starts the history
 * afresh. The measuring itself lives in `forge:forgejo`, shared with the phone's own measurements; only the reading
 * differs per forge.
 *
 * Codeberg is read with the `CODEBERG_TOKEN` environment variable when it is set (the workflow passes the live checks'
 * read-only token): from 2026-11-10 Codeberg refuses repository search to anyone signed out, with an HTTP 429 saying
 * search "is seeing a high influx of requests", so the job's first page failed every day. Without it, the job reads
 * anonymously. A real rate limit is waited out: up to [JOB_ATTEMPTS] tries a page, 30 s apart and doubling, or as long
 * as Codeberg asks. gitlab.com is read the same way with `GITLAB_TOKEN` (the live checks' read-only token), in case it
 * starts refusing signed-out reads too; only its public projects are asked for.
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3 && args[0] in FORGES) { "Usage: trending <${FORGES.joinToString("|")}> <previous state.json> <output directory>" }
    val previous = File(args[1]).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        ?.let { json.decodeFromString<TrendingState>(it) } ?: TrendingState()
    forgejoHttpClient(OkHttp.create()).use { http ->
        val (state, file) = when (args[0]) {
            "codeberg" -> {
                val token = System.getenv("CODEBERG_TOKEN")?.trim()?.ifEmpty { null }
                println("codeberg: reading ${if (token != null) "with a token" else "anonymously"}.")
                val crawl = ForgejoTrendingCrawler(http, ForgeInstance.Codeberg, token, retryDelayMillis = 30_000, attempts = JOB_ATTEMPTS).crawl()
                previous.record(crawl).let { it to it.lists(crawl) }
            }
            else -> {
                val token = System.getenv("GITLAB_TOKEN")?.trim()?.ifEmpty { null }
                println("gitlab: reading ${if (token != null) "with a token" else "anonymously"}.")
                val gitlab = GitLabTrendingCrawler(http, token = token)
                val crawl = gitlab.crawl()
                previous.record(crawl).let { it to gitlab.withLanguages(it.lists(crawl), crawl) }
            }
        }
        val output = File(args[2]).apply { mkdirs() }
        File(output, "state.json").writeText(json.encodeToString(state))
        File(output, "trending.json").writeText(json.encodeToString(file))
        println("${args[0]}: tracked ${state.repos.size} repositories over ${state.days.size} days.")
    }
}

private val FORGES = listOf("codeberg", "gitlab")

/** 30 s, 1, 2 then 4 minutes between tries: under ten minutes for a page before giving up for the day. */
private const val JOB_ATTEMPTS = 5

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
