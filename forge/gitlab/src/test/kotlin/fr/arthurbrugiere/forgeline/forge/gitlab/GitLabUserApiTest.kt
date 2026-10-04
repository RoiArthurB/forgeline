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
    fun fetches_group_profile_when_user_not_found() = runTest {
        val api = userApi { req ->
            if (req.url.encodedPath == "/api/v4/users") {
                json("[]")
            } else if (req.url.encodedPath.endsWith("/projects")) {
                respond("[]", HttpStatusCode.OK, headersOf("X-Total" to listOf("45")))
            } else {
                json("""
                    {
                        "id": 999,
                        "name": "GitLab Org",
                        "path": "gitlab-org",
                        "full_path": "gitlab-org",
                        "description": "GitLab organization",
                        "avatar_url": "https://gitlab.com/org.png"
                    }
                """.trimIndent())
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
}
