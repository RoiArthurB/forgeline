package fr.arthurbrugiere.forgeline.signin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI

class LoopbackRedirectTest {
    private suspend fun get(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        val status = connection.responseCode
        status to (if (status == 200) connection.inputStream.bufferedReader().readText() else "")
    }

    @Test
    fun the_forge_redirect_brings_back_its_code_and_sees_the_page() = runBlocking<Unit> {
        LoopbackRedirect("/oauth/codeberg").use { redirect ->
            assertThat(redirect.redirectUri).matches("""http://127\.0\.0\.1:\d+/oauth/codeberg""")
            val params = async { redirect.await("<p>Signed in</p>") }

            val (status, page) = get("${redirect.redirectUri}?code=abc%2F1&state=s-1")

            assertThat(status).isEqualTo(200)
            assertThat(page).isEqualTo("<p>Signed in</p>")
            assertThat(withTimeout(5_000) { params.await() }).containsExactly("code", "abc/1", "state", "s-1")
        }
    }

    @Test
    fun a_stray_request_is_turned_away_and_the_wait_goes_on() = runBlocking<Unit> {
        LoopbackRedirect("/oauth/codeberg").use { redirect ->
            val params = async { redirect.await("ok") }
            val port = URI(redirect.redirectUri).port

            assertThat(get("http://127.0.0.1:$port/favicon.ico").first).isEqualTo(404)
            get("${redirect.redirectUri}?code=c&state=s")

            assertThat(withTimeout(5_000) { params.await() }["code"]).isEqualTo("c")
        }
    }

    @Test(expected = ConnectException::class)
    fun cancelling_the_wait_stops_listening() = runBlocking<Unit> {
        val redirect = LoopbackRedirect("/oauth/codeberg")
        val waiting = async { redirect.await("ok") }
        // Let the wait begin before taking it back.
        yield()
        waiting.cancel()
        waiting.join()

        get(redirect.redirectUri)
    }
}
