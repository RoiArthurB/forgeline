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
 * `trending <codeberg|gitlab> <previous state.json> <output directory>`: reads the forge once, anonymously, and writes
 * `state.json` and `trending.json` to the output directory. A missing or empty previous state starts the history
 * afresh. The measuring itself lives in `forge:forgejo`, shared with the phone's own measurements; only the reading
 * differs per forge.
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3 && args[0] in FORGES) { "Usage: trending <${FORGES.joinToString("|")}> <previous state.json> <output directory>" }
    val previous = File(args[1]).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        ?.let { json.decodeFromString<TrendingState>(it) } ?: TrendingState()
    forgejoHttpClient(OkHttp.create()).use { http ->
        val (state, file) = when (args[0]) {
            "codeberg" -> {
                val crawl = ForgejoTrendingCrawler(http, ForgeInstance.Codeberg).crawl()
                previous.record(crawl).let { it to it.lists(crawl) }
            }
            else -> {
                val gitlab = GitLabTrendingCrawler(http)
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

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
