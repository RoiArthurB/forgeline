package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.DeviceCode
import fr.arthurbrugiere.forgeline.core.forge.DeviceTokenPoll
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

class GitHubAuthApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun api(
        clientId: String = "client-123",
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = GitHubAuthApi(
        httpClient = gitHubHttpClient(MockEngine { request -> requests += request; handler(request) }),
        clientId = clientId,
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, headers: Map<String, String> = emptyMap()) =
        respond(
            content = body,
            status = status,
            headers = headersOf(
                *(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray(),
            ),
        )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/github/$name")) { "missing fixture $name" }.readText()

    private suspend fun HttpRequestData.form() = body.toByteArray().decodeToString().parseUrlEncodedParameters()

    @Test
    fun device_flow_needs_a_client_id() {
        assertThat(api(clientId = "") { error("no call expected") }.supportsDeviceFlow).isFalse()
        assertThat(api { error("no call expected") }.supportsDeviceFlow).isTrue()
    }

    @Test
    fun requests_a_device_code_with_the_forgeline_scopes() = runTest {
        val result = api { json(fixture("device_code.json")) }.requestDeviceCode()

        assertThat(result).isEqualTo(
            ForgeResult.Success(
                DeviceCode(
                    deviceCode = "3584d83530557fdd1f46af8289938c8ef79f9dc5",
                    userCode = "WDJB-MJHT",
                    verificationUri = "https://github.com/login/device",
                    expiresInSeconds = 900,
                    intervalSeconds = 5,
                ),
            ),
        )
        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://github.com/login/device/code")
        assertThat(request.headers[HttpHeaders.Accept]).contains("application/json")
        val form = request.form()
        assertThat(form["client_id"]).isEqualTo("client-123")
        // Forms encode spaces as "+".
        assertThat(form["scope"]!!.split(" ", "+")).containsExactly("notifications", "read:user", "user:follow", "public_repo")
    }

    @Test
    fun polling_sends_the_device_grant() = runTest {
        api { json("""{"error":"authorization_pending"}""") }.pollDeviceToken("dev-code")

        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("https://github.com/login/oauth/access_token")
        val form = request.form()
        assertThat(form["client_id"]).isEqualTo("client-123")
        assertThat(form["device_code"]).isEqualTo("dev-code")
        assertThat(form["grant_type"]).isEqualTo("urn:ietf:params:oauth:grant-type:device_code")
    }

    @Test
    fun polling_maps_every_documented_outcome() = runTest {
        suspend fun poll(body: String) = api { json(body) }.pollDeviceToken("dev-code")

        assertThat(poll("""{"access_token":"gho_abc","token_type":"bearer","scope":"notifications"}"""))
            .isEqualTo(ForgeResult.Success(DeviceTokenPoll.Authorized("gho_abc")))
        assertThat(poll("""{"error":"authorization_pending"}"""))
            .isEqualTo(ForgeResult.Success(DeviceTokenPoll.Pending))
        assertThat(poll("""{"error":"slow_down","interval":10}"""))
            .isEqualTo(ForgeResult.Success(DeviceTokenPoll.SlowDown(10)))
        assertThat(poll("""{"error":"expired_token"}"""))
            .isEqualTo(ForgeResult.Success(DeviceTokenPoll.Expired))
        assertThat(poll("""{"error":"access_denied"}"""))
            .isEqualTo(ForgeResult.Success(DeviceTokenPoll.Denied))
    }

    @Test
    fun unknown_poll_errors_are_reported() = runTest {
        val result = api { json("""{"error":"incorrect_client_credentials","error_description":"Bad client"}""") }
            .pollDeviceToken("dev-code")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(200, "Bad client")))
    }

    @Test
    fun fetches_the_authenticated_user_with_the_token() = runTest {
        val result = api { json(fixture("user.json")) }.fetchAuthenticatedUser("ghp_token")

        assertThat(result).isEqualTo(
            ForgeResult.Success(
                ForgeUser(
                    login = "octocat",
                    name = "The Octocat",
                    avatarUrl = "https://avatars.githubusercontent.com/u/583231?v=4",
                ),
            ),
        )
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/user")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer ghp_token")
        assertThat(request.headers["X-GitHub-Api-Version"]).isEqualTo("2022-11-28")
    }

    @Test
    fun a_rejected_token_is_unauthorized() = runTest {
        val result = api { json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
            .fetchAuthenticatedUser("bad")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun an_exhausted_rate_limit_is_reported_with_its_reset_time() = runTest {
        val result = api {
            json(
                """{"message":"API rate limit exceeded"}""",
                HttpStatusCode.Forbidden,
                mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1790000000"),
            )
        }.fetchAuthenticatedUser("tok")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.RateLimited(1790000000)))
    }

    @Test
    fun other_http_errors_keep_status_and_message() = runTest {
        val result = api { json("""{"message":"Server Error"}""", HttpStatusCode.InternalServerError) }
            .fetchAuthenticatedUser("tok")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(500, "Server Error")))
    }

    @Test
    fun io_failures_are_network_errors() = runTest {
        val result = api { throw IOException("offline") }.fetchAuthenticatedUser("tok")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }

    @Test
    fun personal_access_token_url_prefills_the_needed_scopes() {
        val url = api { error("no call expected") }.personalAccessTokenUrl

        assertThat(url).startsWith("https://github.com/settings/tokens/new?")
        assertThat(url).contains("scopes=notifications,read:user,user:follow,public_repo")
        assertThat(url).contains("description=Forgeline")
    }
}
