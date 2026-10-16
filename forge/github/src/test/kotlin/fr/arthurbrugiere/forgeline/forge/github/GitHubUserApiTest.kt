package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.LinkKind
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
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

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
    fun a_person_s_links_are_their_public_email_social_accounts_and_x_name() = runTest {
        // The owner's own listed accounts are all "generic" on GitHub: Signal, XMPP and Matrix (2026-10-16).
        val api = api { request ->
            when (request.url.encodedPath) {
                "/users/ana/social_accounts" -> json(
                    """[{"provider":"generic","url":"https://signal.me/#eu/abc"},{"provider":"generic","url":"https://matrix.to/#/@ana:matrix.org"},""" +
                        """{"provider":"mastodon","url":"https://social.example.org/users/ana"},{"provider":"twitter","url":"https://twitter.com/ana"}]""",
                )
                else -> json("""{"login":"ana","type":"User","email":"ana@example.org","twitter_username":"ana"}""")
            }
        }

        val links = api.user("tok", "ana").value().links

        assertThat(links.map { it.kind to it.label }).containsExactly(
            LinkKind.EMAIL to "ana@example.org",
            LinkKind.SIGNAL to "Signal",
            LinkKind.MATRIX to "@ana:matrix.org",
            LinkKind.MASTODON to "@ana@social.example.org",
            // Listed and also kept as the X name: once.
            LinkKind.X to "@ana",
        ).inOrder()
    }

    @Test
    fun an_organisation_has_no_social_accounts_to_list_and_still_loads() = runTest {
        val api = api { request ->
            if (request.url.encodedPath.endsWith("social_accounts")) json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) else json(fixture("org.json"))
        }

        val org = api.user(null, "github").value()

        assertThat(org.isOrganization).isTrue()
        assertThat(org.links.map { it.kind }).doesNotContain(LinkKind.SIGNAL)
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
        assertThat(starred.first().ownerAvatarUrl).isEqualTo("https://avatars.githubusercontent.com/u/20708333?v=4")
        assertThat(requests[0].url.parameters["sort"]).isEqualTo("updated")
    }

    @Test
    fun one_s_own_and_an_organisation_s_lists_hold_their_private_repositories() = runTest {
        // GitHub's list of someone's repositories is public only, even for that person (reported 2026-10-16).
        val api = api { json("""[{"full_name":"me/secret","private":true},{"full_name":"me/open"}]""") }

        val mine = api.repos("tok", "me", mine = true).value()
        api.repos("tok", "ACROSS-Lab", organization = true)
        api.repos("tok", "octocat")

        assertThat(requests.map { it.url.encodedPath }).containsExactly("/user/repos", "/orgs/ACROSS-Lab/repos", "/users/octocat/repos").inOrder()
        assertThat(requests[0].url.parameters["affiliation"]).isEqualTo("owner")
        assertThat(requests[1].url.parameters["type"]).isEqualTo("all")
        assertThat(mine.map { it.isPrivate }).containsExactly(true, false).inOrder()
    }

    @Test
    fun signed_out_one_s_own_list_is_the_public_one() = runTest {
        api { json("[]") }.repos(null, "me", mine = true)

        assertThat(requests.single().url.encodedPath).isEqualTo("/users/me/repos")
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

    @Test
    fun every_reachable_repository_is_read_page_by_page_by_last_push() = runTest {
        val api = api { request ->
            val page = request.url.parameters["page"]
            val link = """<https://api.github.com/user/repos?page=3>; rel="last""""
            when (page) {
                "1" -> respond(
                    """[{"full_name":"me/forgeline","pushed_at":"2026-10-16T13:58:28Z","owner":{"type":"User"}},{"full_name":"ACROSS-Lab/GamaWebPlayer","private":false,"owner":{"type":"Organization"}}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.Link to listOf(link)),
                )
                "2" -> json("""[{"full_name":"gama-platform/gama"}]""")
                else -> json("""[{"full_name":"ACROSS-Lab/a-private-one","private":true}]""")
            }
        }

        val repos = api.reachableRepos("tok").value()

        assertThat(repos.map { it.id.fullName }).containsExactly("me/forgeline", "ACROSS-Lab/GamaWebPlayer", "gama-platform/gama", "ACROSS-Lab/a-private-one").inOrder()
        assertThat(repos.first().pushedAt).isEqualTo(java.time.Instant.parse("2026-10-16T13:58:28Z"))
        assertThat(repos.take(2).map { it.ownerIsOrganization }).containsExactly(false, true).inOrder()
        assertThat(requests.first().url.parameters["affiliation"]).isEqualTo("owner,collaborator,organization_member")
        assertThat(requests.first().url.parameters["sort"]).isEqualTo("pushed")
        assertThat(requests.map { it.url.parameters["page"] }).containsExactly("1", "2", "3")
    }

    @Test
    fun one_s_organisations_are_listed() = runTest {
        val orgs = api { json("""[{"login":"ACROSS-Lab","avatar_url":"https://avatars.example/across"}]""") }.organizations("tok").value()

        assertThat(requests.single().url.encodedPath).isEqualTo("/user/orgs")
        assertThat(orgs.single().login).isEqualTo("ACROSS-Lab")
        assertThat(orgs.single().isOrganization).isTrue()
    }
}
