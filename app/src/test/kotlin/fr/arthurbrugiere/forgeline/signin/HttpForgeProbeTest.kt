package fr.arthurbrugiere.forgeline.signin

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/** What each kind of server answered to the two version paths on 2026-10-04 (the bodies below are theirs). */
class HttpForgeProbeTest {
    private fun probe(v1: Pair<HttpStatusCode, String>?, v4: Pair<HttpStatusCode, String>?) = HttpForgeProbe(
        HttpClient(
            MockEngine { request ->
                val answer = (if (request.url.encodedPath == "/api/v1/version") v1 else v4) ?: throw IOException("unreachable")
                respond(answer.second, answer.first, headersOf(HttpHeaders.ContentType, if (answer.second.startsWith("{")) "application/json" else "text/html"))
            },
        ),
    )

    private val notFound = HttpStatusCode.NotFound to "Not found."

    @Test
    fun a_forgejo_or_gitea_tells_its_version_to_anyone() = runTest {
        // codeberg.org, and a self-hosted Forgejo 15.
        assertThat(probe(HttpStatusCode.OK to """{"version":"16.0.0-dev-753-6bcc6da0+gitea-1.22.0"}""", notFound).typeOf("codeberg.org")).isEqualTo(ForgeType.FORGEJO)
    }

    @Test
    fun a_gitlab_refuses_its_version_in_its_apis_own_words() = runTest {
        // gitlab.com and salsa.debian.org: /api/v1 leads to the sign-in page, /api/v4/version answers 401 as JSON.
        val signInPage = HttpStatusCode.OK to "<html><body>Sign in</body></html>"
        assertThat(probe(signInPage, HttpStatusCode.Unauthorized to """{"message":"401 Unauthorized"}""").typeOf("gitlab.example.org")).isEqualTo(ForgeType.GITLAB)
    }

    @Test
    fun a_gitlab_that_tells_its_version_is_one_too() = runTest {
        assertThat(probe(notFound, HttpStatusCode.OK to """{"version":"18.4.1","revision":"abc"}""").typeOf("gitlab.example.org")).isEqualTo(ForgeType.GITLAB)
    }

    @Test
    fun a_website_that_is_no_forge_is_none() = runTest {
        // example.com answers both with a page; github.com with 410 and 404.
        val page = HttpStatusCode.NotFound to "<!doctype html><html lang=en></html>"
        assertThat(probe(page, page).typeOf("example.com")).isNull()
        // A page behind a sign-in wall answers 401 too, but not as the API would.
        assertThat(probe(page, HttpStatusCode.Unauthorized to "<html>Sign in</html>").typeOf("intranet.example.org")).isNull()
    }

    @Test
    fun a_server_that_cannot_be_reached_is_none() = runTest {
        assertThat(probe(null, null).typeOf("nowhere.example.org")).isNull()
    }
}
