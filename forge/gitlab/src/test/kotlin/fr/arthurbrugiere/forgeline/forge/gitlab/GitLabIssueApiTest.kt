package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
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
                json(
                    """
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
                    """.trimIndent()
                )
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
                json(
                    """
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
                    """.trimIndent()
                )
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
    fun a_remark_on_a_line_is_left_to_its_thread_not_the_conversation() = runTest {
        val ref = IssueRef(repo, 12, isPullRequest = true)
        val api = api { req ->
            when {
                req.url.encodedPath.endsWith("/notes") -> json(
                    """[{"id":1,"body":"On the whole","created_at":"2026-10-01T10:00:00Z","system":false,"type":null},
                    {"id":2,"body":"On line 3","created_at":"2026-10-01T10:01:00Z","system":false,"type":"DiffNote"}]""",
                )
                else -> json("[]")
            }
        }

        val items = api.timeline("tok", ref, 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.Comment>().map { it.body }).containsExactly("On the whole")
    }

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

    @Test
    fun an_issue_is_opened_with_its_labels_as_one_list() = runTest {
        val created = api {
            json("""{"id":101,"iid":11,"project_id":278964,"title":"Crash","description":"","state":"opened","created_at":"2026-10-24T09:43:33.630Z","labels":["bug","triage"]}""", HttpStatusCode.Created)
        }.create("tok", repo, "Crash", "", labels = listOf("bug", "triage")).value()

        assertThat(created.labels.map { it.name }).containsExactly("bug", "triage").inOrder()
        assertThat((requests.single().body as TextContent).text).isEqualTo("""{"title":"Crash","description":"","labels":"bug,triage"}""")
    }

    // The list is the shape gitlab.com answered on 2026-10-07 (one award per person, with its id and who gave it),
    // as are 201 for an award given and 204 for one taken back.
    private fun mergeRequest(title: String, draft: Boolean, reviewers: String = "[]") =
        """{"id":900,"iid":12,"project_id":278964,"title":"$title","state":"opened","created_at":"2026-10-01T10:00:00Z","draft":$draft,"reviewers":$reviewers}"""

    private val bodies get() = requests.map { (it.body as? TextContent)?.text }

    @Test
    fun who_is_asked_to_review_a_merge_request_is_read_with_it() = runTest {
        val api = api { req ->
            if (req.url.encodedPath.endsWith("/award_emoji")) json("[]") else json(mergeRequest("Retry", false, """[{"id":7,"username":"hubot","name":"Hubot"}]"""))
        }

        val details = api.issue(null, IssueRef(repo, 12, isPullRequest = true)).value()

        assertThat(details.pullRequest!!.reviewers.map { it.login }).containsExactly("hubot")
    }

    @Test
    fun reviewers_are_set_whole_by_the_ids_of_the_project_s_members() = runTest {
        val api = api { req ->
            if (req.url.encodedPath.endsWith("/users")) json("""[{"id":7,"username":"hubot"},{"id":8,"username":"octocat"}]""") else json(mergeRequest("Retry", false))
        }

        assertThat(api.setReviewers("tok", IssueRef(repo, 12, isPullRequest = true), listOf("Octocat", "hubot"))).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent()).containsExactly("GET /api/v4/projects/gitlab-org%2Fgitlab/users", "PUT /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/12").inOrder()
        assertThat(bodies.last()).isEqualTo("""{"reviewer_ids":"8,7"}""")
    }

    @Test
    fun a_merge_request_is_made_a_draft_by_its_title_and_ready_again_without_the_mark() = runTest {
        var current = mergeRequest("Retry uploads", false)
        val api = api { req -> if (req.method == HttpMethod.Get) json(current) else json(current) }
        val ref = IssueRef(repo, 12, isPullRequest = true)

        assertThat(api.setDraft("tok", ref, draft = true)).isEqualTo(ForgeResult.Success(Unit))
        current = mergeRequest("[Draft] Retry uploads", true)
        assertThat(api.setDraft("tok", ref, draft = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(bodies.filterNotNull()).containsExactly("""{"title":"Draft: Retry uploads"}""", """{"title":"Retry uploads"}""").inOrder()
    }

    @Test
    fun a_merge_request_already_as_asked_is_left_alone() = runTest {
        val api = api { json(mergeRequest("Draft: Retry uploads", true)) }

        assertThat(api.setDraft("tok", IssueRef(repo, 12, isPullRequest = true), draft = true)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent()).containsExactly("GET /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/12")
    }

    @Test
    fun a_merge_request_is_pointed_at_another_target_branch_and_an_issue_never_is() = runTest {
        val api = api { json("{}") }

        assertThat(api.setBase("tok", IssueRef(repo, 12, isPullRequest = true), "release/2.0")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setBase("tok", IssueRef(repo, 12, isPullRequest = false), "release/2.0")).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))

        assertThat(sent()).containsExactly("PUT /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/12")
        assertThat(bodies.single()).isEqualTo("""{"target_branch":"release/2.0"}""")
    }

    @Test
    fun a_subscription_is_read_from_the_conversation_and_changed_on_its_own_path() = runTest {
        val api = api { req ->
            when {
                req.method == HttpMethod.Get -> json("""{"iid":12,"subscribed":true}""")
                // Already unsubscribed: GitLab says nothing changed, which is what was asked.
                req.url.encodedPath.endsWith("/unsubscribe") -> respond("", HttpStatusCode.NotModified)
                else -> json("""{"iid":12,"subscribed":true}""", HttpStatusCode.Created)
            }
        }
        val mr = IssueRef(repo, 12, isPullRequest = true)

        assertThat(api.isSubscribed("tok", "me", mr).value()).isTrue()
        assertThat(api.setSubscribed("tok", "me", mr, subscribed = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setSubscribed("tok", "me", IssueRef(repo, 3, isPullRequest = false), subscribed = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent()).containsExactly(
            "GET /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/12",
            "POST /api/v4/projects/gitlab-org%2Fgitlab/merge_requests/12/subscribe",
            "POST /api/v4/projects/gitlab-org%2Fgitlab/issues/3/unsubscribe",
        ).inOrder()
    }

    // gitlab-org/gitlab-runner's templates, 2026-10-24: listed by name, the list cut to four, and the Bug one's text.
    private fun captured(name: String) = requireNotNull(javaClass.getResource("/gitlab/captured/$name")).readText()

    @Test
    fun templates_are_listed_by_name_and_one_s_text_is_asked_when_it_is_picked() = runTest {
        val api = api { req -> json(captured(if (req.url.encodedPath.endsWith("/issues/Bug")) "issue_template_bug.json" else "issue_templates.json")) }

        val templates = api.issueTemplates("tok", repo).value()
        assertThat(templates.map { it.name }).containsExactly("AI Agent template", "AI Flow template", "interlock_template", "Bug").inOrder()
        assertThat(templates.all { it.body == null }).isTrue()
        val bug = api.issueTemplate("tok", repo, templates.last()).value()
        // Asked again with its text, nothing more is asked.
        api.issueTemplate("tok", repo, bug)

        assertThat(bug.body).startsWith("## Summary")
        assertThat(sent()).containsExactly(
            "GET /api/v4/projects/gitlab-org%2Fgitlab/templates/issues", "GET /api/v4/projects/gitlab-org%2Fgitlab/templates/issues/Bug",
        ).inOrder()
    }

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

    private suspend fun multipart(request: HttpRequestData): String = String(request.body.toByteArray(), Charsets.ISO_8859_1)

    @Test
    fun a_picture_is_uploaded_to_the_project_and_comes_back_as_markdown_with_its_whole_address() = runTest {
        // The answer gitlab.com gave on 2026-10-07 for "dot one.png": the name tidied, an address relative to the project.
        val api = api {
            json(
                """{"id":1397270798,"alt":"dot_one","url":"/uploads/8a8e0a17979bb00e7eba35cbfad366ee/dot_one.png",
                    "full_path":"/-/project/87334283/uploads/8a8e0a17979bb00e7eba35cbfad366ee/dot_one.png",
                    "markdown":"![dot_one](/uploads/8a8e0a17979bb00e7eba35cbfad366ee/dot_one.png)"}""",
                HttpStatusCode.Created,
            )
        }

        val markdown = api.attach("tok", IssueRef(repo, 10), "dot one.png", "image/png", byteArrayOf(1, 2, 3)).value()

        // Not GitLab's own "/uploads/...": that only means something to GitLab.
        assertThat(markdown).isEqualTo("![dot_one](https://gitlab.com/-/project/87334283/uploads/8a8e0a17979bb00e7eba35cbfad366ee/dot_one.png)")
        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://gitlab.com/api/v4/projects/gitlab-org%2Fgitlab/uploads")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat(request.body.contentType.toString()).startsWith("multipart/form-data")
        val sent = multipart(request)
        assertThat(sent).contains("name=\"file\"")
        assertThat(sent).contains("filename=\"dot one.png\"")
        assertThat(sent).contains("Content-Type: image/png")
        assertThat(sent).contains(String(byteArrayOf(1, 2, 3), Charsets.ISO_8859_1))
    }

    @Test
    fun anyone_who_comments_may_attach_on_gitlab() {
        assertThat(api { json("{}") }.attachments).isEqualTo(fr.arthurbrugiere.forgeline.core.model.AttachmentRule.ANYONE)
    }

    @Test
    fun a_picture_gitlab_refuses_is_a_failure() = runTest {
        val result = api { json("""{"message":"413 Request Entity Too Large"}""", HttpStatusCode.PayloadTooLarge) }
            .attach("tok", IssueRef(repo, 10), "big.png", "image/png", ByteArray(4))

        assertThat(result).isEqualTo(ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(413, "413 Request Entity Too Large")))
    }

    @Test
    fun a_name_that_would_break_the_markdown_is_tidied() = runTest {
        val api = api { json("""{"alt":"a]b[c","full_path":"/-/project/1/uploads/h/a.png"}""", HttpStatusCode.Created) }

        assertThat(api.attach("tok", IssueRef(repo, 10), "a]b[c.png", "image/png", ByteArray(1)).value()).isEqualTo("![abc](https://gitlab.com/-/project/1/uploads/h/a.png)")
    }
}
