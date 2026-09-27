package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
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

/**
 * The fixture is synthetic (a public repo must not hold someone's real feed) but mirrors the shape
 * of a real /received_events response inspected on 2026-09-27, including the trimmed pull_request
 * objects (no title) and the "merged" action.
 */
class GitHubFeedApiTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val fixture = requireNotNull(javaClass.getResource("/github/feed/received_events.json")).readText()

    private fun MockRequestHandleScope.json(body: String, headers: Map<String, String> = emptyMap(), status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubFeedApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private suspend fun events(): List<FeedEvent> = api { json(fixture) }.receivedEvents("tok", "me").value().events!!

    @Test
    fun parses_each_kind_of_activity() = runTest {
        val actions = events().map { it.action }

        assertThat(actions).containsExactly(
            FeedAction.Starred,
            FeedAction.PullRequest(PullRequestAction.MERGED, 43),
            FeedAction.Issue(IssueAction.CLOSED, 42, "Launch fails on cold start"),
            FeedAction.Starred,
            FeedAction.Forked(RepoId("carol", "tools")),
            FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = false),
            FeedAction.Commented(44, "Add telemetry opt-out", isPullRequest = true),
            FeedAction.Reviewed(44, ReviewState.APPROVED),
            FeedAction.Commented(44, null, isPullRequest = true),
            FeedAction.Pushed("main"),
            FeedAction.Branch("v1.2.0", isTag = true, deleted = false),
            FeedAction.CreatedRepo("A fresh idea"),
            FeedAction.Branch("feature", isTag = false, deleted = true),
            FeedAction.Issue(IssueAction.OPENED, 7, "Crash on empty config"),
            FeedAction.MadePublic,
            FeedAction.AddedMember("bob"),
            FeedAction.PullRequest(PullRequestAction.OPENED, 8),
        ).inOrder()
    }

    @Test
    fun drops_label_changes_and_unknown_events() = runTest {
        val events = events()

        assertThat(events).hasSize(17)
        assertThat(events.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun keeps_who_did_it_where_and_when() = runTest {
        assertThat(events().first()).isEqualTo(
            FeedEvent(
                id = "50000000099",
                actor = ForgeUser("alice", null, "https://avatars.githubusercontent.com/u/1?"),
                repo = RepoId("acme", "rocket"),
                action = FeedAction.Starred,
                createdAt = Instant.parse("2026-09-27T09:50:00Z"),
            ),
        )
    }

    @Test
    fun asks_for_the_users_received_events_one_page_at_a_time() = runTest {
        val page = api {
            json(fixture, mapOf("Link" to """<https://api.github.com/user/1/received_events?per_page=100&page=3>; rel="next""""))
        }.receivedEvents("tok", "me", page = 2).value()

        assertThat(page.nextPage).isEqualTo(3)
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/users/me/received_events")
        assertThat(url.parameters["page"]).isEqualTo("2")
        assertThat(url.parameters["per_page"]).isEqualTo("100")
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun only_asks_for_changes_since_the_last_sync() = runTest {
        val page = api {
            json("", mapOf("Last-Modified" to "Sun, 27 Sep 2026 09:50:00 GMT", "X-Poll-Interval" to "60"), HttpStatusCode.NotModified)
        }.receivedEvents("tok", "me", ifModifiedSince = "Sun, 27 Sep 2026 09:50:00 GMT").value()

        assertThat(requests.single().headers[HttpHeaders.IfModifiedSince]).isEqualTo("Sun, 27 Sep 2026 09:50:00 GMT")
        assertThat(page.events).isNull()
        assertThat(page.lastModified).isEqualTo("Sun, 27 Sep 2026 09:50:00 GMT")
        assertThat(page.pollIntervalSeconds).isEqualTo(60)
    }

    @Test
    fun reports_forge_errors() = runTest {
        val result = api { json("""{"message":"Not Found"}""", status = HttpStatusCode.NotFound) }.receivedEvents(null, "ghost")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }
}
