package fr.arthurbrugiere.forgeline.forge.gitlab.trending

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.TrendingState
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.lists
import fr.arthurbrugiere.forgeline.forge.forgejo.trending.record
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
import java.time.temporal.ChronoUnit

/** Against real gitlab.com answers captured on 2026-10-01 (trimmed to two projects). */
class GitLabTrendingCrawlerTest {
    private val now = Instant.parse("2026-10-01T02:17:00Z")
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/gitlab/$name")).readText()

    /** A full page of 100 projects with [stars] each. */
    private fun fullPage(stars: Int, from: Int) = "[" + (from until from + 100).joinToString(",") {
        """{"id":$it,"path":"p$it","namespace":{"full_path":"group/sub"},"star_count":$stars,"created_at":"2024-01-01T00:00:00.000Z"}"""
    } + "]"

    private fun crawler(
        at: Instant = now,
        answer: (HttpRequestData) -> String? = { request -> fullPage(100, request.url.parameters["page"]!!.toInt() * 100) },
    ) = GitLabTrendingCrawler(
        forgejoHttpClient(
            MockEngine { request ->
                requests += request
                val body = answer(request)
                if (body == null) respond("", HttpStatusCode.InternalServerError)
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        clock = Clock.fixed(at, ZoneOffset.UTC),
        retryDelayMillis = 0,
    )

    @Test
    fun gitlab_is_read_by_stars_within_its_budget_without_signing_in() = runTest {
        crawler().crawl()

        assertThat(requests).hasSize(GitLabTrendingCrawler.TOP_PAGES)
        assertThat(requests.map { it.url.host }.toSet()).containsExactly("gitlab.com")
        assertThat(requests.map { it.url.parameters["page"] }).containsExactlyElementsIn((1..GitLabTrendingCrawler.TOP_PAGES).map { "$it" }).inOrder()
        assertThat(requests.map { Triple(it.url.parameters["order_by"], it.url.parameters["sort"], it.url.parameters["per_page"]) }.toSet())
            .containsExactly(Triple("star_count", "desc", "100"))
        assertThat(requests.none { it.headers.contains(HttpHeaders.Authorization) }).isTrue()
    }

    @Test
    fun reads_projects_under_their_full_namespace() = runTest {
        val crawl = crawler(answer = { fixture("projects_stars.json") }).crawl()

        assertThat(crawl.at).isEqualTo(now)
        assertThat(crawl.top.map { it.id }).containsExactly(13083L, 278964L).inOrder()
        val foss = crawl.top.first()
        assertThat(foss.owner to foss.name).isEqualTo("gitlab-org" to "gitlab-foss")
        assertThat(foss.stars).isEqualTo(7176)
        assertThat(foss.forks).isEqualTo(8276)
        assertThat(foss.createdAt).isEqualTo(Instant.parse("2013-09-26T06:02:36Z"))
        assertThat(foss.ownerAvatarUrl).startsWith("https://gitlab.com/uploads/")
        assertThat(crawl.forks).isEmpty()
        // Two projects: fewer than a page, so one request tells everything.
        assertThat(requests).hasSize(1)
    }

    @Test
    fun reading_stops_once_projects_have_too_few_stars_to_trend() = runTest {
        crawler(answer = { request -> if (request.url.parameters["page"] == "1") fullPage(40, 0) else fullPage(1, 1000) }).crawl()

        assertThat(requests.map { it.url.parameters["page"] }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun a_page_that_fails_once_is_retried() = runTest {
        var failed = false
        val crawl = crawler(answer = { request ->
            if (!failed && request.url.parameters["page"] == "3") {
                failed = true
                null
            } else fullPage(100, request.url.parameters["page"]!!.toInt() * 100)
        }).crawl()

        assertThat(crawl.top).isNotEmpty()
        assertThat(requests).hasSize(GitLabTrendingCrawler.TOP_PAGES + 1)
    }

    @Test
    fun a_page_that_keeps_failing_fails_the_crawl_rather_than_record_a_partial_ranking() = runTest {
        val crawler = crawler(answer = { request -> if (request.url.parameters["page"] == "5") null else fullPage(100, 0) })

        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { crawler.crawl() } }
    }

    @Test
    fun only_listed_projects_are_asked_for_their_language() = runTest {
        val yesterday = crawler(now.minus(1, ChronoUnit.DAYS), answer = { fixture("projects_stars.json") }).crawl()
        // gitlab-foss gains 10 stars overnight; gitlab gains none and stays off the lists.
        val gained = fixture("projects_stars.json").replace("\"star_count\": 7176", "\"star_count\": 7186")
        val today = crawler(answer = { request ->
            if (request.url.encodedPath.endsWith("/languages")) fixture("languages.json") else gained
        })
        val crawl = today.crawl()
        val state = TrendingState().record(yesterday).record(crawl)
        requests.clear()

        val file = today.withLanguages(state.lists(crawl), crawl)

        assertThat(file.daily.map { it.name to it.periodStars }).containsExactly("gitlab-foss" to 10)
        assertThat(file.daily.single().language).isEqualTo("Ruby")
        assertThat(requests.map { it.url.encodedPath }).containsExactly("/api/v4/projects/13083/languages")
    }

    @Test
    fun a_language_that_cant_be_read_is_left_out() = runTest {
        val yesterday = crawler(now.minus(1, ChronoUnit.DAYS), answer = { fixture("projects_stars.json") }).crawl()
        val gained = fixture("projects_stars.json").replace("\"star_count\": 7176", "\"star_count\": 7186")
        val today = crawler(answer = { request -> if (request.url.encodedPath.endsWith("/languages")) null else gained })
        val crawl = today.crawl()

        val file = today.withLanguages(TrendingState().record(yesterday).record(crawl).lists(crawl), crawl)

        assertThat(file.daily.single().language).isNull()
    }
}
