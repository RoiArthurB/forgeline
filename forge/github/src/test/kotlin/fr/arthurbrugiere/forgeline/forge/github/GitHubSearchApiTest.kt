package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Fixtures are real public search responses captured on 2026-09-27 (trimmed to a few items). */
class GitHubSearchApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/search/$name.json")).readText()

    private fun MockRequestHandleScope.json(body: String, headers: Map<String, String> = emptyMap(), status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubSearchApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val next = mapOf("Link" to """<https://api.github.com/search/repositories?q=x&page=2>; rel="next", <https://api.github.com/search/repositories?q=x&page=8>; rel="last"""")

    @Test
    fun finds_repositories() = runTest {
        val page = api { json(fixture("repositories"), next) }.repositories(null, "forge client android").value()

        assertThat(page.totalCount).isEqualTo(23)
        assertThat(page.nextPage).isEqualTo(2)
        assertThat(page.items.first()).isEqualTo(
            RepoSummary(
                id = RepoId("evokelektrique", "tunnel-forge"),
                description = page.items.first().description,
                language = "Kotlin",
                stars = 79,
                forks = page.items.first().forks,
                isFork = false,
                updatedAt = Instant.parse("2026-09-19T08:01:15Z"),
            ),
        )
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/search/repositories")
        assertThat(url.parameters["q"]).isEqualTo("forge client android")
        assertThat(url.parameters["page"]).isEqualTo("1")
        assertThat(url.parameters["per_page"]).isEqualTo("30")
    }

    @Test
    fun finds_issues_and_pull_requests_with_their_repo() = runTest {
        val page = api { json(fixture("issues")) }.issues("tok", "repo:paperclipai/paperclip heartbeat").value()

        assertThat(page.totalCount).isEqualTo(5322)
        assertThat(page.nextPage).isNull()
        assertThat(page.items.map { it.repo }.distinct()).containsExactly(RepoId("paperclipai", "paperclip"))
        val issue = page.items.first().issue
        assertThat(issue.number).isEqualTo(12299)
        assertThat(issue.isPullRequest).isFalse()
        assertThat(issue.state).isEqualTo(IssueState.OPEN)
        assertThat(issue.comments).isEqualTo(0)
        val merged = page.items.last().issue
        assertThat(merged.number).isEqualTo(13973)
        assertThat(merged.isPullRequest).isTrue()
        assertThat(merged.state).isEqualTo(IssueState.MERGED)
        assertThat(merged.title).isEqualTo("fix(heartbeat): claim task ownership with queued runs")
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun finds_people_and_organizations() = runTest {
        val page = api { json(fixture("users")) }.users(null, "github", page = 3).value()

        assertThat(page.items.map { it.login }).containsExactly("github", "githubteacher", "githubnext").inOrder()
        assertThat(page.items.first()).isEqualTo(UserSummary("github", page.items.first().avatarUrl, isOrganization = true))
        assertThat(page.items[1].isOrganization).isFalse()
        assertThat(requests.single().url.parameters["page"]).isEqualTo("3")
    }

    @Test
    fun reports_the_search_rate_limit() = runTest {
        val result = api {
            json(
                """{"message":"API rate limit exceeded"}""",
                mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1790489541"),
                HttpStatusCode.Forbidden,
            )
        }.users(null, "x")

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.RateLimited::class.java)
    }
}
