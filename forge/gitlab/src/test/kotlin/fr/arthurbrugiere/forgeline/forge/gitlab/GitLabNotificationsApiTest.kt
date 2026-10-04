package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
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

class GitLabNotificationsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabNotificationsApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun fetches_threads_from_todos() = runTest {
        val json = """
            [
                {
                    "id": 1001,
                    "project": {
                        "id": 278964,
                        "name": "gitlab",
                        "path_with_namespace": "gitlab-org/gitlab"
                    },
                    "action_name": "assigned",
                    "target_type": "Issue",
                    "target": {
                        "id": 501,
                        "iid": 42,
                        "title": "Fix memory leak",
                        "state": "opened"
                    },
                    "state": "pending",
                    "created_at": "2026-10-01T10:00:00Z"
                },
                {
                    "id": 1002,
                    "project": {
                        "id": 278964,
                        "name": "gitlab",
                        "path_with_namespace": "gitlab-org/gitlab"
                    },
                    "action_name": "review_requested",
                    "target_type": "MergeRequest",
                    "target": {
                        "id": 502,
                        "iid": 99,
                        "title": "Support GitLab forge",
                        "state": "opened",
                        "draft": false
                    },
                    "state": "pending",
                    "created_at": "2026-10-01T11:00:00Z"
                }
            ]
        """.trimIndent()

        val sync = api { json(json) }.threads("token", null).value()
        val threads = sync.threads!!
        assertThat(threads).hasSize(2)

        val t1 = threads[0]
        assertThat(t1.id).isEqualTo("1001")
        assertThat(t1.repo).isEqualTo(repo)
        assertThat(t1.number).isEqualTo(42)
        assertThat(t1.type).isEqualTo(SubjectType.ISSUE)
        assertThat(t1.reason).isEqualTo(NotificationReason.ASSIGN)
        assertThat(t1.state).isEqualTo(SubjectState.OPEN)
        assertThat(t1.subject?.isPullRequest).isFalse()

        val t2 = threads[1]
        assertThat(t2.id).isEqualTo("1002")
        assertThat(t2.number).isEqualTo(99)
        assertThat(t2.type).isEqualTo(SubjectType.PULL_REQUEST)
        assertThat(t2.reason).isEqualTo(NotificationReason.REVIEW_REQUESTED)
        assertThat(t2.subject?.isPullRequest).isTrue()
    }

    @Test
    fun marks_todo_done() = runTest {
        val res = api { json("""{"id": 1001, "state": "done"}""") }.markDone("token", "1001")
        assertThat(res).isInstanceOf(ForgeResult.Success::class.java)
        val req = requests.single()
        assertThat(req.method).isEqualTo(HttpMethod.Post)
        assertThat(req.url.encodedPath).isEqualTo("/api/v4/todos/1001/mark_as_done")
    }

    @Test
    fun subject_states_resolves_issues_and_merge_requests() = runTest {
        val issueRef = IssueRef(repo, 42, isPullRequest = false)
        val mrRef = IssueRef(repo, 99, isPullRequest = true)

        val api = api { req ->
            if (req.url.encodedPath.contains("/merge_requests/99")) {
                json("""{"id": 502, "iid": 99, "project_id": 278964, "title": "MR", "state": "merged", "created_at": "2026-10-01T10:00:00Z", "draft": false}""")
            } else {
                json("""{"id": 501, "iid": 42, "project_id": 278964, "title": "Issue", "state": "closed", "created_at": "2026-10-01T10:00:00Z"}""")
            }
        }

        val states = api.subjectStates("token", listOf(issueRef, mrRef)).value()
        assertThat(states[issueRef]).isEqualTo(SubjectState.CLOSED)
        assertThat(states[mrRef]).isEqualTo(SubjectState.MERGED)
    }
}
