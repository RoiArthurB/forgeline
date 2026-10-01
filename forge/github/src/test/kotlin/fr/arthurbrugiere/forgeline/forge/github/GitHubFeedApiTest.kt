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

    private val since = Instant.parse("2026-09-01T00:00:00Z")

    private fun star(fullName: String, pushedAt: String = "2026-09-25T10:00:00Z", discussions: Boolean = false, archived: Boolean = false): String {
        val owner = fullName.substringBefore('/')
        return """{"full_name":"$fullName","pushed_at":"$pushedAt","has_discussions":$discussions,"archived":$archived,""" +
            """"owner":{"login":"$owner","avatar_url":"https://avatars.example/$owner"}}"""
    }

    private val immich = """{"releases":{"nodes":[{"tagName":"v3.3.0-rc.1","name":"v3.3.0-rc.1","publishedAt":"2026-09-30T22:05:07Z","isPrerelease":true,"isDraft":false,"author":{"login":"alextran","avatarUrl":"https://avatars.example/alextran"}}]},
        "discussions":{"nodes":[
          {"number":901,"title":"[Feature] Death date for people","createdAt":"2026-09-30T23:06:29Z","category":{"slug":"feature-request"},"author":{"login":"someone","avatarUrl":null}},
          {"number":900,"title":"v3.3.0-rc.1","createdAt":"2026-09-30T22:05:07Z","category":{"slug":"announcements"},"author":{"login":"alextran","avatarUrl":"https://avatars.example/alextran"}},
          {"number":880,"title":"Immich turns three","createdAt":"2026-09-20T10:00:00Z","category":{"slug":"announcements"},"author":{"login":"alextran","avatarUrl":"https://avatars.example/alextran"}}]}}"""
    private val draft = """{"releases":{"nodes":[{"tagName":"v9","name":null,"publishedAt":null,"isPrerelease":false,"isDraft":true,"author":null}]}}"""
    private val longAgo = """{"releases":{"nodes":[{"tagName":"v1.0.0","name":"First","publishedAt":"2024-01-01T00:00:00Z","isPrerelease":false,"isDraft":false,"author":null}]}}"""
    private val netbird = """{"releases":{"nodes":[{"tagName":"v0.80.0","name":"","publishedAt":"2026-09-29T12:00:00Z","isPrerelease":false,"isDraft":false,"author":null}]}}"""

    private fun HttpRequestData.text() = (body as io.ktor.http.content.TextContent).text

    /** Two pages of stars; the repositories worth asking are immich, acme/draft, slow/project (page 1) and netbird (page 2). */
    private fun starredApi() = api { request ->
        when {
            request.url.encodedPath == "/user/starred" && request.url.parameters["page"] == "1" -> json(
                "[${star("immich-app/immich", discussions = true)},${star("old/quiet", pushedAt = "2024-01-01T00:00:00Z")}," +
                    "${star("acme/draft")},${star("shelved/repo", archived = true)},${star("slow/project")}]",
                headers = mapOf(HttpHeaders.Link to """<https://api.github.com/user/starred?per_page=100&page=2>; rel="next", <https://api.github.com/user/starred?per_page=100&page=2>; rel="last""""),
            )
            request.url.encodedPath == "/user/starred" -> json("[${star("netbirdio/netbird")}]")
            else -> json("""{"data":{"r0":$immich,"r1":$draft,"r2":$longAgo,"r3":$netbird}}""")
        }
    }

    private suspend fun starredActivity(): List<FeedEvent> = starredApi().starredActivity("tok", since).value()

    @Test
    fun starred_repositories_give_their_latest_release_and_announcements() = runTest {
        val events = starredActivity()

        // Every page of stars is read: netbird is on the second.
        assertThat(events.map { it.repo.fullName to it.action }).containsExactly(
            "immich-app/immich" to FeedAction.Released("v3.3.0-rc.1", "v3.3.0-rc.1", prerelease = true),
            "immich-app/immich" to FeedAction.Announced(880, "Immich turns three"),
            "netbirdio/netbird" to FeedAction.Released("v0.80.0", null, prerelease = false),
        )
    }

    @Test
    fun only_starred_repositories_active_lately_are_asked_and_discussions_only_where_they_exist() = runTest {
        starredActivity()

        // One request of 100 repositories with releases and discussions timed out at GitHub (HTTP 504, 2026-10-01): the
        // stars are listed first, and only those pushed to since [since], not archived, are asked about.
        val asked = requests.single { it.url.encodedPath == "/graphql" }.text()
        assertThat(asked).contains("immich")
        assertThat(asked).contains("netbird")
        assertThat(asked).doesNotContain("quiet")
        assertThat(asked).doesNotContain("shelved")
        // Discussions are asked only of the one repository that has them enabled.
        assertThat(Regex("discussions\\(").findAll(asked).count()).isEqualTo(1)
    }

    @Test
    fun an_announcement_posted_with_its_release_shows_once_as_the_release() = runTest {
        val actions = starredActivity().filter { it.repo.name == "immich" }.map { it.action }

        // Discussion #900 "v3.3.0-rc.1" was posted the second the release was: the release row says it.
        assertThat(actions).doesNotContain(FeedAction.Announced(900, "v3.3.0-rc.1"))
        // Other discussion categories (feature requests, Q&A) never show.
        assertThat(actions.filterIsInstance<FeedAction.Announced>().map { it.number }).containsExactly(880)
    }

    @Test
    fun old_releases_and_drafts_from_starred_repositories_are_left_out() = runTest {
        val repos = starredActivity().map { it.repo.fullName }

        assertThat(repos).doesNotContain("slow/project")
        assertThat(repos).doesNotContain("acme/draft")
    }

    @Test
    fun a_starred_release_is_signed_by_its_author_or_else_by_the_repository_owner() = runTest {
        val events = starredActivity()

        assertThat(events.first { it.repo.name == "immich" }.actor).isEqualTo(ForgeUser("alextran", null, "https://avatars.example/alextran"))
        assertThat(events.first { it.repo.name == "netbird" }.actor).isEqualTo(ForgeUser("netbirdio", null, "https://avatars.example/netbirdio"))
        assertThat(events.first { it.repo.name == "immich" }.createdAt).isEqualTo(Instant.parse("2026-09-30T22:05:07Z"))
    }

    @Test
    fun a_release_published_by_a_bot_is_signed_by_the_repository_owner() = runTest {
        // Seen on the real API (2026-10-01): "github-actions[bot] released ..." names nobody worth reading.
        val byBot = """{"releases":{"nodes":[{"tagName":"v1","name":"v1","publishedAt":"2026-09-29T12:00:00Z","isPrerelease":false,"isDraft":false,"author":{"login":"github-actions[bot]","avatarUrl":"https://avatars.example/bot"}}]}}"""
        val api = api { request ->
            if (request.url.encodedPath == "/user/starred") json("[${star("acme/rocket")}]") else json("""{"data":{"r0":$byBot}}""")
        }

        assertThat(api.starredActivity("tok", since).value().single().actor).isEqualTo(ForgeUser("acme", null, "https://avatars.example/acme"))
    }

    @Test
    fun many_starred_repositories_are_asked_in_small_batches_all_at_once() = runTest {
        // 45 active stars: three requests of at most 20, together. One after another, each batch costs about 3 s.
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val all = kotlinx.coroutines.CompletableDeferred<Unit>()
        val batched = api { request ->
            if (request.url.encodedPath == "/user/starred") {
                json((1..45).joinToString(",", "[", "]") { star("o/repo$it") })
            } else {
                if (arrived.incrementAndGet() == 3) all.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000) { all.await() }
                json("""{"data":{}}""")
            }
        }

        assertThat(batched.starredActivity("tok", since)).isEqualTo(ForgeResult.Success(emptyList<FeedEvent>()))
        assertThat(requests.count { it.url.encodedPath == "/graphql" }).isEqualTo(3)
    }

    @Test
    fun one_batch_failing_keeps_the_others() = runTest {
        var batch = 0
        val flaky = api { request ->
            when {
                request.url.encodedPath == "/user/starred" -> json((1..21).joinToString(",", "[", "]") { star("o/repo$it") })
                "repo21" in request.text() -> json("{}", status = HttpStatusCode.BadGateway)
                else -> json("""{"data":{"r0":$netbird}}""").also { batch++ }
            }
        }

        val events = flaky.starredActivity("tok", since).value()

        assertThat(events.map { it.repo.fullName }).containsExactly("o/repo1")
    }

    @Test
    fun starred_repositories_that_cannot_be_listed_are_a_failure() = runTest {
        val result = api { json("{}", status = HttpStatusCode.Unauthorized) }.starredActivity("tok", since)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }
}
