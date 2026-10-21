package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** GitHub's GraphQL API answers 200 even when it refuses: what it refuses is said in its errors. */
class GitHubGraphQlTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg more: Pair<String, String>) =
        respond(body, status, headersOf(*(listOf(HttpHeaders.ContentType to "application/json") + more).map { it.first to listOf(it.second) }.toTypedArray()))

    private fun client(answer: MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData) =
        gitHubHttpClient(MockEngine { requests += it; answer() })

    private suspend fun ask(partial: Boolean = false, answer: MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData) =
        client(answer).gitHubGraphQl("https://api.github.com", "tok", "query { viewer { login } }", buildJsonObject { put("n", 1) }, partial)

    @Test
    fun the_query_goes_signed_in_with_its_variables_and_the_data_comes_back() = runTest {
        val result = ask { json("""{"data":{"viewer":{"login":"octocat"}}}""") }

        assertThat((result as ForgeResult.Success).value["viewer"]!!.jsonObject["login"]).isEqualTo(JsonPrimitive("octocat"))
        val request = requests.single()
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/graphql")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat(request.headers["X-GitHub-Api-Version"]).isEqualTo(GitHubAuthApi.API_VERSION)
        assertThat(String(request.body.toByteArray())).isEqualTo("""{"query":"query { viewer { login } }","variables":{"n":1}}""")
    }

    @Test
    fun a_refusal_reads_as_the_rest_apis_would() = runTest {
        val forbidden = ask { json("""{"data":{"repository":null},"errors":[{"type":"FORBIDDEN","message":"Resource not accessible"}]}""") }
        val missing = ask { json("""{"data":{"repository":null},"errors":[{"type":"NOT_FOUND","message":"Could not resolve"}]}""") }
        val other = ask { json("""{"errors":[{"message":"Field 'x' doesn't exist"}]}""") }

        assertThat(forbidden).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Resource not accessible")))
        assertThat(missing).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Could not resolve")))
        assertThat(other).isEqualTo(ForgeResult.Failure(ForgeError.Http(422, "Field 'x' doesn't exist")))
    }

    @Test
    fun spent_points_are_a_rate_limit_even_when_much_was_asked_at_once() = runTest {
        val limited = """{"errors":[{"type":"RATE_LIMITED","message":"API rate limit exceeded"}]}"""

        val strict = ask { json(limited, HttpStatusCode.OK, "x-ratelimit-reset" to "1761040000") }
        val partial = ask(partial = true) { json(limited, HttpStatusCode.OK, "x-ratelimit-reset" to "1761040000") }

        assertThat(strict).isEqualTo(ForgeResult.Failure(ForgeError.RateLimited(1761040000)))
        assertThat(partial).isEqualTo(ForgeResult.Failure(ForgeError.RateLimited(1761040000)))
    }

    @Test
    fun asking_after_many_things_keeps_what_came_back() = runTest {
        val result = ask(partial = true) {
            json("""{"data":{"r0":{"viewerHasStarred":true},"r1":null},"errors":[{"type":"NOT_FOUND","path":["r1"],"message":"gone"}]}""")
        }

        val data = (result as ForgeResult.Success).value
        assertThat(data.keys).containsExactly("r0", "r1")
    }

    @Test
    fun a_refusal_without_any_data_fails_even_when_much_was_asked() = runTest {
        assertThat(ask(partial = true) { json("""{"errors":[{"type":"FORBIDDEN","message":"SAML enforcement"}]}""") })
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "SAML enforcement")))
    }

    @Test
    fun a_failed_answer_reads_like_any_other() = runTest {
        assertThat(ask { json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }

    // Regression: a rate-limited batch answered "nothing is known", so the Inbox kept stale states without saying why.
    @Test
    fun subject_states_spent_points_are_a_rate_limit() = runTest {
        val api = GitHubNotificationsApi(client { json("""{"errors":[{"type":"RATE_LIMITED","message":"API rate limit exceeded"}]}""") })

        val result = api.subjectStates("tok", listOf(IssueRef(RepoId("octocat", "Hello-World"), 1)))

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.RateLimited(null)))
    }
}
