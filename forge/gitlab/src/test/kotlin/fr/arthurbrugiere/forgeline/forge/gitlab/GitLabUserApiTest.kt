package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
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

class GitLabUserApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun userApi(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabUserApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun starApi(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabStarApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun fetches_user_profile() = runTest {
        val userJson = """
            [
                {
                    "id": 123,
                    "username": "tanuki",
                    "name": "Tanuki GitLab",
                    "avatar_url": "https://gitlab.com/avatar.png",
                    "bio": "GitLab mascot",
                    "location": "Remote",
                    "followers": 100,
                    "following": 50,
                    "created_at": "2020-01-01T00:00:00Z"
                }
            ]
        """.trimIndent()

        val api = userApi { req ->
            if (req.url.encodedPath.endsWith("/projects")) {
                respond("[]", HttpStatusCode.OK, headersOf("X-Total" to listOf("12")))
            } else {
                json(userJson)
            }
        }

        val profile = api.user(null, "tanuki").value()
        assertThat(profile.login).isEqualTo("tanuki")
        assertThat(profile.name).isEqualTo("Tanuki GitLab")
        assertThat(profile.bio).isEqualTo("GitLab mascot")
        assertThat(profile.publicRepos).isEqualTo(12)
        assertThat(profile.isOrganization).isFalse()
    }

    @Test
    fun a_person_s_full_profile_and_links_come_from_their_id() = runTest {
        // A lookup by username answers only the name and avatar (checked 2026-10-16): the rest comes with the id.
        val api = userApi { req ->
            when {
                req.url.encodedPath.endsWith("/projects") -> respond("[]", HttpStatusCode.OK, headersOf("X-Total" to listOf("3")))
                req.url.encodedPath == "/api/v4/users/123" -> json(
                    """{"id":123,"username":"tanuki","name":"Tanuki","bio":"GitLab mascot","website_url":"https://tanuki.dev","organization":"GitLab",""" +
                        """"public_email":"tanuki@example.org","twitter":"tanuki","linkedin":"","discord":"81384788765712384","github":"tanuki-gh"}""",
                )
                else -> json("""[{"id":123,"username":"tanuki","name":"Tanuki"}]""")
            }
        }

        val profile = api.user("tok", "tanuki").value()

        assertThat(profile.bio).isEqualTo("GitLab mascot")
        assertThat(profile.company).isEqualTo("GitLab")
        assertThat(profile.website).isEqualTo("https://tanuki.dev")
        assertThat(profile.links.map { it.kind to it.url }).containsExactly(
            fr.arthurbrugiere.forgeline.core.model.LinkKind.EMAIL to "mailto:tanuki@example.org",
            fr.arthurbrugiere.forgeline.core.model.LinkKind.X to "https://x.com/tanuki",
            fr.arthurbrugiere.forgeline.core.model.LinkKind.GITHUB to "https://github.com/tanuki-gh",
            fr.arthurbrugiere.forgeline.core.model.LinkKind.DISCORD to "https://discord.com/users/81384788765712384",
        ).inOrder()
    }

    @Test
    fun fetches_group_profile_when_user_not_found() = runTest {
        val api = userApi { req ->
            if (req.url.encodedPath == "/api/v4/users") {
                json("[]")
            } else if (req.url.encodedPath.endsWith("/projects")) {
                respond("[]", HttpStatusCode.OK, headersOf("X-Total" to listOf("45")))
            } else {
                json(
                    """
                    {
                        "id": 999,
                        "name": "GitLab Org",
                        "path": "gitlab-org",
                        "full_path": "gitlab-org",
                        "description": "GitLab organization",
                        "avatar_url": "https://gitlab.com/org.png"
                    }
                    """.trimIndent()
                )
            }
        }

        val profile = api.user(null, "gitlab-org").value()
        assertThat(profile.login).isEqualTo("gitlab-org")
        assertThat(profile.name).isEqualTo("GitLab Org")
        assertThat(profile.isOrganization).isTrue()
        assertThat(profile.publicRepos).isEqualTo(45)
    }

    @Test
    fun follows_and_unfollows_user() = runTest {
        val api = userApi { req ->
            if (req.url.encodedPath == "/api/v4/users") {
                json("""[{"id": 123, "username": "tanuki"}]""")
            } else {
                json("""{"id": 123}""")
            }
        }

        val followRes = api.setFollowing("token", "tanuki", true)
        assertThat(followRes).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests.last().url.encodedPath).isEqualTo("/api/v4/users/123/follow")

        val unfollowRes = api.setFollowing("token", "tanuki", false)
        assertThat(unfollowRes).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests.last().url.encodedPath).isEqualTo("/api/v4/users/123/unfollow")
    }

    @Test
    fun checks_starred_status() = runTest {
        val json = """
            [
                {
                    "id": 278964,
                    "name": "gitlab",
                    "path": "gitlab",
                    "path_with_namespace": "gitlab-org/gitlab",
                    "web_url": "https://gitlab.com/gitlab-org/gitlab"
                }
            ]
        """.trimIndent()

        val api = starApi { json(json) }
        val status = api.starredStatus("token", listOf(repo)).value()
        assertThat(status[repo]).isTrue()
    }

    @Test
    fun stars_and_unstars_project() = runTest {
        val api = starApi { json("""{"id": 278964, "star_count": 25001}""") }

        val starRes = api.setStarred("token", repo, true)
        assertThat(starRes).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests[0].method).isEqualTo(HttpMethod.Post)
        assertThat(requests[0].url.encodedPath).contains("/star")

        val unstarRes = api.setStarred("token", repo, false)
        assertThat(unstarRes).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests[1].method).isEqualTo(HttpMethod.Post)
        assertThat(requests[1].url.encodedPath).contains("/unstar")
    }

    @Test
    fun a_project_is_watched_only_when_it_notifies_of_everything() = runTest {
        assertThat(starApi { json("""{"level":"watch"}""") }.isWatching("token", repo).value()).isTrue()
        // What the account does everywhere, or less, is not watching this project.
        assertThat(starApi { json("""{"level":"global"}""") }.isWatching("token", repo).value()).isFalse()
        assertThat(starApi { json("""{"level":"participating"}""") }.isWatching("token", repo).value()).isFalse()

        assertThat(requests.last().url.encodedPath).endsWith("/notification_settings")
    }

    @Test
    fun watching_sets_the_level_and_no_longer_watching_gives_it_back_to_the_account_s_setting() = runTest {
        val api = starApi { json("""{"level":"watch"}""") }

        assertThat(api.setWatching("token", repo, true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setWatching("token", repo, false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { it.method }.distinct()).containsExactly(HttpMethod.Put)
        assertThat(requests.map { (it.body as io.ktor.http.content.TextContent).text })
            .containsExactly("""{"level":"watch"}""", """{"level":"global"}""").inOrder()
    }

    @Test
    fun a_fork_says_where_the_copy_is_even_in_a_subgroup() = runTest {
        val fork = starApi { json("""{"id":9,"name":"tool","path":"tool","path_with_namespace":"me/sub/tool","web_url":"https://gitlab.com/me/sub/tool"}""") }
            .fork("token", repo).value()

        assertThat(fork).isEqualTo(RepoId("me/sub", "tool", repo.forge))
        assertThat(requests.single().method).isEqualTo(HttpMethod.Post)
        assertThat(requests.single().url.encodedPath).endsWith("/fork")
    }

    @Test
    fun every_project_one_is_a_member_of_and_one_s_groups() = runTest {
        val project = """{"id":1,"name":"runner","path":"runner","path_with_namespace":"acme/runner","web_url":"https://gitlab.com/acme/runner","visibility":"private","namespace":{"kind":"group"}}"""
        val api = userApi { req ->
            when (req.url.encodedPath) {
                "/api/v4/projects" -> respond("[$project]", HttpStatusCode.OK, headersOf(io.ktor.http.HttpHeaders.ContentType to listOf("application/json"), "X-Total-Pages" to listOf("1")))
                else -> json("""[{"id":7,"name":"Acme","path":"acme","full_path":"acme"}]""")
            }
        }

        val repos = api.reachableRepos("tok").value()
        val groups = api.organizations("tok").value()

        assertThat(requests.first().url.parameters["membership"]).isEqualTo("true")
        assertThat(repos.single().id.fullName).isEqualTo("acme/runner")
        assertThat(repos.single().isPrivate).isTrue()
        assertThat(repos.single().ownerIsOrganization).isTrue()
        assertThat(groups.single().login).isEqualTo("acme")
    }

    @Test
    fun contributions_read_from_the_profile_s_own_calendar() = runTest {
        val contributions = userApi { json("""{"2026-10-26": 14, "2026-10-27": 3}""") }.contributions(null, "dzaporozhets").value()

        assertThat(contributions.total).isEqualTo(17)
        assertThat(requests.single().url.toString()).isEqualTo("https://gitlab.com/users/dzaporozhets/calendar.json")
    }
}
