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
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GitLabSearchApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabSearchApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun searches_repositories() = runTest {
        val json = """
            [
                {
                    "id": 1,
                    "name": "project-a",
                    "path": "project-a",
                    "path_with_namespace": "group/project-a",
                    "web_url": "https://gitlab.com/group/project-a",
                    "star_count": 10
                }
            ]
        """.trimIndent()

        val results = api { json(json) }.repositories(null, "project-a").value()
        assertThat(results.items).hasSize(1)
        assertThat(results.items[0].id.fullName).isEqualTo("group/project-a")
        assertThat(results.items[0].stars).isEqualTo(10)
    }

    @Test
    fun searches_issues_and_merge_requests() = runTest {
        val api = api { req ->
            if (req.url.encodedPath.endsWith("/merge_requests")) {
                json("""
                    [
                        {
                            "id": 20,
                            "iid": 2,
                            "project_id": 1,
                            "title": "MR fix",
                            "state": "opened",
                            "created_at": "2026-10-01T12:00:00Z",
                            "web_url": "https://gitlab.com/group/project-a/-/merge_requests/2"
                        }
                    ]
                """.trimIndent())
            } else {
                json("""
                    [
                        {
                            "id": 10,
                            "iid": 1,
                            "project_id": 1,
                            "title": "Issue bug",
                            "state": "opened",
                            "created_at": "2026-10-01T10:00:00Z",
                            "web_url": "https://gitlab.com/group/project-a/-/issues/1"
                        }
                    ]
                """.trimIndent())
            }
        }

        val results = api.issues(null, "bug").value()
        assertThat(results.items).hasSize(2)
        // Ordered by createdAt descending: MR (12:00) then Issue (10:00)
        assertThat(results.items[0].issue.title).isEqualTo("MR fix")
        assertThat(results.items[0].issue.isPullRequest).isTrue()
        assertThat(results.items[0].repo.fullName).isEqualTo("group/project-a")

        assertThat(results.items[1].issue.title).isEqualTo("Issue bug")
        assertThat(results.items[1].issue.isPullRequest).isFalse()
        assertThat(results.items[1].repo.fullName).isEqualTo("group/project-a")
    }

    @Test
    fun searches_users() = runTest {
        val json = """
            [
                {
                    "id": 123,
                    "username": "tanuki",
                    "avatar_url": "https://gitlab.com/avatar.png"
                }
            ]
        """.trimIndent()

        val results = api { json(json) }.users(null, "tanuki").value()
        assertThat(results.items).hasSize(1)
        assertThat(results.items[0].login).isEqualTo("tanuki")
        assertThat(results.items[0].avatarUrl).isEqualTo("https://gitlab.com/avatar.png")
    }
}
