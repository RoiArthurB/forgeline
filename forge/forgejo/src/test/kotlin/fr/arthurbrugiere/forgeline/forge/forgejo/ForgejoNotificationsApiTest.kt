package fr.arthurbrugiere.forgeline.forge.forgejo

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * notifications.json follows the NotificationThread schema of Codeberg's published API description (2026-09-29):
 * listing notifications needs an account, so there is no captured answer.
 */
class ForgejoNotificationsApiTest {
    private val codeberg = Codeberg()
    private var newCount = 0
    private val reviewRequested = """[{"number":14602,"title":"Fix the webhook retries","state":"open","created_at":"2026-09-28T10:00:00+02:00","repository":{"owner":"forgejo","name":"forgejo"}}]"""

    private val api = with(codeberg) {
        ForgejoNotificationsApi(
            client { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/notifications/new") -> json("""{"new":$newCount}""")
                    path.endsWith("/notifications") -> json(fixture("notifications.json"))
                    path.endsWith("/issues/search") -> json(if (request.url.parameters["review_requested"] == "true") reviewRequested else "[]")
                    path.endsWith("/notifications/threads/902") && request.method == HttpMethod.Get -> json(fixture("notifications.json").let { all ->
                        all.substring(all.indexOf("{\"id\": 902"), all.indexOf(",\n  {\"id\": 903"))
                    })
                    path == "/api/v1/user" -> json(fixture("user.json"))
                    else -> status(io.ktor.http.HttpStatusCode.NoContent)
                }
            },
            ForgeInstance.Codeberg,
        )
    }

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private fun paths() = codeberg.requests.map(HttpRequestData::url).map { it.encodedPath }

    @Test
    fun threads_carry_their_forge_kind_number_and_state() = runTest {
        val threads = api.threads("t", ifModifiedSince = null).value().threads!!

        assertThat(threads.map { it.id }).containsExactly("901", "902", "903").inOrder()
        val pull = threads[0]
        assertThat(pull.repo.forge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(pull.type).isEqualTo(SubjectType.PULL_REQUEST)
        assertThat(pull.number).isEqualTo(14602)
        assertThat(pull.state).isEqualTo(SubjectState.OPEN)
        assertThat(threads[1].state).isEqualTo(SubjectState.CLOSED)
        assertThat(threads[2].number).isNull()
        assertThat(threads[2].type).isEqualTo(SubjectType.OTHER)
    }

    @Test
    fun needs_you_is_rebuilt_from_searches() = runTest {
        val threads = api.threads("t", ifModifiedSince = null).value().threads!!

        // Forgejo sends no reason: the review request comes from the search, the rest are subscriptions.
        assertThat(threads[0].reason).isEqualTo(NotificationReason.REVIEW_REQUESTED)
        assertThat(threads[0].needsYou).isTrue()
        assertThat(threads[1].reason).isEqualTo(NotificationReason.SUBSCRIBED)
        assertThat(paths().count { it.endsWith("/issues/search") }).isEqualTo(4)
    }

    @Test
    fun with_nothing_new_the_list_isnt_fetched_except_every_fourth_check() = runTest {
        val first = api.threads("t", ifModifiedSince = null).value()
        codeberg.requests.clear()

        var marker = first.lastModified
        repeat(3) {
            val sync: NotificationsSync = api.threads("t", marker).value()
            assertThat(sync.threads).isNull()
            marker = sync.lastModified
        }
        assertThat(paths().none { it.endsWith("/notifications") }).isTrue()

        // The fourth check fetches again, to catch threads read on the web.
        assertThat(api.threads("t", marker).value().threads).isNotNull()
    }

    @Test
    fun something_new_fetches_the_list_at_once() = runTest {
        val first = api.threads("t", ifModifiedSince = null).value()
        newCount = 2

        assertThat(api.threads("t", first.lastModified).value().threads).hasSize(3)
    }

    @Test
    fun done_is_read_since_forgejo_has_no_done() = runTest {
        assertThat(api.supportsDone).isFalse()

        api.markDone("t", "901")

        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Patch)
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/notifications/threads/901")
        assertThat(request.url.parameters["to-status"]).isEqualTo("read")
    }

    @Test
    fun unsubscribing_leaves_the_threads_issue() = runTest {
        api.unsubscribe("t", "902")

        assertThat(codeberg.requests.last().method).isEqualTo(HttpMethod.Delete)
        assertThat(codeberg.requests.last().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/issues/14601/subscriptions/earl-warren")
    }

    @Test
    fun reading_a_thread_marks_it_read_and_done_does_the_same() = runTest {
        // Forgejo has no "done": the Inbox hides a done thread itself (supportsDone is false).
        assertThat(api.markRead("t", "902")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.markDone("t", "903")).isEqualTo(ForgeResult.Success(Unit))

        assertThat(api.supportsDone).isFalse()
        assertThat(codeberg.requests.map { Triple(it.method, it.url.encodedPath, it.url.parameters["to-status"]) }).containsExactly(
            Triple(HttpMethod.Patch, "/api/v1/notifications/threads/902", "read"),
            Triple(HttpMethod.Patch, "/api/v1/notifications/threads/903", "read"),
        ).inOrder()
    }

    @Test
    fun the_pages_after_the_first_are_asked_all_at_once() = runTest {
        // Regression: each page waited for the one before, a round trip each to a far forge.
        val bothAsked = CompletableDeferred<Unit>()
        val later = mutableSetOf<String>()
        val paged = with(codeberg) {
            ForgejoNotificationsApi(
                client { request ->
                    val page = request.url.parameters["page"]
                    when {
                        request.url.encodedPath.endsWith("/issues/search") -> json("[]")
                        page == "1" -> json(fixture("notifications.json"), headers = mapOf("X-Total-Count" to "120"))
                        else -> {
                            later += page.orEmpty()
                            if (later.size == 2) bothAsked.complete(Unit)
                            withTimeout(5_000) { bothAsked.await() }
                            json("[]")
                        }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        paged.threads("t", ifModifiedSince = null, maxPages = 3)

        // 120 threads, 50 a page: pages 2 and 3, together.
        assertThat(later).containsExactly("2", "3")
    }

    @Test
    fun a_search_that_times_out_never_takes_the_inbox_down() = runTest {
        // Regression: Codeberg's issue searches take 7 to 11 s (measured 2026-10-01). One of them running past the read
        // timeout failed the whole sync, so the Codeberg Inbox sometimes couldn't refresh at all.
        val flaky = with(codeberg) {
            ForgejoNotificationsApi(
                client { request ->
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("/notifications") -> json(fixture("notifications.json"))
                        request.url.parameters["review_requested"] == "true" -> throw java.net.SocketTimeoutException("timeout")
                        path.endsWith("/issues/search") -> json("[]")
                        else -> status(io.ktor.http.HttpStatusCode.NoContent)
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        val sync = flaky.threads("t", ifModifiedSince = null, maxPages = 1)

        assertThat(sync).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(sync.value().threads).isNotEmpty()
    }
}
