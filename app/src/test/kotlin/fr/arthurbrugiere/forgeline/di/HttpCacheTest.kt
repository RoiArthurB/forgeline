package fr.arthurbrugiere.forgeline.di

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import org.junit.After
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The forges' answers are kept and asked again with what is known of them (`If-None-Match`): a 304 is all that comes
 * back when nothing changed, and GitHub doesn't count it against the rate limit. Run against a real local server, so
 * OkHttp's own cache is what answers.
 */
class HttpCacheTest {
    private val folder = kotlin.io.path.createTempDirectory("http-cache").toFile()

    /** Each request's method, path and the headers the cache adds or the app sets. */
    private val seen = CopyOnWriteArrayList<String>()
    private var etag = "\"v1\""
    private var body = "first"

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val asked = exchange.requestHeaders
            seen += "${exchange.requestMethod} ${exchange.requestURI.path} if-none-match=${asked.getFirst("If-None-Match")}"
            exchange.responseHeaders.add("ETag", etag)
            // What GitHub sends signed in: fresh for a minute, kept by the phone only.
            exchange.responseHeaders.add("Cache-Control", "private, max-age=60")
            if (asked.getFirst("If-None-Match") == etag) {
                exchange.sendResponseHeaders(304, -1)
            } else {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"
    private val client = HttpClient(forgeEngine(Cache(folder, 1_000_000)))

    @After
    fun stop() {
        client.close()
        server.stop(0)
        folder.deleteRecursively()
    }

    @Test
    fun an_answer_asked_again_is_revalidated_and_read_from_the_phone_when_unchanged() = runBlocking<Unit> {
        val first = client.get("$base/repos/octo/repo")
        val second = client.get("$base/repos/octo/repo")

        assertThat(first.bodyAsText()).isEqualTo("first")
        // The forge answered 304; the body is the one kept.
        assertThat(second.status).isEqualTo(HttpStatusCode.OK)
        assertThat(second.bodyAsText()).isEqualTo("first")
        assertThat(seen).containsExactly(
            "GET /repos/octo/repo if-none-match=null",
            "GET /repos/octo/repo if-none-match=\"v1\"",
        ).inOrder()
    }

    @Test
    fun a_fresh_answer_is_never_served_without_asking_so_a_change_made_from_the_app_shows_at_once() = runBlocking<Unit> {
        client.get("$base/repos/octo/repo/issues").bodyAsText()
        etag = "\"v2\""
        body = "after the change"

        // Within GitHub's minute of freshness, yet asked: what was just closed from the app is listed closed.
        assertThat(client.get("$base/repos/octo/repo/issues").bodyAsText()).isEqualTo("after the change")
        assertThat(seen).hasSize(2)
    }

    @Test
    fun a_request_with_its_own_conditions_is_left_to_the_app() = runBlocking<Unit> {
        client.get("$base/notifications").bodyAsText()

        // The Inbox asks "anything since?" itself, and must hear the 304.
        val polled = client.get("$base/notifications") { header(HttpHeaders.IfNoneMatch, "\"v1\"") }

        assertThat(polled.status).isEqualTo(HttpStatusCode.NotModified)
    }

    @Test
    fun a_part_of_a_log_is_neither_kept_nor_answered_from_what_was_kept() = runBlocking<Unit> {
        client.get("$base/jobs/1/logs") { header(HttpHeaders.Range, "bytes=0-9") }.bodyAsText()
        client.get("$base/jobs/1/logs") { header(HttpHeaders.Range, "bytes=0-9") }.bodyAsText()

        assertThat(seen).containsExactly(
            "GET /jobs/1/logs if-none-match=null",
            "GET /jobs/1/logs if-none-match=null",
        )
    }

    @Test
    fun what_isnt_a_read_goes_as_it_is() = runBlocking<Unit> {
        client.post("$base/repos/octo/repo/forks").bodyAsText()
        client.post("$base/repos/octo/repo/forks").bodyAsText()

        assertThat(seen).containsExactly(
            "POST /repos/octo/repo/forks if-none-match=null",
            "POST /repos/octo/repo/forks if-none-match=null",
        )
    }
}
