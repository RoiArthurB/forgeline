package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForgejoAuthApiTest {
    private val codeberg = Codeberg()
    private val tokenAnswer = """{"access_token":"at","token_type":"bearer","expires_in":3600,"refresh_token":"rt"}"""

    private fun api(clientId: String = "client-123") = with(codeberg) {
        ForgejoAuthApi(client { if (it.url.encodedPath.endsWith("/user")) json(fixture("user.json")) else json(tokenAnswer) }, ForgeInstance.Codeberg, clientId)
    }

    private fun form(index: Int): Map<String, String> {
        val body = codeberg.requests[index].body as OutgoingContent.ByteArrayContent
        return parseQueryString(body.bytes().decodeToString()).entries().associate { it.key to it.value.single() }
    }

    @Test
    fun browser_sign_in_needs_a_client_id_and_there_is_no_device_flow() {
        assertThat(api().supportsBrowserSignIn).isTrue()
        assertThat(api(clientId = "").supportsBrowserSignIn).isFalse()
        assertThat(api().supportsDeviceFlow).isFalse()
        assertThat(api().personalAccessTokenUrl).isEqualTo("https://codeberg.org/user/settings/applications")
    }

    @Test
    fun the_authorization_page_asks_for_a_code_with_pkce() {
        val url = Url(api().authorizationUrl("http://127.0.0.1:43123/oauth/codeberg", "state-1", "challenge-1"))

        assertThat("${url.protocol.name}://${url.host}${url.encodedPath}").isEqualTo("https://codeberg.org/login/oauth/authorize")
        assertThat(url.parameters["client_id"]).isEqualTo("client-123")
        assertThat(url.parameters["redirect_uri"]).isEqualTo("http://127.0.0.1:43123/oauth/codeberg")
        assertThat(url.parameters["response_type"]).isEqualTo("code")
        assertThat(url.parameters["state"]).isEqualTo("state-1")
        assertThat(url.parameters["code_challenge"]).isEqualTo("challenge-1")
        assertThat(url.parameters["code_challenge_method"]).isEqualTo("S256")
    }

    @Test
    fun the_code_is_exchanged_with_its_verifier_and_the_token_refreshes() = runTest {
        val api = api()

        val tokens = api.exchangeCode("code-1", "http://127.0.0.1:43123/oauth/codeberg", "verifier-1")
        api.refresh("rt")

        assertThat(tokens).isEqualTo(ForgeResult.Success(OAuthTokens("at", "rt", 3600)))
        assertThat(codeberg.requests[0].url.toString()).isEqualTo("https://codeberg.org/login/oauth/access_token")
        assertThat(form(0)).containsExactly(
            "client_id", "client-123", "grant_type", "authorization_code", "code", "code-1",
            "redirect_uri", "http://127.0.0.1:43123/oauth/codeberg", "code_verifier", "verifier-1",
        )
        assertThat(form(1)).containsExactly("client_id", "client-123", "grant_type", "refresh_token", "refresh_token", "rt")
    }

    @Test
    fun the_signed_in_person_is_read_with_the_token() = runTest {
        val user = (api().fetchAuthenticatedUser("at") as ForgeResult.Success).value

        assertThat(user.login).isEqualTo("earl-warren")
        assertThat(codeberg.requests.single().headers["Authorization"]).isEqualTo("token at")
    }

    @Test
    fun a_rejected_token_is_unauthorized() = runTest {
        val api = with(codeberg) { ForgejoAuthApi(client { json("""{"message":"invalid token"}""", HttpStatusCode.Unauthorized) }, ForgeInstance.Codeberg) }

        assertThat(api.fetchAuthenticatedUser("bad")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }
}
