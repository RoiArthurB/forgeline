package fr.arthurbrugiere.forgeline.forge.common

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What every forge's client shares. */
class ForgeHttpTest {
    @Serializable
    private data class Named(val name: String)

    @Test
    fun every_call_says_who_is_calling_and_a_failure_is_an_answer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = forgeHttpClient(MockEngine { requests += it; respond("nope", HttpStatusCode.NotFound) })

        assertThat(client.get("https://forge.example/api").status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(requests.single().headers[HttpHeaders.UserAgent]).isEqualTo(USER_AGENT)
    }

    @Test
    fun fields_the_app_doesnt_know_are_skipped() = runTest {
        val client = forgeHttpClient(
            MockEngine { respond("""{"name":"forgeline","stars":3}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) },
        )

        assertThat(client.get("https://forge.example/api").body<Named>()).isEqualTo(Named("forgeline"))
    }

    @Test
    fun no_network_is_a_network_error_and_an_unreadable_answer_is_unreadable() = runTest {
        assertThat(forgeCall<Unit> { throw IOException("no route to host") }).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(forgeCall<Unit> { throw SerializationException("a web page") }).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
        assertThat(forgeCall<Unit> { throw java.time.format.DateTimeParseException("bad", "x", 0) })
            .isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_bug_and_a_cancellation_go_through() = runTest {
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { forgeCall<Unit> { error("a bug") } } }
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { forgeCall<Unit> { throw CancellationException() } } }
    }

    @Test
    fun the_next_and_last_pages_are_read_from_the_link_header() = runTest {
        val link = """<https://forge.example/api?per_page=50&page=2>; rel="next", <https://forge.example/api?page=7&per_page=50>; rel="last""""
        val client = forgeHttpClient(MockEngine { respond("[]", headers = headersOf(HttpHeaders.Link, link)) })
        val bare = forgeHttpClient(MockEngine { respond("[]") })

        val paged = client.get("https://forge.example/api")
        assertThat(paged.nextLinkedPage()).isEqualTo(2)
        assertThat(paged.lastLinkedPage()).isEqualTo(7)
        assertThat(bare.get("https://forge.example/api").nextLinkedPage()).isNull()
    }

    @Test
    fun a_stream_is_written_whole() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val into = ByteArrayOutputStream()

        ByteReadChannel(bytes).writeTo(into)

        assertThat(into.toByteArray()).isEqualTo(bytes)
    }
}
