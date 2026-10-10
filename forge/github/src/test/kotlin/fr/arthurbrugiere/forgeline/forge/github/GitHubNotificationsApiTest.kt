package fr.arthurbrugiere.forgeline.forge.github

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import io.ktor.http.content.TextContent
import org.junit.Test
import java.time.Instant

/**
 * The fixture is synthetic (a public repo must not hold anyone's real notifications) but mirrors
 * the exact shape of a real /notifications response inspected on 2026-09-27.
 */
class GitHubNotificationsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val fixture = requireNotNull(javaClass.getResource("/github/notifications/threads.json")).readText()

    private fun MockRequestHandleScope.json(body: String, headers: Map<String, String> = emptyMap(), status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubNotificationsApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun parses_threads_with_their_subject_and_reason() = runTest {
        val sync = api { json(fixture, mapOf("Last-Modified" to "Sat, 27 Sep 2026 09:30:00 GMT", "X-Poll-Interval" to "60")) }
            .threads("tok", ifModifiedSince = null).value()

        assertThat(sync.lastModified).isEqualTo("Sat, 27 Sep 2026 09:30:00 GMT")
        assertThat(sync.pollIntervalSeconds).isEqualTo(60)
        val threads = sync.threads!!
        assertThat(threads).hasSize(5)
        assertThat(threads.first()).isEqualTo(
            NotificationThread(
                id = "1001",
                repo = RepoId("acme", "rocket"),
                title = "Launch fails on cold start",
                type = SubjectType.ISSUE,
                number = 42,
                reason = NotificationReason.MENTION,
                unread = true,
                updatedAt = Instant.parse("2026-09-27T09:30:00Z"),
                ownerAvatarUrl = "https://avatars.githubusercontent.com/u/1?v=4",
            ),
        )
        assertThat(threads.map { it.type }).containsExactly(
            SubjectType.ISSUE, SubjectType.PULL_REQUEST, SubjectType.CHECK_SUITE, SubjectType.RELEASE, SubjectType.PULL_REQUEST,
        ).inOrder()
        assertThat(threads.map { it.number }).containsExactly(42, 43, null, null, 7).inOrder()
        assertThat(threads.map { it.reason }).containsExactly(
            NotificationReason.MENTION, NotificationReason.REVIEW_REQUESTED, NotificationReason.CI_ACTIVITY,
            NotificationReason.SUBSCRIBED, NotificationReason.MANUAL,
        ).inOrder()
    }

    @Test
    fun requests_read_and_unread_threads_with_the_token() = runTest {
        api { json("[]") }.threads("tok", ifModifiedSince = null)

        val request = requests.single()
        assertThat(request.url.encodedPath).isEqualTo("/notifications")
        assertThat(request.url.parameters["all"]).isEqualTo("true")
        assertThat(request.url.parameters["per_page"]).isEqualTo("50")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat(request.headers[HttpHeaders.IfModifiedSince]).isNull()
    }

    @Test
    fun an_unchanged_inbox_answers_not_modified_for_free() = runTest {
        val result = api { respond("", HttpStatusCode.NotModified, headersOf("X-Poll-Interval", "60")) }
            .threads("tok", ifModifiedSince = "Sat, 27 Sep 2026 09:30:00 GMT")

        // The marker counts the checks answered "not modified" since the last list.
        assertThat(result).isEqualTo(ForgeResult.Success(NotificationsSync(null, "Sat, 27 Sep 2026 09:30:00 GMT;checks=1", 60)))
        assertThat(requests.single().headers[HttpHeaders.IfModifiedSince]).isEqualTo("Sat, 27 Sep 2026 09:30:00 GMT")
    }

    @Test
    fun marking_unread_asks_github_nothing_since_it_has_no_such_call() = runTest {
        // GitHub's API marks a thread read or done, never unread: the Inbox keeps it unread by itself.
        assertThat(api { json("{}") }.markUnread("tok", "1")).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests).isEmpty()
    }

    @Test
    fun one_check_in_four_lists_the_threads_even_with_nothing_new() = runTest {
        // Regression: GitHub answers "not modified" as long as no thread has new activity (its Last-Modified is the
        // newest thread's date, checked 2026-10-10). Threads read or marked done on github.com stayed unread here.
        val api = api { request ->
            if (request.headers[HttpHeaders.IfModifiedSince] != null) {
                respond("", HttpStatusCode.NotModified)
            } else {
                json("[]", mapOf(HttpHeaders.LastModified to "Sat, 27 Sep 2026 09:30:00 GMT"))
            }
        }
        var marker: String? = "Sat, 27 Sep 2026 09:30:00 GMT"

        val listed = (1..4).map { api.threads("tok", marker).value().also { marker = it.lastModified }.threads != null }

        assertThat(listed).containsExactly(false, false, false, true).inOrder()
        // The date alone goes to GitHub, never the count.
        assertThat(requests.mapNotNull { it.headers[HttpHeaders.IfModifiedSince] }.distinct()).containsExactly("Sat, 27 Sep 2026 09:30:00 GMT")
        // And the count starts again.
        assertThat(marker).isEqualTo("Sat, 27 Sep 2026 09:30:00 GMT")
    }

    @Test
    fun follows_pages_up_to_the_limit() = runTest {
        val api = api { request ->
            val page = request.url.parameters["page"]?.toInt() ?: 1
            json(
                fixture,
                if (page < 5) mapOf("Link" to "<https://api.github.com/notifications?all=true&per_page=50&page=${page + 1}>; rel=\"next\"") else emptyMap(),
            )
        }

        val threads = api.threads("tok", ifModifiedSince = null, maxPages = 2).value().threads!!

        assertThat(requests.map { it.url.parameters["page"] }).containsExactly("1", "2").inOrder()
        assertThat(threads).hasSize(10)
    }

    @Test
    fun thread_actions_use_the_documented_endpoints() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }

        assertThat(api.markRead("tok", "1001")).isEqualTo(ForgeResult.Success(Unit))
        api.markDone("tok", "1001")
        api.unsubscribe("tok", "1001")

        assertThat(requests.map { it.method to it.url.encodedPath }).containsExactly(
            HttpMethod.Patch to "/notifications/threads/1001",
            HttpMethod.Delete to "/notifications/threads/1001",
            HttpMethod.Delete to "/notifications/threads/1001/subscription",
        ).inOrder()
    }

    @Test
    fun a_token_without_the_notifications_scope_is_reported() = runTest {
        val result = api { json("""{"message":"Missing the 'notifications' scope."}""", status = HttpStatusCode.Forbidden) }
            .threads("tok", ifModifiedSince = null)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Missing the 'notifications' scope.")))
    }

    @Test
    fun subject_states_come_from_one_graphql_request() = runTest {
        // A real answer captured on 2026-09-29, with a number that doesn't exist (answered as an error).
        val paperclip = RepoId("paperclipai", "paperclip")
        val refs = listOf(14127, 14129, 1, 14502, 99999999).map { IssueRef(paperclip, it) }
        val answer = requireNotNull(javaClass.getResource("/github/notifications/subject_states.json")).readText()

        val states = api { json(answer) }.subjectStates("tok", refs).value()

        assertThat(states).containsExactly(
            IssueRef(paperclip, 14127), SubjectState.OPEN,
            IssueRef(paperclip, 14129), SubjectState.OPEN,
            IssueRef(paperclip, 1), SubjectState.CLOSED,
            IssueRef(paperclip, 14502), SubjectState.MERGED,
        )
        val request = requests.single()
        assertThat(request.url.encodedPath).isEqualTo("/graphql")
        val body = (request.body as TextContent).text
        // Owners and names travel as variables, never pasted into the query.
        assertThat(body).contains("\"o0\":\"paperclipai\"")
        assertThat(body).contains("i14502: issueOrPullRequest(number: 14502)")
    }

    @Test
    fun drafts_and_issues_closed_as_not_planned_are_told_apart() = runTest {
        val repo = RepoId("o", "r")
        val answer = """{"data":{"r0":{"i1":{"__typename":"PullRequest","state":"OPEN","isDraft":true},""" +
            """"i2":{"__typename":"Issue","state":"CLOSED","stateReason":"NOT_PLANNED"},""" +
            """"i3":{"__typename":"PullRequest","state":"CLOSED","isDraft":false}}}}"""

        val states = api { json(answer) }.subjectStates("tok", listOf(1, 2, 3).map { IssueRef(repo, it) }).value()

        assertThat(states.values).containsExactly(SubjectState.DRAFT, SubjectState.NOT_PLANNED, SubjectState.CLOSED).inOrder()
    }

    @Test
    fun many_subjects_are_asked_for_in_chunks() = runTest {
        val refs = (1..150).map { IssueRef(RepoId("o", "r"), it) }

        api { json("""{"data":{}}""") }.subjectStates("tok", refs)

        assertThat(requests).hasSize(2)
    }

    @Test
    fun the_pages_after_the_first_are_asked_all_at_once() = runTest {
        // Regression: each page waited for the one before, a round trip each to a far forge.
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        var most = 0
        val bothAsked = CompletableDeferred<Unit>()
        val link = """<https://api.github.com/notifications?page=2>; rel="next", <https://api.github.com/notifications?page=3>; rel="last""""
        val sync = api { request ->
            val page = request.url.parameters["page"]
            if (page == "1") return@api json(fixture, mapOf("Link" to link))
            // The two pages arrive on two threads: counted one at a time, or the higher count could be overwritten.
            synchronized(inFlight) {
                most = maxOf(most, inFlight.incrementAndGet())
                if (most >= 2) bothAsked.complete(Unit)
            }
            // Each later page answers only once the other is asked too: one after another would never end.
            withTimeout(5_000) { bothAsked.await() }
            inFlight.decrementAndGet()
            json("[]")
        }.threads("tok", ifModifiedSince = "Sat, 27 Sep 2026 09:00:00 GMT", maxPages = 3).value()

        assertThat(most).isEqualTo(2)
        assertThat(sync.threads).hasSize(5)
        assertThat(requests.map { it.url.parameters["page"] }).containsExactly("1", "2", "3")
        // Only the first page is conditional.
        assertThat(requests.filter { it.url.parameters["page"] != "1" }.map { it.headers[HttpHeaders.IfModifiedSince] }).containsExactly(null, null)
    }

    @Test
    fun no_more_pages_are_asked_than_allowed() = runTest {
        val link = """<https://api.github.com/notifications?page=2>; rel="next", <https://api.github.com/notifications?page=9>; rel="last""""
        api { request -> if (request.url.parameters["page"] == "1") json(fixture, mapOf("Link" to link)) else json("[]") }
            .threads("tok", ifModifiedSince = null, maxPages = 3)

        assertThat(requests.map { it.url.parameters["page"] }).containsExactly("1", "2", "3")
    }

    @Test
    fun state_lookups_of_more_than_a_hundred_subjects_are_asked_together() = runTest {
        // Regression: each batch of 100 waited for the one before.
        val subjects = (1..150).map { IssueRef(RepoId("acme", "rocket"), it) }
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val both = CompletableDeferred<Unit>()
        val states = api {
            if (arrived.incrementAndGet() == 2) both.complete(Unit)
            withTimeout(5_000) { both.await() }
            json("""{"data":{}}""")
        }.subjectStates("tok", subjects)

        assertThat(states).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests.count { it.url.encodedPath.endsWith("/graphql") }).isEqualTo(2)
    }

    @Test
    fun a_thread_says_when_it_was_last_read() = runTest {
        val threads = api { json(fixture) }.threads("tok", ifModifiedSince = null).value().threads!!

        // Never read: nothing to go by. Read before: what came after is what's new.
        assertThat(threads[0].lastReadAt).isNull()
        assertThat(threads[3].lastReadAt).isEqualTo(java.time.Instant.parse("2026-09-26T18:00:00Z"))
    }
}
