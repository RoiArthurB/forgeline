package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GitHubTrendingApi(
    private val httpClient: HttpClient,
    private val webBaseUrl: String = "https://github.com",
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TrendingApi {

    override val forge: ForgeInstance = ForgeInstance.GitHub

    override suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>> = gitHubCall {
        val response = httpClient.get("$webBaseUrl/trending") {
            parameter("since", period.query)
            accept(ContentType.Text.Html)
            // GitHub omits the repo list for non-browser agents; identify as a compatible bot.
            header(HttpHeaders.UserAgent, TRENDING_USER_AGENT)
        }
        response.toResult {
            val html = bodyAsText()
            withContext(parseDispatcher) { GitHubTrendingParser.parse(html) }
        }
    }

    private val TrendingPeriod.query: String
        get() = when (this) {
            TrendingPeriod.DAILY -> "daily"
            TrendingPeriod.WEEKLY -> "weekly"
            TrendingPeriod.MONTHLY -> "monthly"
        }

    private companion object {
        const val TRENDING_USER_AGENT = "Mozilla/5.0 (compatible; Forgeline; +https://github.com/RoiArthurB/forgeline)"
    }
}
