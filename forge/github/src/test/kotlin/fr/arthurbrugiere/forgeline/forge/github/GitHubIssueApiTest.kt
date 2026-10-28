package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.HideReason
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueType
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.SavedReply
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant

/** Fixtures: real paperclipai/paperclip issue #5462 and merged PR #14187, captured 2026-09-27. */
class GitHubIssueApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("paperclipai", "paperclip")
    private val issue = IssueRef(repo, 5462)
    private val pr = IssueRef(repo, 14187)

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/issue/$name")) { name }.readText()

    private fun MockRequestHandleScope.json(body: String, extraHeaders: Map<String, String> = emptyMap(), status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(*(extraHeaders + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubIssueApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun parses_an_issue() = runTest {
        val details = api { json(fixture("issue_closed.json")) }.issue(null, issue).value()

        assertThat(details.ref).isEqualTo(issue)
        assertThat(details.state).isEqualTo(IssueState.CLOSED)
        assertThat(details.stateReason).isEqualTo("completed")
        assertThat(details.author?.login).isEqualTo("Alchemist-DevAI")
        assertThat(details.comments).isEqualTo(5)
        assertThat(details.body).isNotEmpty()
        assertThat(details.pullRequest).isNull()
        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/issues/5462")
    }

    @Test
    fun a_pull_request_also_loads_its_branch_and_diff_stats() = runTest {
        val details = api { request ->
            if ("/pulls/" in request.url.encodedPath) json(fixture("pr.json")) else json(fixture("pr_issue.json"))
        }.issue(null, pr).value()

        assertThat(details.state).isEqualTo(IssueState.MERGED)
        val info = details.pullRequest!!
        assertThat(info.isMerged).isTrue()
        assertThat(info.baseRef).isEqualTo("master")
        assertThat(info.headRef).isEqualTo("fix/runner-warm-mode-lease-transition")
        assertThat(listOf(info.additions, info.deletions, info.changedFiles, info.commits)).containsExactly(176, 5, 6, 6).inOrder()
        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/repos/paperclipai/paperclip/issues/14187",
            "/repos/paperclipai/paperclip/pulls/14187",
        )
    }

    @Test
    fun the_issue_timeline_keeps_conversation_and_drops_noise() = runTest {
        val page = api { json(fixture("issue_timeline.json")) }.timeline(null, issue, page = 1).value()

        // 10 raw events: 5 comments, 2 cross-references, 1 close; mention and subscription dropped.
        assertThat(page.items).hasSize(8)
        assertThat(page.items.filterIsInstance<TimelineItem.Comment>()).hasSize(5)
        assertThat(page.items.filterIsInstance<TimelineItem.CrossReferenced>()).hasSize(2)
        assertThat(page.items.last()).isInstanceOf(TimelineItem.StateChanged::class.java)
        assertThat(page.nextPage).isNull()
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/issues/5462/timeline")
        assertThat(url.parameters["per_page"]).isEqualTo("100")
        assertThat(url.parameters["page"]).isEqualTo("1")
    }

    @Test
    fun comments_carry_their_reactions() = runTest {
        val comment = api { json(fixture("issue_timeline.json")) }.timeline(null, issue, 1).value()
            .items.filterIsInstance<TimelineItem.Comment>().first()

        assertThat(comment.id).isEqualTo(4416881112)
        assertThat(comment.reactions).containsExactly(Reaction.THUMBS_UP, 1)
        assertThat(comment.createdAt).isEqualTo(Instant.parse("2026-05-11T01:10:42Z"))
    }

    @Test
    fun cross_references_point_at_their_source() = runTest {
        val reference = api { json(fixture("issue_timeline.json")) }.timeline(null, issue, 1).value()
            .items.filterIsInstance<TimelineItem.CrossReferenced>().first()

        assertThat(reference.source.repo).isEqualTo(repo)
        assertThat(reference.sourceIsPullRequest).isTrue()
        assertThat(reference.sourceTitle).isNotEmpty()
    }

    @Test
    fun the_pr_timeline_has_commits_reviews_and_the_merge() = runTest {
        val items = api { json(fixture("pr_timeline.json")) }.timeline(null, pr, 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.Committed>()).hasSize(6)
        val review = items.filterIsInstance<TimelineItem.Review>().single()
        assertThat(review.state).isEqualTo(ReviewState.COMMENTED)
        assertThat(review.body).isNull()
        assertThat(review.createdAt).isEqualTo(Instant.parse("2026-09-27T02:03:32Z"))
        // GitHub writes a close a second after the merge: only the merge is told, as on GitHub's page (#26).
        assertThat(items.filterIsInstance<TimelineItem.StateChanged>().map { it.change }).containsExactly(StateChange.MERGED)
        // Then the branch deleted, which GitHub doesn't name.
        val deleted = items.filterIsInstance<TimelineItem.Event>().single()
        assertThat(deleted.event).isEqualTo(ConversationEvent.BRANCH_DELETED)
        assertThat(deleted.subject).isNull()
        assertThat(deleted.actor?.login).isEqualTo("cryppadotta")
        val commit = items.filterIsInstance<TimelineItem.Committed>().first()
        assertThat(commit.message).startsWith("fix(runner): acquire reusable leases")
        assertThat(commit.createdAt).isNotNull()
    }

    @Test
    fun a_pull_request_closed_then_reopened_and_merged_keeps_its_close() = runTest {
        val events = """[
          {"event":"closed","actor":{"login":"a"},"created_at":"2026-01-01T00:00:00Z"},
          {"event":"reopened","actor":{"login":"a"},"created_at":"2026-01-02T00:00:00Z"},
          {"event":"merged","actor":{"login":"a"},"created_at":"2026-01-03T00:00:00Z"},
          {"event":"closed","actor":{"login":"a"},"created_at":"2026-01-03T00:00:01Z"},
          {"event":"head_ref_restored","actor":{"login":"a"},"created_at":"2026-01-04T00:00:00Z"}
        ]"""

        val items = api { json(events) }.timeline(null, pr, 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.StateChanged>().map { it.change })
            .containsExactly(StateChange.CLOSED, StateChange.REOPENED, StateChange.MERGED).inOrder()
        assertThat((items.last() as TimelineItem.Event).event).isEqualTo(ConversationEvent.BRANCH_RESTORED)
    }

    @Test
    fun labels_and_renames_are_kept() = runTest {
        val events = """[
          {"event":"labeled","actor":{"login":"a"},"created_at":"2026-01-01T00:00:00Z","label":{"name":"bug","color":"d73a4a"}},
          {"event":"unlabeled","actor":{"login":"a"},"created_at":"2026-01-02T00:00:00Z","label":{"name":"bug","color":"d73a4a"}},
          {"event":"renamed","actor":{"login":"a"},"created_at":"2026-01-03T00:00:00Z","rename":{"from":"Old","to":"New"}},
          {"event":"reopened","actor":{"login":"a"},"created_at":"2026-01-04T00:00:00Z"},
          {"event":"some_future_event","actor":{"login":"a"},"created_at":"2026-01-05T00:00:00Z"}
        ]"""

        val items = api { json(events) }.timeline(null, issue, 1).value().items

        assertThat(items.map { it::class.simpleName }).containsExactly("Labeled", "Labeled", "Renamed", "StateChanged").inOrder()
        assertThat((items[1] as TimelineItem.Labeled).added).isFalse()
    }

    @Test
    fun an_issue_closed_by_a_commit_says_which_and_what_it_said() = runTest {
        // gama-platform/gama#1118 (2026-06-30): the event names the commit only; its message is asked of it.
        val events = """[
          {"event":"closed","actor":{"login":"AlexisDrogoul"},"created_at":"2026-06-30T13:13:16Z","state_reason":"completed",
           "commit_id":"c2a01032c89c63026099bacb814e8786fce8e9a8",
           "commit_url":"https://api.github.com/repos/gama-platform/gama/commits/c2a01032c89c63026099bacb814e8786fce8e9a8"},
          {"event":"reopened","actor":{"login":"a"},"created_at":"2026-07-01T00:00:00Z"},
          {"event":"closed","actor":{"login":"a"},"created_at":"2026-07-02T00:00:00Z"}
        ]"""
        val commit = """{"sha":"c2a01032c89c63026099bacb814e8786fce8e9a8","html_url":"https://github.com/gama-platform/gama/commit/c2a01032c89c63026099bacb814e8786fce8e9a8",
          "commit":{"message":"Detect duplicate default facet definitions\n\nFixes #1118 -- Add validation to check if a default facet has already been defined."}}"""

        val items = api { if ("/commits/" in it.url.encodedPath) json(commit) else json(events) }.timeline(null, issue, 1).value().items

        val closed = items.filterIsInstance<TimelineItem.StateChanged>()
        assertThat(closed[0].commit).isEqualTo(
            ClosingCommit(
                "c2a01032c89c63026099bacb814e8786fce8e9a8",
                "Detect duplicate default facet definitions\n\nFixes #1118 -- Add validation to check if a default facet has already been defined.",
                "https://github.com/gama-platform/gama/commit/c2a01032c89c63026099bacb814e8786fce8e9a8",
            ),
        )
        // Closed by hand: nothing more to ask.
        assertThat(closed[2].commit).isNull()
        assertThat(requests.map { it.url.encodedPath })
            .containsExactly("/repos/paperclipai/paperclip/issues/5462/timeline", "/repos/gama-platform/gama/commits/c2a01032c89c63026099bacb814e8786fce8e9a8")
    }

    @Test
    fun a_closing_commit_that_can_t_be_read_still_names_itself() = runTest {
        // A commit since force-pushed away, or in a repository the reader can't see.
        val events = """[{"event":"closed","actor":{"login":"a"},"created_at":"2026-07-02T00:00:00Z","commit_id":"abc1234def",
          "commit_url":"https://api.github.com/repos/other/repo/commits/abc1234def"}]"""

        val items = api { if ("/commits/" in it.url.encodedPath) json("{}", status = HttpStatusCode.NotFound) else json(events) }.timeline(null, issue, 1).value().items

        assertThat((items.single() as TimelineItem.StateChanged).commit).isEqualTo(ClosingCommit("abc1234def", null, "https://github.com/other/repo/commit/abc1234def"))
    }

    @Test
    fun a_hidden_comment_says_why_and_who_may_hide_one_is_told_by_graphql() = runTest {
        // The REST timeline names each comment's node; GraphQL says which are hidden (gama-platform/gama, 2026-10-25).
        val events = """[
          {"event":"commented","id":1,"node_id":"IC_one","user":{"login":"a"},"body":"Spam here","created_at":"2026-10-20T10:00:00Z"},
          {"event":"commented","id":2,"node_id":"IC_two","user":{"login":"b"},"body":"Useful","created_at":"2026-10-20T11:00:00Z"}
        ]"""
        val nodes = """{"data":{"nodes":[{"id":"IC_one","isMinimized":true,"minimizedReason":"spam","viewerCanMinimize":true},""" +
            """{"id":"IC_two","isMinimized":false,"minimizedReason":null,"viewerCanMinimize":true}]}}"""
        val api = api { if (it.url.encodedPath == "/graphql") json(nodes) else json(events) }

        val comments = api.timeline("tok", issue, 1).value().items.filterIsInstance<TimelineItem.Comment>()

        assertThat(comments.map { it.hidden }).containsExactly(HideReason.SPAM, null).inOrder()
        assertThat(comments.all { it.canHide }).isTrue()
        assertThat(api.hidesComments).isTrue()
        // Signed out, GraphQL can't be asked: nothing is said hidden, and nobody may hide.
        requests.clear()
        val signedOut = api.timeline(null, issue, 1).value().items.filterIsInstance<TimelineItem.Comment>()
        assertThat(signedOut.none { it.canHide || it.hidden != null }).isTrue()
        assertThat(requests.map { it.url.encodedPath }).doesNotContain("/graphql")
    }

    @Test
    fun a_comment_is_hidden_with_its_reason_and_shown_again_by_its_node() = runTest {
        val comment = TimelineItem.Comment(1, null, "Off topic", java.time.Instant.EPOCH, emptyMap(), nodeId = "IC_one", canHide = true)
        val api = api { json("""{"data":{"done":{"clientMutationId":null}}}""") }

        assertThat(api.setCommentHidden("tok", issue, comment, HideReason.OFF_TOPIC)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setCommentHidden("tok", issue, comment, null)).isEqualTo(ForgeResult.Success(Unit))

        val sent = requests.map { (it.body as io.ktor.http.content.TextContent).text }
        assertThat(sent[0]).contains("minimizeComment(input: {subjectId: \$id, classifier: OFF_TOPIC})")
        assertThat(sent[1]).contains("unminimizeComment(input: {subjectId: \$id})")
        assertThat(sent[1]).contains(""""variables":{"id":"IC_one"}""")
    }

    @Test
    fun the_pull_requests_that_will_close_an_issue_are_told_by_graphql() = runTest {
        // gama-platform/gama#999 is closed by #1020 once merged (2026-10-25); one in another repository too, here.
        val answer = """{"data":{"repository":{"issue":{"closedByPullRequestsReferences":{"nodes":[""" +
            """{"number":1020,"title":"Fix horizontal viewport jumping","state":"CLOSED","repository":{"name":"gama","owner":{"login":"gama-platform"}}},""" +
            """{"number":7,"title":"Port the fix","state":"MERGED","repository":{"name":"gama.experimental","owner":{"login":"gama-platform"}}}]}}}}}"""
        val api = api { json(answer) }

        val pulls = api.closingPulls("tok", IssueRef(RepoId("gama-platform", "gama"), 999)).value()

        assertThat(pulls).containsExactly(
            LinkedIssue(IssueRef(RepoId("gama-platform", "gama"), 1020, isPullRequest = true), "Fix horizontal viewport jumping", IssueState.CLOSED),
            LinkedIssue(IssueRef(RepoId("gama-platform", "gama.experimental"), 7, isPullRequest = true), "Port the fix", IssueState.MERGED),
        ).inOrder()
        assertThat((requests.single().body as io.ktor.http.content.TextContent).text).contains("closedByPullRequestsReferences(first: 10, includeClosedPrs: true)")
        // Signed out, GraphQL can't be asked: none are told.
        requests.clear()
        assertThat(api.closingPulls(null, IssueRef(RepoId("gama-platform", "gama"), 999)).value()).isEmpty()
        assertThat(requests).isEmpty()
    }

    // cli/cli#10093 and its sub-issues, as GitHub's REST API answered them (2026-10-25), trimmed to what is read.
    private val epic = IssueRef(RepoId("cli", "cli"), 10093)
    private fun child(id: Long, number: Int, title: String, state: String = "open") =
        """{"id":$id,"number":$number,"title":"$title","state":"$state","repository_url":"https://api.github.com/repos/cli/cli"}"""

    @Test
    fun an_issue_s_sub_issues_and_parent_are_read_together() = runTest {
        val api = api { request ->
            when {
                request.url.encodedPath.endsWith("/sub_issues") -> json("[${child(5552791292, 14510, "Create pull requests from a fork")},${child(5552791303, 14511, "Use the tracked fork", "closed")}]")
                request.url.encodedPath.endsWith("/parent") -> json("{}", status = HttpStatusCode.NotFound)
                else -> json("{}")
            }
        }

        val read = api.subIssues("tok", epic).value()

        assertThat(api.tracksSubIssues).isTrue()
        assertThat(read.parent).isNull()
        assertThat(read.children).containsExactly(
            LinkedIssue(IssueRef(RepoId("cli", "cli"), 14510, isPullRequest = false), "Create pull requests from a fork", IssueState.OPEN),
            LinkedIssue(IssueRef(RepoId("cli", "cli"), 14511, isPullRequest = false), "Use the tracked fork", IssueState.CLOSED),
        ).inOrder()
    }

    @Test
    fun a_sub_issue_names_its_parent() = runTest {
        val api = api { request ->
            if (request.url.encodedPath.endsWith("/parent")) json(child(2743353349, 10093, "Enhance gh pr create")) else json("[]")
        }

        assertThat(api.subIssues("tok", IssueRef(RepoId("cli", "cli"), 14512)).value().parent?.ref?.number).isEqualTo(10093)
    }

    @Test
    fun a_sub_issue_is_added_and_taken_out_by_its_id() = runTest {
        val api = api { request ->
            when {
                request.method == io.ktor.http.HttpMethod.Get -> json(child(5552791308, 14512, "Choose a fork"))
                else -> json("{}", status = HttpStatusCode.Created)
            }
        }
        val sub = IssueRef(RepoId("cli", "cli"), 14512)

        api.addSubIssue("tok", epic, sub).value()
        api.removeSubIssue("tok", epic, sub).value()

        // Each is asked for its id first: the sub-issue is named by it, not by its number.
        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "GET /repos/cli/cli/issues/14512", "POST /repos/cli/cli/issues/10093/sub_issues",
            "GET /repos/cli/cli/issues/14512", "DELETE /repos/cli/cli/issues/10093/sub_issue",
        ).inOrder()
        assertThat((requests[1].body as io.ktor.http.content.TextContent).text).isEqualTo("""{"sub_issue_id":5552791308}""")
        assertThat((requests[3].body as io.ktor.http.content.TextContent).text).isEqualTo("""{"sub_issue_id":5552791308}""")
    }

    @Test
    fun an_issue_says_its_type_the_repository_names_its_types_and_one_is_set_by_name() = runTest {
        // gama-platform/gama's types and gama#1239's, as GitHub answered (2026-10-25), trimmed.
        val types = """{"data":{"repository":{"issueTypes":{"nodes":[{"name":"Task","color":"YELLOW","description":"A specific piece of work"},""" +
            """{"name":"Bug","color":"RED","description":"An unexpected problem or behavior"}]}}}}"""
        val issueJson = """{"number":1239,"title":"Crash","state":"open","created_at":"2026-10-01T10:00:00Z","type":{"id":208386,"name":"Bug","color":"red"}}"""
        val api = api { request ->
            when (request.url.encodedPath) {
                "/graphql" -> json(types)
                else -> json(issueJson)
            }
        }
        val gama = IssueRef(RepoId("gama-platform", "gama"), 1239)

        assertThat(api.typesIssues).isTrue()
        assertThat(api.issue("tok", gama).value().type).isEqualTo(IssueType("Bug", "red"))
        assertThat(api.issueTypes("tok", gama.repo).value()).containsExactly(
            IssueType("Task", "YELLOW", "A specific piece of work"), IssueType("Bug", "RED", "An unexpected problem or behavior"),
        ).inOrder()
        requests.clear()
        api.setIssueType("tok", gama, "Task").value()
        api.setIssueType("tok", gama, null).value()

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PATCH /repos/gama-platform/gama/issues/1239", "PATCH /repos/gama-platform/gama/issues/1239")
        assertThat(requests.map { (it.body as io.ktor.http.content.TextContent).text }).containsExactly("""{"type":"Task"}""", """{"type":null}""").inOrder()
    }

    @Test
    fun the_reader_s_saved_replies_are_read_by_graphql() = runTest {
        val api = api { json("""{"data":{"viewer":{"savedReplies":{"nodes":[{"title":"Duplicate","body":"Thanks! This is a duplicate of #%."},{"title":"Needs repro","body":"Could you share steps?"}]}}}}""") }

        assertThat(api.savedReplies("tok").value()).containsExactly(
            SavedReply("Duplicate", "Thanks! This is a duplicate of #%."), SavedReply("Needs repro", "Could you share steps?"),
        ).inOrder()
        assertThat((requests.single().body as io.ktor.http.content.TextContent).text).contains("viewer { savedReplies(first: 50)")
    }

    @Test
    fun a_next_page_is_announced_by_the_link_header() = runTest {
        val link = """<https://api.github.com/repositories/1/issues/5462/timeline?per_page=100&page=2>; rel="next", """ +
            """<https://api.github.com/repositories/1/issues/5462/timeline?per_page=100&page=4>; rel="last""""

        val page = api { json("[]", mapOf("Link" to link)) }.timeline(null, issue, 1).value()

        assertThat(page.nextPage).isEqualTo(2)
        // The last one too: the pages in between can be asked together.
        assertThat(page.lastPage).isEqualTo(4)
    }

    @Test
    fun a_missing_issue_is_a_404() = runTest {
        val result = api { json("""{"message":"Not Found"}""", status = HttpStatusCode.NotFound) }.issue(null, issue)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun a_pull_requests_title_takes_one_request() = runTest {
        val title = api { json(fixture("pr_issue.json")) }.title(null, pr).value()

        assertThat(title).isNotEmpty()
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/issues/14187")
    }

    // The answer below follows GitHub's documented shape for a created comment: posting can't be captured from a
    // real account in tests.
    private val created = """{"id":4242,"user":{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/583231?v=4"},
        "body":"Thanks, **fixed** in 1.2","created_at":"2026-10-01T09:30:00Z","reactions":{"+1":0,"-1":0,"laugh":0,"hooray":0,"confused":0,"heart":0,"rocket":0,"eyes":0}}"""

    @Test
    fun a_comment_is_posted_to_the_conversation_and_comes_back_as_kept() = runTest {
        val comment = api { json(created, status = HttpStatusCode.Created) }.comment("tok", issue, "Thanks, **fixed** in 1.2").value()

        assertThat(comment).isEqualTo(
            TimelineItem.Comment(
                4242, fr.arthurbrugiere.forgeline.core.model.ForgeUser("octocat", null, "https://avatars.githubusercontent.com/u/583231?v=4"),
                "Thanks, **fixed** in 1.2", Instant.parse("2026-10-01T09:30:00Z"), emptyMap(),
            ),
        )
        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/issues/5462/comments")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"body":"Thanks, **fixed** in 1.2"}""")
    }

    @Test
    fun a_comment_the_forge_refuses_is_a_failure() = runTest {
        // A locked conversation, or a sign-in that can't write there.
        val result = api { json("""{"message":"Unable to create comment because issue is locked."}""", status = HttpStatusCode.Forbidden) }
            .comment("tok", issue, "Hello")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Unable to create comment because issue is locked.")))
    }

    // The answer below follows GitHub's documented shape for a created issue: opening one can't be captured from a
    // real account in tests.
    private val opened = """{"number":5501,"title":"Crash when the list is empty","body":"Steps:\n1. Open it","state":"open",
        "user":{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/583231?v=4"},"labels":[],"comments":0,
        "created_at":"2026-10-02T02:20:00Z","closed_at":null}"""

    @Test
    fun an_issue_is_opened_in_the_repository_and_comes_back_numbered() = runTest {
        val created = api { json(opened, status = HttpStatusCode.Created) }.create("tok", repo, "Crash when the list is empty", "Steps:\n1. Open it").value()

        assertThat(created.ref).isEqualTo(IssueRef(repo, 5501))
        assertThat(created.title).isEqualTo("Crash when the list is empty")
        assertThat(created.body).isEqualTo("Steps:\n1. Open it")
        assertThat(created.state).isEqualTo(IssueState.OPEN)
        assertThat(created.author?.login).isEqualTo("octocat")
        assertThat(created.createdAt).isEqualTo(Instant.parse("2026-10-02T02:20:00Z"))
        assertThat(created.pullRequest).isNull()
        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/issues")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"title":"Crash when the list is empty","body":"Steps:\n1. Open it"}""")
    }

    @Test
    fun an_issue_opened_from_a_template_asks_for_its_labels() = runTest {
        api { json(opened, status = HttpStatusCode.Created) }.create("tok", repo, "[ICE]: crash", "", labels = listOf("I-ICE", "C-bug"))

        assertThat((requests.single().body as TextContent).text).isEqualTo("""{"title":"[ICE]: crash","body":"","labels":["I-ICE","C-bug"]}""")
    }

    @Test
    fun an_issue_the_forge_refuses_is_a_failure() = runTest {
        // What GitHub answers when a repository's issues are switched off.
        val result = api { json("""{"message":"Issues are disabled for this repo"}""", status = HttpStatusCode.Gone) }.create("tok", repo, "Hello", "")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(410, "Issues are disabled for this repo")))
    }

    @Test
    fun closing_and_reopening_change_the_state_of_the_issue_or_pull_request() = runTest {
        // The answer is the issue as GitHub keeps it; only its success is read.
        val changing = api { json(opened) }

        assertThat(changing.setOpen("tok", issue, open = false)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(changing.setOpen("tok", pr, open = true)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { it.method }).containsExactly(HttpMethod.Patch, HttpMethod.Patch)
        // A pull request goes through the issue endpoint too.
        assertThat(requests.map { it.url.toString() }).containsExactly(
            "https://api.github.com/repos/paperclipai/paperclip/issues/5462",
            "https://api.github.com/repos/paperclipai/paperclip/issues/14187",
        ).inOrder()
        assertThat(requests.map { (it.body as TextContent).text }).containsExactly("""{"state":"closed"}""", """{"state":"open"}""").inOrder()
        assertThat(requests.first().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun a_state_change_the_forge_refuses_is_a_failure() = runTest {
        val result = api { json("""{"message":"Must have admin rights to Repository."}""", status = HttpStatusCode.Forbidden) }.setOpen("tok", issue, open = false)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Must have admin rights to Repository.")))
    }

    @Test
    fun the_forge_s_permissions_say_what_the_signed_in_user_may_do() = runTest {
        fun permissions(admin: Boolean = false, maintain: Boolean = false, push: Boolean = false, triage: Boolean = false) =
            """{"name":"paperclip","permissions":{"admin":$admin,"maintain":$maintain,"push":$push,"triage":$triage,"pull":true}}"""

        assertThat(api { json(permissions(admin = true, push = true, triage = true)) }.access("tok", repo).value().access).isEqualTo(RepoAccess.ADMIN)
        assertThat(api { json(permissions(maintain = true, triage = true)) }.access("tok", repo).value().access).isEqualTo(RepoAccess.WRITE)
        assertThat(api { json(permissions(push = true, triage = true)) }.access("tok", repo).value().access).isEqualTo(RepoAccess.WRITE)
        assertThat(api { json(permissions(triage = true)) }.access("tok", repo).value().access).isEqualTo(RepoAccess.TRIAGE)
        assertThat(api { json(permissions()) }.access("tok", repo).value().access).isEqualTo(RepoAccess.NONE)
        assertThat(requests.first().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip")
        assertThat(requests.first().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun a_repository_that_doesn_t_state_permissions_grants_nothing() = runTest {
        // Read without the right to know, GitHub leaves the permissions out.
        assertThat(api { json("""{"name":"paperclip"}""") }.access("tok", repo).value().access).isEqualTo(RepoAccess.NONE)
    }

    // Fixtures: netbirdio/netbird issue #5434, locked then converted to a discussion, captured 2026-10-02.
    private val locked = IssueRef(RepoId("netbirdio", "netbird"), 5434)

    @Test
    fun a_locked_issue_says_so() = runTest {
        val details = api { json(fixture("locked_issue.json")) }.issue(null, locked).value()

        assertThat(details.isLocked).isTrue()
        assertThat(details.state).isEqualTo(IssueState.CLOSED)
        assertThat(details.assignees).isEmpty()
        assertThat(details.milestone).isNull()
        // The issues captured earlier are not.
        assertThat(api { json(fixture("issue_closed.json")) }.issue(null, issue).value().isLocked).isFalse()
    }

    @Test
    fun locking_and_converting_to_a_discussion_are_part_of_the_conversation() = runTest {
        val events = api { json(fixture("locked_timeline.json")) }.timeline(null, locked, 1).value().items.filterIsInstance<TimelineItem.Event>()

        assertThat(events.map { it.event }).containsExactly(ConversationEvent.LOCKED, ConversationEvent.CONVERTED_TO_DISCUSSION).inOrder()
        assertThat(events.map { it.actor?.login }).containsExactly("netbirdio", "thomashacker").inOrder()
        assertThat(events.first().createdAt).isEqualTo(Instant.parse("2026-10-01T16:44:08Z"))
    }

    @Test
    fun who_it_is_assigned_to_and_its_milestone_are_read_from_the_issue() = runTest {
        // GitHub's documented shape, added to a captured issue: no public issue to capture holds both for long.
        val triaged = JsonObject(
            Json.parseToJsonElement(fixture("issue_closed.json")).jsonObject + mapOf(
                "assignees" to Json.parseToJsonElement("""[{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/583231?v=4"},{"login":"hubot"}]"""),
                "milestone" to Json.parseToJsonElement("""{"number":4,"title":"2026.10"}"""),
            ),
        ).toString()
        val details = api { json(triaged) }.issue(null, issue).value()

        assertThat(details.assignees.map { it.login }).containsExactly("octocat", "hubot").inOrder()
        assertThat(details.milestone).isEqualTo(Milestone(4, "2026.10"))
    }

    @Test
    fun triage_events_name_who_and_what() = runTest {
        // GitHub's documented shapes for events the captured timelines don't hold.
        fun event(name: String, extra: String = "") =
            """{"event":"$name","actor":{"login":"maintainer"},"created_at":"2026-10-02T03:00:00Z"$extra}"""
        val timeline = listOf(
            event("assigned", ""","assignee":{"login":"octocat"}"""),
            event("unassigned", ""","assignee":{"login":"octocat"}"""),
            event("milestoned", ""","milestone":{"title":"2026.10"}"""),
            event("demilestoned", ""","milestone":{"title":"2026.10"}"""),
            event("pinned"), event("unpinned"), event("unlocked"), event("transferred"),
        ).joinToString(",", "[", "]")

        val events = api { json(timeline) }.timeline(null, issue, 1).value().items.filterIsInstance<TimelineItem.Event>()

        assertThat(events.map { it.event }).containsExactly(
            ConversationEvent.ASSIGNED, ConversationEvent.UNASSIGNED, ConversationEvent.MILESTONED, ConversationEvent.DEMILESTONED,
            ConversationEvent.PINNED, ConversationEvent.UNPINNED, ConversationEvent.UNLOCKED, ConversationEvent.TRANSFERRED,
        ).inOrder()
        assertThat(events.map { it.subject }).containsExactly("octocat", "octocat", "2026.10", "2026.10", null, null, null, null).inOrder()
        assertThat(events.map { it.actor?.login }.distinct()).containsExactly("maintainer")
    }

    private val bodies get() = requests.map { (it.body as? TextContent)?.text }

    @Test
    fun github_keeps_no_due_date_time_spent_or_dependencies() = runTest {
        val api = api { error("no request expected") }

        assertThat(api.actions).containsExactlyElementsIn(
            ConversationAction.entries - setOf(ConversationAction.DUE_DATE, ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES),
        )
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)
        assertThat(api.setDueDate("tok", issue, java.time.LocalDate.parse("2026-10-10"))).isEqualTo(unsupported)
        assertThat(api.timeTracking("tok", issue)).isEqualTo(unsupported)
        assertThat(api.setTimerRunning("tok", issue, true)).isEqualTo(unsupported)
        assertThat(api.addTime("tok", issue, 60)).isEqualTo(unsupported)
        assertThat(api.dependencies("tok", issue)).isEqualTo(unsupported)
        assertThat(api.addDependency("tok", issue, pr)).isEqualTo(unsupported)
        assertThat(api.removeDependency("tok", issue, pr)).isEqualTo(unsupported)
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_github_repository_switches_nothing_off() = runTest {
        assertThat(api { json("""{"name":"paperclip","permissions":{"admin":true}}""") }.access("tok", repo).value().switchedOff).isEmpty()
    }

    @Test
    fun closing_can_say_why_and_reopening_never_does() = runTest {
        val changing = api { json(opened) }

        changing.setOpen("tok", issue, open = false, reason = CloseReason.NOT_PLANNED)
        changing.setOpen("tok", issue, open = false, reason = CloseReason.DUPLICATE)
        changing.setOpen("tok", issue, open = true, reason = CloseReason.COMPLETED)

        assertThat(bodies).containsExactly(
            """{"state":"closed","state_reason":"not_planned"}""", """{"state":"closed","state_reason":"duplicate"}""", """{"state":"open"}""",
        ).inOrder()
    }

    @Test
    fun a_label_is_made_with_its_colour_as_github_writes_it() = runTest {
        val made = api { json("""{"id":9,"name":"needs repro","color":"0e8a16"}""", status = HttpStatusCode.Created) }
            .createLabel("tok", repo, "needs repro", "#0e8a16").value()

        assertThat(made).isEqualTo(Label("needs repro", "0e8a16"))
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/labels")
        // Without its hash.
        assertThat((requests.single().body as io.ktor.http.content.TextContent).text).isEqualTo("""{"name":"needs repro","color":"0e8a16"}""")
    }

    @Test
    fun the_repository_s_labels_assignable_people_and_open_milestones_are_listed() = runTest {
        val listing = api { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "labels" -> json("""[{"name":"bug","color":"d73a4a"},{"name":"question","color":"d876e3"}]""")
                "assignees" -> json("""[{"login":"octocat","avatar_url":"https://avatars.githubusercontent.com/u/583231?v=4"}]""")
                else -> json("""[{"number":4,"title":"2026.10","state":"open"}]""")
            }
        }

        assertThat(listing.labels("tok", repo).value()).containsExactly(Label("bug", "d73a4a"), Label("question", "d876e3")).inOrder()
        assertThat(listing.assignable("tok", repo).value().map { it.login }).containsExactly("octocat")
        assertThat(listing.milestones("tok", repo).value()).containsExactly(Milestone(4, "2026.10"))

        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/repos/paperclipai/paperclip/labels", "/repos/paperclipai/paperclip/assignees", "/repos/paperclipai/paperclip/milestones",
        ).inOrder()
        assertThat(requests.map { it.url.parameters["per_page"] }.distinct()).containsExactly("100")
        assertThat(requests.last().url.parameters["state"]).isEqualTo("open")
        assertThat(requests.map { it.headers[HttpHeaders.Authorization] }.distinct()).containsExactly("Bearer tok")
    }

    @Test
    fun labels_assignees_and_milestone_are_set_whole() = runTest {
        val changing = api { json(opened) }

        assertThat(changing.setLabels("tok", issue, listOf("bug", "question"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(changing.setAssignees("tok", issue, listOf("octocat"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(changing.setMilestone("tok", issue, Milestone(4, "2026.10"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(changing.setMilestone("tok", issue, null)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "PUT /repos/paperclipai/paperclip/issues/5462/labels",
            "PATCH /repos/paperclipai/paperclip/issues/5462",
            "PATCH /repos/paperclipai/paperclip/issues/5462",
            "PATCH /repos/paperclipai/paperclip/issues/5462",
        ).inOrder()
        assertThat(bodies).containsExactly(
            """{"labels":["bug","question"]}""", """{"assignees":["octocat"]}""", """{"milestone":4}""", """{"milestone":null}""",
        ).inOrder()
    }

    /** The captured pull request, asked of two people: GitHub lists them as it lists any user. */
    private val askedOfTwo = fixture("pr.json").replace(
        "\"requested_reviewers\":[]",
        "\"requested_reviewers\":[{\"login\":\"octocat\",\"id\":1},{\"login\":\"hubot\",\"id\":2}]",
    )

    @Test
    fun who_is_asked_to_review_a_pull_request_is_read_with_it() = runTest {
        val details = api { request ->
            if ("/pulls/" in request.url.encodedPath) json(askedOfTwo) else json(fixture("pr_issue.json"))
        }.issue(null, pr).value()

        assertThat(details.pullRequest!!.reviewers.map { it.login }).containsExactly("octocat", "hubot").inOrder()
    }

    @Test
    fun reviewers_are_asked_and_let_off_by_who_changes_only() = runTest {
        val changing = api { request -> if (request.method == HttpMethod.Get) json(askedOfTwo) else json("{}", status = HttpStatusCode.Created) }

        // Hubot stays asked, octocat is let off, monalisa is asked.
        assertThat(changing.setReviewers("tok", pr, listOf("Hubot", "monalisa"))).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "GET /repos/paperclipai/paperclip/pulls/14187",
            "POST /repos/paperclipai/paperclip/pulls/14187/requested_reviewers",
            "DELETE /repos/paperclipai/paperclip/pulls/14187/requested_reviewers",
        ).inOrder()
        assertThat(bodies.drop(1)).containsExactly("""{"reviewers":["monalisa"]}""", """{"reviewers":["octocat"]}""").inOrder()
    }

    @Test
    fun asking_the_same_reviewers_again_asks_nothing_more() = runTest {
        val changing = api { json(askedOfTwo) }

        assertThat(changing.setReviewers("tok", pr, listOf("octocat", "hubot"))).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests).hasSize(1)
    }

    @Test
    fun a_reviewer_github_refuses_is_a_failure() = runTest {
        val changing = api { request ->
            if (request.method == HttpMethod.Get) json(askedOfTwo) else json("""{"message":"Reviews may only be requested from collaborators."}""", status = HttpStatusCode.UnprocessableEntity)
        }

        val result = changing.setReviewers("tok", pr, listOf("stranger"))

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.Http::class.java)
    }

    @Test
    fun a_pull_request_becomes_a_draft_and_ready_again_by_its_node() = runTest {
        val acting = api { request ->
            if (request.url.encodedPath == "/graphql") json("""{"data":{"done":{"clientMutationId":null}}}""") else json(fixture("pr.json"))
        }

        assertThat(acting.setDraft("tok", pr, draft = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.setDraft("tok", pr, draft = false)).isEqualTo(ForgeResult.Success(Unit))

        val mutations = bodies.filterNotNull().filter { "mutation" in it }
        assertThat(mutations[0]).contains("convertPullRequestToDraft(input: {pullRequestId: \$id})")
        assertThat(mutations[1]).contains("markPullRequestReadyForReview(input: {pullRequestId: \$id})")
        mutations.forEach { assertThat(it).contains(""""variables":{"id":"PR_kwDORclPyM8AAAABFTkt9A"}""") }
    }

    @Test
    fun a_conversation_is_locked_and_unlocked() = runTest {
        val changing = api { respond("", HttpStatusCode.NoContent) }

        assertThat(changing.setLocked("tok", issue, locked = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(changing.setLocked("tok", issue, locked = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "PUT /repos/paperclipai/paperclip/issues/5462/lock", "DELETE /repos/paperclipai/paperclip/issues/5462/lock",
        ).inOrder()
    }

    // The answers below follow GitHub's GraphQL schema: pinning, transferring and deleting can't be captured from a
    // real account in tests.
    private fun graphQl(answer: (body: String) -> String) = api { request ->
        check(request.url.encodedPath == "/graphql" && request.method == HttpMethod.Post) { request.url }
        json(answer((request.body as TextContent).text))
    }

    private val node = """{"data":{"repository":{"issue":{"id":"I_abc","isPinned":true}}}}"""

    @Test
    fun whether_an_issue_is_pinned_is_asked_by_its_number() = runTest {
        assertThat(graphQl { node }.isPinned("tok", issue).value()).isTrue()

        assertThat(bodies.single()).contains(""""variables":{"owner":"paperclipai","name":"paperclip","number":5462}""")
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun pinning_unpinning_and_deleting_name_the_issue_by_its_node() = runTest {
        val acting = graphQl { body -> if ("mutation" in body) """{"data":{"done":{"clientMutationId":null}}}""" else node }

        assertThat(acting.setPinned("tok", issue, pinned = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.setPinned("tok", issue, pinned = false)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.delete("tok", issue)).isEqualTo(ForgeResult.Success(Unit))

        val mutations = bodies.filterNotNull().filter { "mutation" in it }
        assertThat(mutations).hasSize(3)
        assertThat(mutations[0]).contains("pinIssue(input: {issueId: \$id})")
        assertThat(mutations[1]).contains("unpinIssue(input: {issueId: \$id})")
        assertThat(mutations[2]).contains("deleteIssue(input: {issueId: \$id})")
        mutations.forEach { assertThat(it).contains(""""variables":{"id":"I_abc"}""") }
    }

    // GitHub's answer for gama-platform/gama#1265, 2026-10-24: a pull request, found by its number as an issue is.
    private val subscribable = """{"data":{"repository":{"issueOrPullRequest":{"id":"PR_kwDOLUle7M8AAAABKK38FQ","viewerSubscription":"UNSUBSCRIBED"}}}}"""

    @Test
    fun a_subscription_is_read_and_changed_on_an_issue_or_a_pull_request_alike() = runTest {
        val acting = graphQl { body -> if ("mutation" in body) """{"data":{"updateSubscription":{"clientMutationId":null}}}""" else subscribable }

        assertThat(acting.isSubscribed("tok", "me", issue).value()).isFalse()
        assertThat(acting.setSubscribed("tok", "me", issue, subscribed = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.setSubscribed("tok", "me", issue, subscribed = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(bodies.first()).contains("issueOrPullRequest(number: \$number)")
        val mutations = bodies.filterNotNull().filter { "mutation" in it }
        assertThat(mutations[0]).contains(""""variables":{"id":"PR_kwDOLUle7M8AAAABKK38FQ","state":"SUBSCRIBED"}""")
        assertThat(mutations[1]).contains(""""variables":{"id":"PR_kwDOLUle7M8AAAABKK38FQ","state":"UNSUBSCRIBED"}""")
    }

    @Test
    fun the_branch_a_pull_request_goes_into_is_changed_on_the_pull_request() = runTest {
        val changing = api { json("{}") }

        assertThat(changing.setBase("tok", IssueRef(repo, 5462, isPullRequest = true), "release/2.0")).isEqualTo(ForgeResult.Success(Unit))

        assertThat("${requests.single().method.value} ${requests.single().url.encodedPath}").isEqualTo("PATCH /repos/paperclipai/paperclip/pulls/5462")
        assertThat(bodies.single()).isEqualTo("""{"base":"release/2.0"}""")
    }

    /**
     * Templates as GitHub answers them: [markdown] from GraphQL, and the files of `.github/ISSUE_TEMPLATE` by path from
     * the contents API, where a path not in [files] answers 404 (most repositories have no such folder).
     */
    private fun templates(markdown: String, files: Map<String, String> = emptyMap()) = api { request ->
        val path = request.url.encodedPath
        when {
            path == "/graphql" -> json(markdown)
            path.startsWith("/repos/paperclipai/paperclip/contents/") -> files[path.removePrefix("/repos/paperclipai/paperclip/contents/")]?.let { json(it) }
                ?: json("""{"message":"Not Found"}""", status = HttpStatusCode.NotFound)
            else -> error("unexpected $path")
        }
    }

    private val graphQlBodies get() = requests.filter { it.url.encodedPath == "/graphql" }.map { (it.body as TextContent).text }

    @Test
    fun a_repository_s_markdown_templates_come_with_their_text() = runTest {
        // GitHub's answer for gama-platform/gama, 2026-10-24.
        val captured = requireNotNull(javaClass.getResource("/github/issue_templates.json")).readText()

        val templates = templates(captured).issueTemplates("tok", issue.repo).value()

        assertThat(templates.map { it.name }).containsExactly("Report a problem in using GAMA", "Feature or enhancement request").inOrder()
        assertThat(templates[0].key).isEqualTo("bug_report.md")
        assertThat(templates[0].about).startsWith("Create a report to help us eliminate it.")
        assertThat(templates[0].title).isEmpty()
        assertThat(templates[0].body).startsWith("**Describe the bug**")
        assertThat(templates[0].labels).isEmpty()
        assertThat(graphQlBodies.single()).contains(""""variables":{"owner":"paperclipai","name":"paperclip"}""")
        // No folder of forms beside them.
        assertThat(requests.map { it.url.encodedPath }).contains("/repos/paperclipai/paperclip/contents/.github/ISSUE_TEMPLATE")
    }

    @Test
    fun a_template_says_which_labels_its_issues_wear() = runTest {
        // GitHub's answer for rust-lang/rust, 2026-10-24, cut to two templates: one with labels, one without.
        val captured = requireNotNull(javaClass.getResource("/github/issue_templates_labels.json")).readText()

        val templates = templates(captured).issueTemplates("tok", issue.repo).value()

        assertThat(templates.map { it.key }).containsExactly("ice.md", "tracking_issue_future.md").inOrder()
        assertThat(templates[0].labels).containsExactly("I-ICE", "T-compiler", "C-bug").inOrder()
        assertThat(templates[0].title).isEqualTo("[ICE]: ")
        assertThat(templates[1].labels).isEmpty()
        assertThat(graphQlBodies.single()).contains("labels(first: 20) { nodes { name } }")
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResource("/github/$name")) { name }.readText()

    @Test
    fun a_repository_s_issue_forms_come_beside_its_markdown_templates_in_file_order() = runTest {
        // GitHub's answers for facebook/react, 2026-10-25: one Markdown template in GraphQL, two forms and config.yml in the folder.
        val templates = templates(
            resource("issue_templates_react.json"),
            mapOf(
                ".github/ISSUE_TEMPLATE" to resource("issue_template_folder_react.json"),
                ".github/ISSUE_TEMPLATE/compiler_bug_report.yml" to resource("issue_form_compiler_bug_report.json"),
                ".github/ISSUE_TEMPLATE/devtools_bug_report.yml" to resource("issue_form_devtools_bug_report.json"),
            ),
        ).issueTemplates("tok", issue.repo).value()

        assertThat(templates.map { it.key }).containsExactly("bug_report.md", "compiler_bug_report.yml", "devtools_bug_report.yml").inOrder()
        val compiler = templates[1]
        assertThat(compiler.name).isEqualTo("⚛️ ✨ Compiler bug report")
        assertThat(compiler.title).isEqualTo("[Compiler Bug]: ")
        assertThat(compiler.labels).containsExactly("Component: Optimizing Compiler", "Type: Bug", "Status: Unconfirmed").inOrder()
        assertThat(compiler.body).startsWith("### What kind of issue is this?\n\n")
        assertThat(compiler.body).contains("- [ ] React Compiler core (the JS output is incorrect, or your app works incorrectly after optimization)\n")
        assertThat(compiler.body).contains("### How often does this bug happen?\n\n<!-- Following the repro steps above, how easily are you able to reproduce this bug? -->\n- Every time\n")
        assertThat(templates[2].labels).contains("Component: Developer Tools")
        // config.yml isn't asked for: it is no form.
        assertThat(requests.map { it.url.encodedPath }).doesNotContain("/repos/paperclipai/paperclip/contents/.github/ISSUE_TEMPLATE/config.yml")
    }

    @Test
    fun a_repository_s_config_turns_blank_issues_off_and_sends_people_elsewhere() = runTest {
        // facebook/react's config.yml through the contents API, 2026-10-25.
        val chooser = templates("{}", mapOf(".github/ISSUE_TEMPLATE/config.yml" to resource("issue_config_react.json"))).issueChooser("tok", issue.repo).value()

        assertThat(chooser.blankIssues).isFalse()
        assertThat(chooser.links.map { it.name }).containsExactly("📃 Documentation Issue", "🤔 Questions and Help").inOrder()
        assertThat(chooser.links[1].url).isEqualTo("https://reactjs.org/community/support.html")
    }

    @Test
    fun a_repository_without_a_config_allows_blank_issues() = runTest {
        val chooser = templates("{}").issueChooser("tok", issue.repo).value()

        assertThat(chooser.blankIssues).isTrue()
        assertThat(chooser.links).isEmpty()
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/contents/.github/ISSUE_TEMPLATE/config.yml")
    }

    @Test
    fun a_form_that_can_t_be_read_is_left_out_and_the_rest_still_come() = runTest {
        val templates = templates(
            resource("issue_templates_react.json"),
            mapOf(
                ".github/ISSUE_TEMPLATE" to resource("issue_template_folder_react.json"),
                ".github/ISSUE_TEMPLATE/devtools_bug_report.yml" to resource("issue_form_devtools_bug_report.json"),
            ),
        ).issueTemplates("tok", issue.repo).value()

        assertThat(templates.map { it.key }).containsExactly("bug_report.md", "devtools_bug_report.yml").inOrder()
    }

    @Test
    fun a_transferred_issue_says_where_it_went() = runTest {
        val acting = graphQl { body ->
            if ("transferIssue" in body) {
                """{"data":{"transferIssue":{"issue":{"number":12,"repository":{"name":"docs","owner":{"login":"paperclipai"}}}}}}"""
            } else {
                """{"data":{"from":{"issue":{"id":"I_abc"}},"to":{"id":"R_xyz"}}}"""
            }
        }

        val moved = acting.transfer("tok", issue, RepoId("paperclipai", "docs")).value()

        assertThat(moved).isEqualTo(IssueRef(RepoId("paperclipai", "docs"), 12))
        assertThat(bodies[0]).contains(""""variables":{"owner":"paperclipai","name":"paperclip","number":5462,"toOwner":"paperclipai","toName":"docs"}""")
        assertThat(bodies[1]).contains(""""variables":{"issue":"I_abc","repository":"R_xyz"}""")
    }

    @Test
    fun transferring_to_a_repository_github_doesn_t_know_is_not_found_and_changes_nothing() = runTest {
        val acting = graphQl { """{"data":{"from":{"issue":{"id":"I_abc"}},"to":null},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to a Repository with the name 'paperclipai/nope'."}]}""" }

        val result = acting.transfer("tok", issue, RepoId("paperclipai", "nope"))

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Could not resolve to a Repository with the name 'paperclipai/nope'.")))
        assertThat(requests).hasSize(1)
    }

    @Test
    fun what_graphql_refuses_is_a_failure_though_it_answers_200() = runTest {
        val acting = graphQl { body ->
            if ("mutation" in body) """{"data":{"deleteIssue":null},"errors":[{"type":"FORBIDDEN","message":"octocat does not have the correct permissions to execute `DeleteIssue`"}]}""" else node
        }

        assertThat(acting.delete("tok", issue))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "octocat does not have the correct permissions to execute `DeleteIssue`")))
    }

    @Test
    fun a_pull_request_has_no_issue_node_to_pin() = runTest {
        val acting = graphQl { """{"data":{"repository":{"issue":null}},"errors":[{"type":"NOT_FOUND","message":"Could not resolve to an Issue with the number of 14187."}]}""" }

        assertThat(acting.setPinned("tok", pr, pinned = true)).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Could not resolve to an Issue with the number of 14187.")))
    }

    @Test
    fun a_comment_is_rewritten_by_its_id() = runTest {
        // Comments are numbered across the repository: the conversation's number is not in the address.
        api { json(created) }.editComment("tok", issue, 4242, "Thanks, fixed in **1.3**").value()

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Patch)
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/issues/comments/4242")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"body":"Thanks, fixed in **1.3**"}""")
    }

    @Test
    fun a_comment_is_deleted_by_its_id() = runTest {
        api { respond("", HttpStatusCode.NoContent) }.deleteComment("tok", issue, 4242).value()

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Delete)
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/issues/comments/4242")
    }

    @Test
    fun rewriting_or_deleting_someone_elses_comment_is_a_failure() = runTest {
        val refused = api { json("""{"message":"Must have admin rights to Repository."}""", status = HttpStatusCode.Forbidden) }

        assertThat(refused.editComment("tok", issue, 4242, "Mine now")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Must have admin rights to Repository.")))
        assertThat(refused.deleteComment("tok", issue, 4242)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Must have admin rights to Repository.")))
    }

    @Test
    fun an_issue_or_a_pull_request_gets_a_new_title_and_text_in_one_call() = runTest {
        api { json(opened) }.edit("tok", pr, "Crash when the list is empty", "").value()

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Patch)
        assertThat(request.url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/issues/14187")
        // An emptied description is sent empty: left out, the forge would keep the old one.
        assertThat((request.body as TextContent).text).isEqualTo("""{"title":"Crash when the list is empty","body":""}""")
    }

    // GitHub's documented answers: 201 for a reaction it made, 200 with the one that was already there.
    private fun reacting(alreadyThere: Boolean) = api { request ->
        when {
            request.method == HttpMethod.Post -> json("""{"id":77,"content":"+1"}""", status = if (alreadyThere) HttpStatusCode.OK else HttpStatusCode.Created)
            request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
            else -> json("""{"id":4242,"body":"x","reactions":{"+1":${if (alreadyThere) 2 else 3},"heart":1,"laugh":0}}""")
        }
    }

    @Test
    fun a_reaction_not_given_yet_is_given_and_the_counts_read_again() = runTest {
        val counts = reacting(alreadyThere = false).toggleReaction("tok", "me", issue, 4242, Reaction.THUMBS_UP).value()

        assertThat(counts).containsExactly(Reaction.THUMBS_UP, 3, Reaction.HEART, 1)
        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "POST /repos/paperclipai/paperclip/issues/comments/4242/reactions",
            "GET /repos/paperclipai/paperclip/issues/comments/4242",
        ).inOrder()
        assertThat((requests.first().body as TextContent).text).isEqualTo("""{"content":"+1"}""")
        assertThat(requests.first().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun a_reaction_already_given_is_taken_back_by_its_id() = runTest {
        val counts = reacting(alreadyThere = true).toggleReaction("tok", "me", issue, 4242, Reaction.THUMBS_UP).value()

        assertThat(counts).containsExactly(Reaction.THUMBS_UP, 2, Reaction.HEART, 1)
        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "POST /repos/paperclipai/paperclip/issues/comments/4242/reactions",
            "DELETE /repos/paperclipai/paperclip/issues/comments/4242/reactions/77",
            "GET /repos/paperclipai/paperclip/issues/comments/4242",
        ).inOrder()
    }

    @Test
    fun a_reaction_to_the_conversation_itself_goes_to_the_issue() = runTest {
        reacting(alreadyThere = true).toggleReaction("tok", "me", pr, null, Reaction.HOORAY).value()

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "POST /repos/paperclipai/paperclip/issues/14187/reactions",
            "DELETE /repos/paperclipai/paperclip/issues/14187/reactions/77",
            "GET /repos/paperclipai/paperclip/issues/14187",
        ).inOrder()
        assertThat((requests.first().body as TextContent).text).isEqualTo("""{"content":"hooray"}""")
    }

    @Test
    fun every_reaction_has_the_name_github_gives_it() = runTest {
        val api = reacting(alreadyThere = false)

        Reaction.entries.forEach { api.toggleReaction("tok", "me", issue, 1, it) }

        assertThat(requests.filter { it.method == HttpMethod.Post }.map { (it.body as TextContent).text.substringAfter(":\"").substringBefore('"') })
            .containsExactly("+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes").inOrder()
    }

    @Test
    fun a_reaction_the_forge_refuses_is_a_failure_and_nothing_more_is_asked() = runTest {
        val result = api { json("""{"message":"Resource not accessible"}""", status = HttpStatusCode.Forbidden) }.toggleReaction("tok", "me", issue, 4242, Reaction.EYES)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Resource not accessible")))
        assertThat(requests).hasSize(1)
    }

    @Test
    fun github_s_api_takes_no_picture_and_is_asked_for_none() = runTest {
        val api = api { json("{}") }

        assertThat(api.attachments).isEqualTo(fr.arthurbrugiere.forgeline.core.model.AttachmentRule.NOBODY)
        assertThat(api.attach("tok", issue, "a.png", "image/png", ByteArray(1))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(requests).isEmpty()
    }
}
