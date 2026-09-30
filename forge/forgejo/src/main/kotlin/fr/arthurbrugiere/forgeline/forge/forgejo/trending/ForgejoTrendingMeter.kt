package fr.arthurbrugiere.forgeline.forge.forgejo.trending

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeasurement
import fr.arthurbrugiere.forgeline.core.forge.TrendingMeter
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.Clock

/**
 * Trending measured on the phone, the way the daily job measures Codeberg's: for a self-hosted server nobody
 * publishes a list for, or for Codeberg when asked to.
 */
class ForgejoTrendingMeter(
    private val http: HttpClient,
    private val forge: ForgeInstance,
    private val clock: Clock = Clock.systemUTC(),
    private val topPages: Int = if (forge == ForgeInstance.Codeberg) ForgejoTrendingCrawler.TOP_PAGES else SELF_HOSTED_PAGES,
    private val retryDelayMillis: Long = 10_000,
) : TrendingMeter {

    override suspend fun measure(token: String?, previousState: String?): ForgeResult<TrendingMeasurement> = try {
        // A history that doesn't read (an older format) starts afresh rather than stop measuring.
        val previous = previousState?.let { runCatching { json.decodeFromString<TrendingState>(it) }.getOrNull() } ?: TrendingState()
        val crawl = ForgejoTrendingCrawler(http, forge, token, topPages, clock = clock, retryDelayMillis = retryDelayMillis).crawl()
        val state = previous.record(crawl)
        val file = state.lists(crawl)
        val lists = TrendingPeriod.entries.associateWith { period -> file.list(period).map { it.toModel(forge) } }
        ForgeResult.Success(TrendingMeasurement(json.encodeToString(state), lists))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ForgeResult.Failure(ForgeError.Network)
    } catch (e: SerializationException) {
        ForgeResult.Failure(ForgeError.Http(200, "Unreadable search answer"))
    }

    private companion object {
        /** Up to 1,000 repositories: plenty for a self-hosted server, and the crawl stops sooner on a small one. */
        const val SELF_HOSTED_PAGES = 20
    }
}
