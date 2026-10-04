package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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
import org.junit.Test

class GitLabAuthApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(clientId: String = "client-123", handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") }) =
        GitLabAuthApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }), ForgeInstance.GitLab, clientId)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun builds_authorization_url_with_pkce_and_scopes() {
        val authApi = api(clientId = "test-client-id")
        val url = authApi.authorizationUrl("http://localhost:8080/callback", "random-state", "code-challenge-s256")

        assertThat(url).contains("https://gitlab.com/oauth/authorize?")
        assertThat(url).contains("client_id=test-client-id")
        assertThat(url).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcallback")
        assertThat(url).contains("response_type=code")
        assertThat(url).contains("state=random-state")
        assertThat(url).contains("code_challenge=code-challenge-s256")
        assertThat(url).contains("code_challenge_method=S256")
        assertThat(url).contains("scope=api+read_user+openid")
    }

    @Test
    fun exchanges_code_for_tokens() = runTest {
        val json = """
            {
                "access_token": "glpat-secret",
                "refresh_token": "glprt-secret",
                "expires_in": 7200
            }
        """.trimIndent()

        val tokens = api { json(json) }.exchangeCode("auth-code", "http://localhost:8080/callback", "verifier-123").value()
        assertThat(tokens.accessToken).isEqualTo("glpat-secret")
        assertThat(tokens.refreshToken).isEqualTo("glprt-secret")
        assertThat(tokens.expiresInSeconds).isEqualTo(7200L)
        assertThat(requests.single().method).isEqualTo(HttpMethod.Post)
        assertThat(requests.single().url.encodedPath).isEqualTo("/oauth/token")
    }

    @Test
    fun fetches_authenticated_user() = runTest {
        val json = """
            {
                "id": 42,
                "username": "roiarthurb",
                "name": "Arthur",
                "avatar_url": "https://gitlab.com/user.png"
            }
        """.trimIndent()

        val user = api { json(json) }.fetchAuthenticatedUser("glpat-secret").value()
        assertThat(user.login).isEqualTo("roiarthurb")
        assertThat(user.name).isEqualTo("Arthur")
        assertThat(user.avatarUrl).isEqualTo("https://gitlab.com/user.png")
        assertThat(requests.single().url.encodedPath).isEqualTo("/api/v4/user")
    }
}
