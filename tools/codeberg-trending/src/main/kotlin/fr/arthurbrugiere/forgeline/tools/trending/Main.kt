package fr.arthurbrugiere.forgeline.tools.trending

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.ForgejoTrendingCrawler
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.TrendingState
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.lists
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.record
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `codeberg-trending <previous state.json> <output directory>`: reads Codeberg once, within 30 anonymous requests,
 * and writes `state.json` and `trending.json` to the output directory. A missing or empty previous state starts the
 * history afresh. The measuring itself lives in `forge:forgejo`, shared with the phone's own measurements.
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) { "Usage: codeberg-trending <previous state.json> <output directory>" }
    val previous = File(args[0]).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        ?.let { json.decodeFromString<TrendingState>(it) } ?: TrendingState()
    val crawl = forgejoHttpClient(OkHttp.create()).use { ForgejoTrendingCrawler(it, ForgeInstance.Codeberg).crawl() }
    val state = previous.record(crawl)
    val output = File(args[1]).apply { mkdirs() }
    File(output, "state.json").writeText(json.encodeToString(state))
    File(output, "trending.json").writeText(json.encodeToString(state.lists(crawl)))
    println("Tracked ${state.repos.size} repositories over ${state.days.size} days; ${crawl.forks.size} recent forks read.")
}

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
