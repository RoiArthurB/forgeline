package fr.arthurbrugiere.forgeline.tools.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Against real codeberg.org answers captured on 2026-09-30 (trimmed to two repositories). */
class CrawlerTest {
    private val now = Instant.parse("2026-09-30T02:17:00Z")
    private val requests = mutableListOf<HttpRequestData>()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/codeberg/$name")).readText()

    private fun crawler(fail: (HttpRequestData) -> HttpStatusCode? = { null }) = Crawler(
        forgejoHttpClient(
            MockEngine { request ->
                requests += request
                val status = fail(request)
                val body = if (request.url.parameters["mode"] == "fork") fixture("search-forks.json") else fixture("search-stars.json")
                if (status != null) respond("", status) else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        retryDelayMillis = 0,
    )

    @Test
    fun spends_exactly_the_30_request_budget() = runTest {
        crawler().crawl()

        assertThat(requests).hasSize(TOP_PAGES + FORK_PAGES)
        val top = requests.filter { it.url.parameters["mode"] == "source" }
        assertThat(top.map { it.url.parameters["page"] }).containsExactlyElementsIn((1..TOP_PAGES).map { "$it" }).inOrder()
        assertThat(top.map { it.url.parameters["sort"] to it.url.parameters["limit"] }.toSet()).containsExactly("stars" to "50")
        assertThat(requests.filter { it.url.parameters["mode"] == "fork" }.map { it.url.parameters["sort"] }).containsExactly("created", "created")
        assertThat(requests.none { it.headers.contains(HttpHeaders.Authorization) }).isTrue()
    }

    @Test
    fun reads_repositories_and_the_parents_of_new_forks() = runTest {
        val crawl = crawler().crawl()

        assertThat(crawl.at).isEqualTo(now)
        // Every page answers the same fixture here: the same repository is only kept once.
        val zig = crawl.top.first()
        assertThat(crawl.top.map { it.id }).containsExactly(948270L, 73144L).inOrder()
        assertThat(zig.owner to zig.name).isEqualTo("ziglang" to "zig")
        assertThat(zig.stars).isEqualTo(6712)
        assertThat(zig.language).isEqualTo("Zig")
        assertThat(zig.createdAt).isEqualTo(Instant.parse("2025-11-25T17:51:45Z"))
        assertThat(zig.ownerAvatarUrl).isNotNull()
        assertThat(crawl.forks.map { it.parentId }.toSet()).containsExactly(263748L, 463034L)
    }

    @Test
    fun a_page_that_fails_once_is_retried() = runTest {
        var failed = false
        val crawl = crawler { request ->
            if (!failed && request.url.parameters["page"] == "3") HttpStatusCode.BadGateway.also { failed = true } else null
        }.crawl()

        assertThat(crawl.top).isNotEmpty()
        assertThat(requests).hasSize(TOP_PAGES + FORK_PAGES + 1)
    }

    @Test
    fun a_page_that_keeps_failing_fails_the_run_rather_than_publish_a_partial_ranking() = runTest {
        val crawler = crawler { request -> HttpStatusCode.InternalServerError.takeIf { request.url.parameters["page"] == "5" } }

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { crawler.crawl() } }
    }
}
