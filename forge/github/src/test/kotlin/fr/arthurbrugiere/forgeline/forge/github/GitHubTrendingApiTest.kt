package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class GitHubTrendingApiTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val page = requireNotNull(javaClass.getResource("/github/trending_daily.html")).readText()

    private fun kotlinx.coroutines.test.TestScope.api(respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData) =
        GitHubTrendingApi(
            gitHubHttpClient(MockEngine { requests += it; respond() }),
            parseDispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun requests_each_period_and_parses_the_page() = runTest {
        val api = api { respond(page, headers = headersOf(HttpHeaders.ContentType, "text/html")) }

        val daily = api.trending(TrendingPeriod.DAILY)
        api.trending(TrendingPeriod.WEEKLY)
        api.trending(TrendingPeriod.MONTHLY)

        assertThat((daily as ForgeResult.Success).value).hasSize(15)
        assertThat(requests.map { it.url.toString() }).containsExactly(
            "https://github.com/trending?since=daily",
            "https://github.com/trending?since=weekly",
            "https://github.com/trending?since=monthly",
        ).inOrder()
    }

    @Test
    fun http_errors_and_throttling_are_reported() = runTest {
        assertThat(api { respond("", HttpStatusCode.ServiceUnavailable) }.trending(TrendingPeriod.DAILY))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(503, null)))
        assertThat(api { respond("", HttpStatusCode.TooManyRequests, headersOf("retry-after", "60")) }.trending(TrendingPeriod.DAILY))
            .isEqualTo(ForgeResult.Failure(ForgeError.RateLimited(60)))
    }

    @Test
    fun io_failures_are_network_errors() = runTest {
        assertThat(api { throw IOException("offline") }.trending(TrendingPeriod.DAILY))
            .isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }
}
