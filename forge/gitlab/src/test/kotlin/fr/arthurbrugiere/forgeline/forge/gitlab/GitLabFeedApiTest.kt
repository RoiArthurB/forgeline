package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
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

class GitLabFeedApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabFeedApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun fetches_and_maps_received_events() = runTest {
        val eventsJson = """
            [
                {
                    "id": 1,
                    "project_id": 100,
                    "action_name": "pushed to",
                    "push_data": {
                        "ref": "main"
                    },
                    "created_at": "2026-10-01T10:00:00Z",
                    "author": {
                        "id": 10,
                        "username": "roiarthurb"
                    }
                },
                {
                    "id": 2,
                    "project_id": 100,
                    "action_name": "created",
                    "target_type": "Issue",
                    "target_iid": 15,
                    "target_title": "Fix bug",
                    "created_at": "2026-10-01T10:05:00Z",
                    "author": {
                        "id": 10,
                        "username": "roiarthurb"
                    }
                },
                {
                    "id": 3,
                    "project_id": 100,
                    "action_name": "accepted",
                    "target_type": "MergeRequest",
                    "target_iid": 7,
                    "target_title": "Add feature",
                    "created_at": "2026-10-01T10:10:00Z",
                    "author": {
                        "id": 10,
                        "username": "roiarthurb"
                    }
                }
            ]
        """.trimIndent()

        val projectJson = """
            {
                "id": 100,
                "name": "my-project",
                "path": "my-project",
                "path_with_namespace": "my-group/my-project",
                "web_url": "https://gitlab.com/my-group/my-project"
            }
        """.trimIndent()

        val api = api { req ->
            if (req.url.encodedPath == "/api/v4/events") {
                json(eventsJson)
            } else if (req.url.encodedPath == "/api/v4/projects/100") {
                json(projectJson)
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }

        val page = api.receivedEvents("token", "roiarthurb").value()
        val events = page.events!!
        assertThat(events).hasSize(3)

        assertThat(events[0].action).isEqualTo(FeedAction.Pushed("main"))
        assertThat(events[0].repo.fullName).isEqualTo("my-group/my-project")
        assertThat(events[0].actor.login).isEqualTo("roiarthurb")

        assertThat(events[1].action).isEqualTo(FeedAction.Issue(IssueAction.OPENED, 15, "Fix bug"))

        assertThat(events[2].action).isEqualTo(FeedAction.PullRequest(PullRequestAction.MERGED, 7, "Add feature"))
    }
}
