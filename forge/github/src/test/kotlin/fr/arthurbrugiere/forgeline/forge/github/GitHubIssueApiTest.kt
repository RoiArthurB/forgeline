package fr.arthurbrugiere.forgeline.forge.github

import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
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
        assertThat(items.filterIsInstance<TimelineItem.StateChanged>().map { it.change })
            .containsExactly(StateChange.MERGED, StateChange.CLOSED).inOrder()
        val commit = items.filterIsInstance<TimelineItem.Committed>().first()
        assertThat(commit.message).startsWith("fix(runner): acquire reusable leases")
        assertThat(commit.createdAt).isNotNull()
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
    fun a_next_page_is_announced_by_the_link_header() = runTest {
        val link = """<https://api.github.com/repositories/1/issues/5462/timeline?per_page=100&page=2>; rel="next", """ +
            """<https://api.github.com/repositories/1/issues/5462/timeline?per_page=100&page=4>; rel="last""""

        val page = api { json("[]", mapOf("Link" to link)) }.timeline(null, issue, 1).value()

        assertThat(page.nextPage).isEqualTo(2)
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
    fun an_issue_the_forge_refuses_is_a_failure() = runTest {
        // What GitHub answers when a repository's issues are switched off.
        val result = api { json("""{"message":"Issues are disabled for this repo"}""", status = HttpStatusCode.Gone) }.create("tok", repo, "Hello", "")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(410, "Issues are disabled for this repo")))
    }
}
