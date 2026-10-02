package fr.arthurbrugiere.forgeline.forge.github

import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
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
}
