package fr.arthurbrugiere.forgeline.forge.gitlab

import io.ktor.http.content.TextContent
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

    // What follows was tried on gitlab.com on 2026-10-07, on a scratch project: the addresses, the verbs and the
    // answers' status are GitLab's own.

    @Test
    fun a_note_is_rewritten_where_it_was_written() = runTest {
        // A note's address goes through its issue or its merge request: the same id under the other kind is not found.
        val api = api { json("""{"id":3969166511,"body":"probe note, edited","created_at":"2026-10-07T09:43:36.405Z","system":false}""") }

        api.editComment("tok", IssueRef(repo, 10, isPullRequest = true), 3969166511, "probe note, edited").value()

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Put)
        assertThat(request.url.toString()).isEqualTo("https://gitlab.com/api/v4/projects/gitlab-org%2Fgitlab/merge_requests/10/notes/3969166511")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"body":"probe note, edited"}""")
    }

    @Test
    fun a_note_is_deleted_where_it_was_written() = runTest {
        api { respond("", HttpStatusCode.NoContent) }.deleteComment("tok", IssueRef(repo, 10, isPullRequest = false), 3969166511).value()

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Delete)
        assertThat(request.url.toString()).isEqualTo("https://gitlab.com/api/v4/projects/gitlab-org%2Fgitlab/issues/10/notes/3969166511")
    }

    @Test
    fun a_note_already_gone_is_a_failure() = runTest {
        val result = api { json("""{"message":"404 Not found"}""", HttpStatusCode.NotFound) }.deleteComment("tok", IssueRef(repo, 10), 3969166511)

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http::class.java)
    }

    @Test
    fun the_text_of_an_issue_is_sent_as_its_description() = runTest {
        api { json("""{"iid":10,"title":"Probe issue, renamed","description":"","state":"opened","created_at":"2026-10-07T09:43:33.630Z"}""") }
            .edit("tok", IssueRef(repo, 10), "Probe issue, renamed", "").value()

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Put)
        assertThat(request.url.toString()).isEqualTo("https://gitlab.com/api/v4/projects/gitlab-org%2Fgitlab/issues/10")
        assertThat((request.body as TextContent).text).isEqualTo("""{"title":"Probe issue, renamed","description":""}""")
    }

    // The list is the shape gitlab.com answered on 2026-10-07 (one award per person, with its id and who gave it),
    // as are 201 for an award given and 204 for one taken back.
    private fun award(id: Long, name: String, username: String) = """{"id":$id,"name":"$name","user":{"id":1,"username":"$username"},"awardable_type":"Note"}"""

    private fun reacting(vararg awards: String) = api { request ->
        when (request.method) {
            HttpMethod.Get -> json(awards.joinToString(",", "[", "]"))
            HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
            else -> json(award(99, "thumbsup", "me"), HttpStatusCode.Created)
        }
    }

    private fun sent() = requests.map { "${it.method.value} ${it.url.encodedPath}" }

    @Test
    fun an_award_not_given_yet_is_given_on_the_note() = runTest {
        val api = reacting(award(1, "thumbsup", "alice"), award(2, "heart", "me"))

        val counts = api.toggleReaction("tok", "me", IssueRef(repo, 10, isPullRequest = false), 555, Reaction.THUMBS_UP).value()

        assertThat(counts).containsExactly(Reaction.THUMBS_UP, 2, Reaction.HEART, 1)
        assertThat(sent()).containsExactly(
            "GET /api/v4/projects/gitlab-org%2Fgitlab/issues/10/notes/555/award_emoji",
            "POST /api/v4/projects/gitlab-org%2Fgitlab/issues/10/notes/555/award_emoji",
        ).inOrder()
        assertThat((requests.last().body as TextContent).text).isEqualTo("""{"name":"thumbsup"}""")
    }

    @Test
    fun one_s_own_award_is_taken_back_by_its_id_never_given_twice() = runTest {
        // Given twice, GitLab answers 404 "Award Emoji Name has already been taken": it is looked for first.
        val api = reacting(award(1, "thumbsup", "alice"), award(58620369, "thumbsup", "Me"))

        val counts = api.toggleReaction("tok", "me", IssueRef(repo, 10, isPullRequest = true), 555, Reaction.THUMBS_UP).value()

        assertThat(counts).containsExactly(Reaction.THUMBS_UP, 1)
        assertThat(sent()).containsExactly(
            "GET /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/10/notes/555/award_emoji",
            "DELETE /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/10/notes/555/award_emoji/58620369",
        ).inOrder()
    }

    @Test
    fun an_award_to_the_conversation_itself_goes_to_the_issue() = runTest {
        val counts = reacting().toggleReaction("tok", "me", IssueRef(repo, 10), null, Reaction.HOORAY).value()

        assertThat(counts).containsExactly(Reaction.HOORAY, 1)
        assertThat(sent().last()).isEqualTo("POST /api/v4/projects/gitlab-org%2Fgitlab/issues/10/award_emoji")
        assertThat((requests.last().body as TextContent).text).isEqualTo("""{"name":"tada"}""")
    }

    @Test
    fun every_reaction_has_a_gitlab_name_that_reads_back_as_itself() = runTest {
        // Regression guard: "laughing" is what GitLab calls the laugh, and it was not read back as one.
        Reaction.entries.forEach { reaction ->
            requests.clear()
            val counts = reacting().toggleReaction("tok", "me", IssueRef(repo, 10), null, reaction).value()
            val name = (requests.last().body as TextContent).text.substringAfter(":\"").substringBefore('"')

            assertThat(counts).containsExactly(reaction, 1)
            assertThat(GitLabAwardEmojiJson(1, name).toReaction()).isEqualTo(reaction)
        }
    }

    @Test
    fun an_award_the_forge_refuses_is_a_failure() = runTest {
        val api = api { request -> if (request.method == HttpMethod.Get) json("[]") else json("""{"message":"403 Forbidden"}""", HttpStatusCode.Forbidden) }

        val result = api.toggleReaction("tok", "me", IssueRef(repo, 10), 555, Reaction.EYES)

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http::class.java)
    }
}
