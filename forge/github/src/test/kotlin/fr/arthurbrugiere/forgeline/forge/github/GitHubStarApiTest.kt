package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class GitHubStarApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubStarApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private val paperclip = RepoId("paperclipai", "paperclip")
    private val hindsight = RepoId("vectorize-io", "hindsight")
    private val gone = RepoId("someone", "deleted")

    @Test
    fun starring_and_unstarring_use_put_and_delete() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }

        assertThat(api.setStarred("tok", paperclip, starred = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setStarred("tok", paperclip, starred = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { it.method }).containsExactly(HttpMethod.Put, HttpMethod.Delete).inOrder()
        assertThat(requests.map { it.url.toString() }.distinct())
            .containsExactly("https://api.github.com/user/starred/paperclipai/paperclip")
        assertThat(requests.map { it.headers[HttpHeaders.Authorization] }.distinct()).containsExactly("Bearer tok")
    }

    @Test
    fun starring_with_a_revoked_token_is_unauthorized() = runTest {
        val result = api { respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }
            .setStarred("tok", paperclip, starred = true)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    @Test
    fun starred_status_is_fetched_for_all_repos_in_one_graphql_query() = runTest {
        val api = api {
            respond(
                """{"data":{"r0":{"viewerHasStarred":true},"r1":{"viewerHasStarred":false},"r2":null},
                   "errors":[{"type":"NOT_FOUND","path":["r2"]}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = api.starredStatus("tok", listOf(paperclip, hindsight, gone))

        assertThat(result).isEqualTo(ForgeResult.Success(mapOf(paperclip to true, hindsight to false)))
        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/graphql")
        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        val variables = body["variables"]!!.jsonObject
        assertThat(variables["o0"]!!.jsonPrimitive.content).isEqualTo("paperclipai")
        assertThat(variables["n1"]!!.jsonPrimitive.content).isEqualTo("hindsight")
        // Names travel as variables, never spliced into the query text.
        assertThat(body["query"]!!.jsonPrimitive.content).doesNotContain("paperclipai")
    }

    @Test
    fun no_repos_means_no_request() = runTest {
        val result = api { error("no request expected") }.starredStatus("tok", emptyList())

        assertThat(result).isEqualTo(ForgeResult.Success(emptyMap<RepoId, Boolean>()))
    }

    @Test
    fun a_repository_is_watched_when_its_subscription_says_so() = runTest {
        val watching = api { respond("""{"subscribed":true,"ignored":false}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            .isWatching("tok", paperclip)

        assertThat(watching).isEqualTo(ForgeResult.Success(true))
        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/subscription")
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun no_subscription_is_not_watching_rather_than_a_failure() = runTest {
        // GitHub answers 404 when the account never set anything for the repository.
        val watching = api { respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.isWatching("tok", paperclip)

        assertThat(watching).isEqualTo(ForgeResult.Success(false))
    }

    @Test
    fun watching_puts_a_subscription_and_no_longer_watching_deletes_it() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }

        assertThat(api.setWatching("tok", paperclip, watching = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setWatching("tok", paperclip, watching = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { it.method }).containsExactly(HttpMethod.Put, HttpMethod.Delete).inOrder()
        assertThat(requests.map { it.url.encodedPath }.distinct()).containsExactly("/repos/paperclipai/paperclip/subscription")
        assertThat((requests[0].body as io.ktor.http.content.TextContent).text).contains("\"subscribed\":true")
    }

    @Test
    fun a_fork_says_where_the_copy_is() = runTest {
        val fork = api { respond("""{"name":"paperclip","full_name":"me/paperclip","owner":{"login":"me"}}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json")) }
            .fork("tok", paperclip)

        assertThat(fork).isEqualTo(ForgeResult.Success(RepoId("me", "paperclip")))
        assertThat(requests.single().method).isEqualTo(HttpMethod.Post)
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/forks")
    }

    @Test
    fun a_fork_that_is_refused_is_a_failure() = runTest {
        val fork = api { respond("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden) }.fork("tok", paperclip)

        assertThat(fork).isInstanceOf(ForgeResult.Failure::class.java)
    }
}
