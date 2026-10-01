package fr.arthurbrugiere.forgeline.forge.gitlab.trending

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeasurement
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.TrendingState
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.lists
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.record
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Clock

/**
 * gitlab.com's Trending measured on the phone, the way the daily job measures it: for anyone signed in to GitLab who'd
 * rather not use the shared list. Anonymous, like the job, so [measure]'s token is unused.
 */
class GitLabTrendingMeter(
    private val http: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = 10_000,
) : TrendingMeter {

    override suspend fun measure(token: String?, previousState: String?): ForgeResult<TrendingMeasurement> = try {
        // A history that doesn't read (an older format) starts afresh rather than stop measuring.
        val previous = previousState?.let { runCatching { json.decodeFromString<TrendingState>(it) }.getOrNull() } ?: TrendingState()
        val crawler = GitLabTrendingCrawler(http, forge.host, clock = clock, retryDelayMillis = retryDelayMillis)
        val crawl = crawler.crawl()
        val state = previous.record(crawl)
        val file = crawler.withLanguages(state.lists(crawl), crawl)
        val lists = TrendingPeriod.entries.associateWith { period -> file.list(period).map { it.toModel(forge) } }
        ForgeResult.Success(TrendingMeasurement(json.encodeToString(state), lists))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ForgeResult.Failure(ForgeError.Network)
    }

    private companion object {
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
