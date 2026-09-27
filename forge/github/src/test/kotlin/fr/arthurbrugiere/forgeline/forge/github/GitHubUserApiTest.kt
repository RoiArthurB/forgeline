package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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

/** Fixtures: real api.github.com responses for octocat and the github org, captured 2026-09-27. */
class GitHubUserApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/user/$name")) { name }.readText()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubUserApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun parses_a_user_profile() = runTest {
        val user = api { json(fixture("user.json")) }.user(null, "octocat").value()

        assertThat(user.login).isEqualTo("octocat")
        assertThat(user.name).isEqualTo("The Octocat")
        assertThat(user.website).isEqualTo("https://github.blog")
        assertThat(user.publicRepos).isEqualTo(8)
        assertThat(user.followers).isGreaterThan(20_000)
        assertThat(user.isOrganization).isFalse()
    }

    @Test
    fun recognizes_organizations() = runTest {
        assertThat(api { json(fixture("org.json")) }.user(null, "github").value().isOrganization).isTrue()
    }

    @Test
    fun lists_repos_by_last_update_and_starred_repos() = runTest {
        val api = api { request -> json(fixture(if (request.url.encodedPath.endsWith("starred")) "starred.json" else "repos.json")) }

        val repos = api.repos(null, "octocat").value()
        val starred = api.starred(null, "octocat").value()

        assertThat(repos.first().id.fullName).isEqualTo("octocat/Hello-World")
        assertThat(starred.map { it.id.fullName }).containsExactly("violet-org/boysenberry-repo", "octocat/Spoon-Knife", "octocat/Hello-World").inOrder()
        assertThat(requests[0].url.parameters["sort"]).isEqualTo("updated")
    }

    @Test
    fun following_state_comes_from_the_status_code() = runTest {
        assertThat(api { respond("", HttpStatusCode.NoContent) }.isFollowing("t", "octocat")).isEqualTo(ForgeResult.Success(true))
        assertThat(api { respond("", HttpStatusCode.NotFound) }.isFollowing("t", "octocat")).isEqualTo(ForgeResult.Success(false))
        assertThat(requests.first().url.encodedPath).isEqualTo("/user/following/octocat")
    }

    @Test
    fun follow_and_unfollow_use_put_and_delete() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }

        api.setFollowing("t", "octocat", follow = true)
        api.setFollowing("t", "octocat", follow = false)

        assertThat(requests.map { it.method }).containsExactly(HttpMethod.Put, HttpMethod.Delete).inOrder()
        assertThat(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer t" }).isTrue()
    }

    @Test
    fun an_unknown_user_is_a_404() = runTest {
        assertThat(api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.user(null, "nobody"))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }
}
