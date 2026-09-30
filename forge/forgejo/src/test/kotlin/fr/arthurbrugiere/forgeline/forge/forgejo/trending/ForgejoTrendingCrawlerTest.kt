package fr.arthurbrugiere.forgeline.forge.forgejo.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
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
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Against real codeberg.org answers captured on 2026-09-30 (trimmed to two repositories). */
class ForgejoTrendingCrawlerTest {
    private val now = Instant.parse("2026-09-30T02:17:00Z")
    private val requests = mutableListOf<HttpRequestData>()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/codeberg/$name")).readText()

    /** A full page of 50 repositories with [stars] each, for servers bigger than one page. */
    private fun fullPage(stars: Int, from: Int) = """{"ok":true,"data":[""" + (from until from + 50).joinToString(",") {
        """{"id":$it,"name":"r$it","owner":{"login":"o"},"stars_count":$stars,"created_at":"2024-01-01T00:00:00Z"}"""
    } + "]}"

    private fun crawler(
        forge: ForgeInstance = ForgeInstance.Codeberg,
        token: String? = null,
        topPages: Int = ForgejoTrendingCrawler.TOP_PAGES,
        answer: (HttpRequestData) -> String? = { request ->
            if (request.url.parameters["mode"] == "fork") fixture("search_forks.json") else fullPage(100, request.url.parameters["page"]!!.toInt() * 100)
        },
    ) = ForgejoTrendingCrawler(
        forgejoHttpClient(
            MockEngine { request ->
                requests += request
                val body = answer(request)
                if (body == null) respond("", HttpStatusCode.InternalServerError)
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        forge,
        token,
        topPages,
        clock = Clock.fixed(now, ZoneOffset.UTC),
        retryDelayMillis = 0,
    )

    @Test
    fun codeberg_is_read_within_the_30_request_budget() = runTest {
        crawler().crawl()

        assertThat(requests).hasSize(ForgejoTrendingCrawler.TOP_PAGES + 1)
        val top = requests.filter { it.url.parameters["mode"] == "source" }
        assertThat(top.map { it.url.parameters["page"] }).containsExactlyElementsIn((1..ForgejoTrendingCrawler.TOP_PAGES).map { "$it" }).inOrder()
        assertThat(top.map { it.url.parameters["sort"] to it.url.parameters["limit"] }.toSet()).containsExactly("stars" to "50")
        // The forks fixture holds two forks: fewer than a page, so the second page isn't asked.
        assertThat(requests.filter { it.url.parameters["mode"] == "fork" }.map { it.url.parameters["sort"] }).containsExactly("created")
        assertThat(requests.none { it.headers.contains(HttpHeaders.Authorization) }).isTrue()
    }

    @Test
    fun reads_repositories_and_the_parents_of_new_forks() = runTest {
        val crawl = crawler(answer = { request ->
            fixture(if (request.url.parameters["mode"] == "fork") "search_forks.json" else "search_stars.json")
        }).crawl()

        assertThat(crawl.at).isEqualTo(now)
        val zig = crawl.top.first()
        assertThat(crawl.top.map { it.id }).containsExactly(948270L, 73144L).inOrder()
        assertThat(zig.owner to zig.name).isEqualTo("ziglang" to "zig")
        assertThat(zig.stars).isEqualTo(6712)
        assertThat(zig.createdAt).isEqualTo(Instant.parse("2025-11-25T17:51:45Z"))
        assertThat(crawl.forks.map { it.parentId }.toSet()).containsExactly(263748L, 463034L)
    }

    @Test
    fun a_small_server_stops_when_it_runs_out_of_repositories() = runTest {
        // Two repositories: fewer than a page, so one request tells everything.
        crawler(answer = { request ->
            fixture(if (request.url.parameters["mode"] == "fork") "search_forks.json" else "search_stars.json")
        }).crawl()

        assertThat(requests.count { it.url.parameters["mode"] == "source" }).isEqualTo(1)
    }

    @Test
    fun reading_stops_once_repositories_have_too_few_stars_to_trend() = runTest {
        crawler(answer = { request ->
            when {
                request.url.parameters["mode"] == "fork" -> fixture("search_forks.json")
                request.url.parameters["page"] == "1" -> fullPage(40, 0)
                else -> fullPage(1, 1000)
            }
        }).crawl()

        assertThat(requests.filter { it.url.parameters["mode"] == "source" }.map { it.url.parameters["page"] }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun a_self_hosted_server_is_read_on_its_own_host_with_its_token() = runTest {
        val selfHosted = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
        crawler(selfHosted, token = "s3cret", topPages = 20, answer = { request ->
            fixture(if (request.url.parameters["mode"] == "fork") "search_forks.json" else "search_stars.json")
        }).crawl()

        assertThat(requests.map { it.url.host }.toSet()).containsExactly("git.example.org")
        assertThat(requests.map { it.headers[HttpHeaders.Authorization] }.toSet()).containsExactly("token s3cret")
    }

    @Test
    fun a_page_that_fails_once_is_retried() = runTest {
        var failed = false
        val crawl = crawler(answer = { request ->
            if (!failed && request.url.parameters["page"] == "3" && request.url.parameters["mode"] == "source") {
                failed = true
                null
            } else if (request.url.parameters["mode"] == "fork") fixture("search_forks.json") else fullPage(100, request.url.parameters["page"]!!.toInt() * 100)
        }).crawl()

        assertThat(crawl.top).isNotEmpty()
        assertThat(requests).hasSize(ForgejoTrendingCrawler.TOP_PAGES + 2)
    }

    @Test
    fun a_page_that_keeps_failing_fails_the_crawl_rather_than_record_a_partial_ranking() = runTest {
        val crawler = crawler(answer = { request -> if (request.url.parameters["page"] == "5") null else fullPage(100, 0) })

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { crawler.crawl() } }
    }
}
