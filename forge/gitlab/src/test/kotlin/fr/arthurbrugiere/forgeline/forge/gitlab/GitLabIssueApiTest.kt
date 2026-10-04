package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
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

class GitLabIssueApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabIssueApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun fetches_issue_and_award_emojis() = runTest {
        val issueRef = IssueRef(repo, 10, isPullRequest = false)
        val api = api { req ->
            if (req.url.encodedPath.endsWith("/award_emoji")) {
                json("""[{"id": 1, "name": "thumbsup"}, {"id": 2, "name": "thumbsup"}]""")
            } else {
                json("""
                    {
                        "id": 100,
                        "iid": 10,
                        "project_id": 278964,
                        "title": "Crash on login",
                        "description": "Steps to reproduce...",
                        "state": "opened",
                        "created_at": "2026-10-01T10:00:00Z",
                        "user_notes_count": 2,
                        "labels": ["bug"]
                    }
                """.trimIndent())
            }
        }

        val details = api.issue(null, issueRef).value()
        assertThat(details.title).isEqualTo("Crash on login")
        assertThat(details.state).isEqualTo(IssueState.OPEN)
        assertThat(details.reactions[Reaction.THUMBS_UP]).isEqualTo(2)
        assertThat(details.pullRequest).isNull()
        assertThat(requests.any { it.url.encodedPath.contains("/issues/10") }).isTrue()
    }

    @Test
    fun fetches_merge_request_and_populates_pull_request_info() = runTest {
        val mrRef = IssueRef(repo, 10, isPullRequest = true)
        val api = api { req ->
            if (req.url.encodedPath.endsWith("/award_emoji")) {
                json("[]")
            } else {
                json("""
                    {
                        "id": 200,
                        "iid": 10,
                        "project_id": 278964,
                        "title": "Add feature",
                        "description": "MR description",
                        "state": "opened",
                        "created_at": "2026-10-01T10:00:00Z",
                        "draft": false,
                        "source_branch": "feat",
                        "target_branch": "main",
                        "changes_count": "5"
                    }
                """.trimIndent())
            }
        }

        val details = api.issue(null, mrRef).value()
        assertThat(details.title).isEqualTo("Add feature")
        assertThat(details.pullRequest).isNotNull()
        assertThat(details.pullRequest?.baseRef).isEqualTo("main")
        assertThat(details.pullRequest?.headRef).isEqualTo("feat")
        assertThat(details.pullRequest?.changedFiles).isEqualTo(5)
        assertThat(requests.any { it.url.encodedPath.contains("/merge_requests/10") }).isTrue()
    }

    @Test
    fun timeline_differentiates_comments_and_system_events() = runTest {
        val issueRef = IssueRef(repo, 10, isPullRequest = false)
        val json = """
            [
                {
                    "id": 1,
                    "body": "This is a comment",
                    "created_at": "2026-10-01T11:00:00Z",
                    "system": false
                },
                {
                    "id": 2,
                    "body": "assigned to @roiarthurb",
                    "created_at": "2026-10-01T11:05:00Z",
                    "system": true
                }
            ]
        """.trimIndent()

        val timeline = api { json(json) }.timeline(null, issueRef, 1).value()
        assertThat(timeline.items).hasSize(2)
        assertThat(timeline.items[0]).isInstanceOf(TimelineItem.Comment::class.java)
        assertThat((timeline.items[0] as TimelineItem.Comment).body).isEqualTo("This is a comment")

        assertThat(timeline.items[1]).isInstanceOf(TimelineItem.Event::class.java)
        assertThat((timeline.items[1] as TimelineItem.Event).event).isEqualTo(ConversationEvent.ASSIGNED)
    }

    @Test
    fun time_tracking_and_add_spent_time() = runTest {
        val issueRef = IssueRef(repo, 10, isPullRequest = false)
        val api = api { req ->
            if (req.method == HttpMethod.Get) {
                json("""{"time_estimate": 3600, "total_time_spent": 1800}""")
            } else {
                json("""{"total_time_spent": 3600}""")
            }
        }

        val tracking = api.timeTracking("token", issueRef).value()
        assertThat(tracking.totalSeconds).isEqualTo(1800L)

        val addResult = api.addTime("token", issueRef, 1800L)
        assertThat(addResult).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests.last().url.encodedPath).contains("/add_spent_time")
        assertThat(requests.last().url.parameters["duration"]).isEqualTo("1800s")
    }
}
