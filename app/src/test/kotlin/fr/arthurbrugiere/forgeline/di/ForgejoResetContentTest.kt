package fr.arthurbrugiere.forgeline.di

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.forgejo.ForgejoNotificationsApi
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The engine the app really runs on, against a server that answers the way Forgejo does. The clients' own tests run
 * on a mock engine, which reads anything: what the real one refuses only shows here.
 */
@RunWith(RobolectricTestRunner::class)
class ForgejoResetContentTest {
    private val server = ServerSocket(0)
    private val requests = mutableListOf<String>()

    /** Answers every connection with [answer], once its request has been read. */
    private fun serve(answer: String) = thread(isDaemon = true) {
        runCatching {
            while (true) {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    requests += reader.readLine().orEmpty()
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    socket.getOutputStream().apply { write(answer.toByteArray()) }.flush()
                }
            }
        }
    }

    /** A Codeberg client whose requests land on the local server instead. */
    private fun api() = ForgejoNotificationsApi(
        forgejoHttpClient(forgeEngine()).apply {
            plugin(HttpSend).intercept { request ->
                request.url.protocol = URLProtocol.HTTP
                request.url.host = "127.0.0.1"
                request.url.port = server.localPort
                execute(request)
            }
        },
        ForgeInstance.Codeberg,
    )

    @After
    fun stop() = server.close()

    @Test
    fun a_thread_is_read_when_the_forge_answers_205_with_a_body() = runBlocking {
        // Regression: this is Forgejo's answer to a status change, and OkHttp refuses it. Every "done" on Codeberg
        // failed, though the forge had done it (seen on a phone, 2026-10-04).
        val body = """{"id":901,"unread":false}"""
        serve("HTTP/1.1 205 Reset Content\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")

        assertThat(api().markDone("t", "901")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(requests.single()).startsWith("PATCH /api/v1/notifications/threads/901?to-status=read")
    }

    @Test
    fun a_forge_that_refuses_is_still_a_failure() = runBlocking {
        val body = """{"message":"token does not have the required scope"}"""
        serve("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")

        assertThat(api().markDone("t", "901")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "token does not have the required scope")))
    }
}
