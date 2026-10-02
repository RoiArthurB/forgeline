package fr.arthurbrugiere.forgeline.forge.forgejo

import io.ktor.http.HttpHeaders
import java.time.Instant
import io.ktor.http.content.TextContent
import io.ktor.http.HttpMethod
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import io.ktor.http.HttpStatusCode
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForgejoIssueApiTest {
    private val codeberg = Codeberg()
    private val pull = IssueRef(RepoId("forgejo", "forgejo", ForgeInstance.Codeberg), 14597)

    private val api = with(codeberg) {
        ForgejoIssueApi(
            client {
                val path = it.url.encodedPath
                when {
                    path.endsWith("/reactions") -> json("null")
                    path.endsWith("/reviews") -> json(fixture("reviews.json"))
                    path.endsWith("/timeline") -> json(fixture("timeline.json"))
                    path.contains("/pulls/") -> json(fixture("pull.json"))
                    else -> json(fixture("issue_pull.json"))
                }
            },
            ForgeInstance.Codeberg,
        )
    }

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun a_merged_pull_request_carries_its_branches_and_size() = runTest {
        val issue = api.issue(null, pull).value()

        assertThat(issue.state).isEqualTo(IssueState.MERGED)
        assertThat(issue.reactions).isEmpty()
        val info = issue.pullRequest!!
        assertThat(info.isMerged).isTrue()
        assertThat(info.baseRef).isEqualTo("v17.0/forgejo")
        assertThat(info.additions).isEqualTo(54)
        assertThat(info.deletions).isEqualTo(13)
        assertThat(info.changedFiles).isEqualTo(5)
    }

    @Test
    fun the_timeline_keeps_the_conversation_and_drops_the_noise() = runTest {
        val items = api.timeline(null, pull, page = 1).value().items

        // Review requests, pushes, branch deletions and commit references are left out.
        assertThat(items.map { it::class.simpleName }).containsExactly("Labeled", "Review", "Comment", "StateChanged", "Event").inOrder()
        // After the merge, the release bot filed it under the release it shipped in.
        assertThat(items[4]).isEqualTo(
            TimelineItem.Event(ConversationEvent.MILESTONED, (items[4] as TimelineItem.Event).actor, "Forgejo v17.0.0", (items[4] as TimelineItem.Event).createdAt),
        )
        assertThat((items[4] as TimelineItem.Event).actor?.login).isEqualTo("forgejo-release-notes-assistant")
        assertThat((items[0] as TimelineItem.Labeled).added).isTrue()
        assertThat((items[0] as TimelineItem.Labeled).label.name).isEqualTo("test/present")
        // The review entry doesn't say it approved: the pull request's reviews do.
        assertThat((items[1] as TimelineItem.Review).state).isEqualTo(ReviewState.APPROVED)
        assertThat((items[2] as TimelineItem.Comment).author?.login).isEqualTo("forgejo-actions")
        assertThat((items[3] as TimelineItem.StateChanged).change).isEqualTo(StateChange.MERGED)
    }

    @Test
    fun a_plain_issue_has_no_pull_request_details() = runTest {
        val first = with(codeberg) { fixture("issues.json") }.let { all -> kotlinx.serialization.json.Json.parseToJsonElement(all) }
            .let { (it as kotlinx.serialization.json.JsonArray)[0].toString() }
        val issues = with(codeberg) {
            ForgejoIssueApi(client { if (it.url.encodedPath.endsWith("/reactions")) json("[]") else json(first) }, ForgeInstance.Codeberg)
        }

        val issue = issues.issue(null, IssueRef(pull.repo, 14601)).value()

        assertThat(issue.pullRequest).isNull()
        assertThat(issue.state).isEqualTo(IssueState.OPEN)
        // The pull request call went out alongside (it can't be known before): its 404 is ignored.
        assertThat(issue.pullRequest).isNull()
    }

    @Test
    fun a_missing_issue_is_an_error() = runTest {
        val issues = with(codeberg) { ForgejoIssueApi(client { status(HttpStatusCode.NotFound) }, ForgeInstance.Codeberg) }

        val result = issues.issue(null, IssueRef(pull.repo, 1))

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.Http::class.java)
    }

    @Test
    fun a_pull_requests_details_are_asked_alongside_the_issue_not_after() = runTest {
        // Regression: the details waited for the issue to say it's a pull request, a round trip each to a far forge.
        val bothAsked = CompletableDeferred<Unit>()
        val asked = mutableSetOf<String>()
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        path.endsWith("/reactions") -> json("null")
                        path.contains("/pulls/") -> {
                            asked += "pull"
                            if ("issue" in asked) bothAsked.complete(Unit)
                            withTimeout(5_000) { bothAsked.await() }
                            json(fixture("pull.json"))
                        }
                        else -> {
                            asked += "issue"
                            if ("pull" in asked) bothAsked.complete(Unit)
                            withTimeout(5_000) { bothAsked.await() }
                            json(fixture("issue_pull.json"))
                        }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        assertThat(issues.issue(null, pull).value().pullRequest?.isMerged).isTrue()
    }

    @Test
    fun a_pull_requests_title_takes_one_request() = runTest {
        val issues = with(codeberg) { ForgejoIssueApi(client { json(fixture("issue_pull.json")) }, ForgeInstance.Codeberg) }

        assertThat(issues.title(null, pull).value()).isNotEmpty()
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/issues/14597")
    }

    @Test
    fun a_plain_issue_never_waits_for_the_pull_request_call_asked_alongside() = runTest {
        val first = with(codeberg) { fixture("issues.json") }.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            .let { (it as kotlinx.serialization.json.JsonArray)[0].toString() }
        val never = CompletableDeferred<Unit>()
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        // The pull request call hangs: a plain issue must not wait for it.
                        path.contains("/pulls/") -> { never.await(); json("{}") }
                        path.endsWith("/reactions") -> json("[]")
                        else -> json(first)
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        // Real time: the fake server answers on its own thread, and virtual time would skip straight to the timeout.
        val issue = withContext(Dispatchers.Default) { withTimeout(5_000) { issues.issue(null, IssueRef(pull.repo, 14601)) } }

        assertThat(issue.value().pullRequest).isNull()
    }

    @Test
    fun review_states_are_asked_alongside_the_timeline_not_after() = runTest {
        // Regression: the reviews waited for the timeline to show a review, a round trip each to a far forge.
        val arrived = java.util.concurrent.atomic.AtomicInteger()
        val both = CompletableDeferred<Unit>()
        suspend fun meet() {
            if (arrived.incrementAndGet() == 2) both.complete(Unit)
            withTimeout(5_000) { both.await() }
        }
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client {
                    val path = it.url.encodedPath
                    when {
                        path.endsWith("/reviews") -> { meet(); json(fixture("reviews.json")) }
                        else -> { meet(); json(fixture("timeline.json")) }
                    }
                },
                ForgeInstance.Codeberg,
            )
        }

        val items = issues.timeline(null, pull, page = 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.Review>()).isNotEmpty()
    }

    @Test
    fun a_timeline_without_reviews_never_waits_for_them() = runTest {
        val never = CompletableDeferred<Unit>()
        val issues = with(codeberg) {
            ForgejoIssueApi(
                client { if (it.url.encodedPath.endsWith("/reviews")) { never.await(); json("[]") } else json("[]") },
                ForgeInstance.Codeberg,
            )
        }

        // Real time, for the same reason.
        val page = withContext(Dispatchers.Default) { withTimeout(5_000) { issues.timeline(null, pull, page = 1) } }

        assertThat(page.value().items).isEmpty()
    }

    // The answer below follows Forgejo's API description for a created comment: posting can't be captured from a
    // real account in tests.
    private val created = """{"id":9001,"user":{"login":"me","full_name":"","avatar_url":"https://codeberg.org/avatars/abc"},
        "body":"Works for me on 16.0","created_at":"2026-10-01T11:30:00+02:00","updated_at":"2026-10-01T11:30:00+02:00"}"""

    @Test
    fun a_comment_is_posted_to_the_conversation_and_comes_back_as_kept() = runTest {
        val posting = with(codeberg) { ForgejoIssueApi(client { json(created, HttpStatusCode.Created) }, ForgeInstance.Codeberg) }

        val comment = posting.comment("tok", pull, "Works for me on 16.0").value()

        assertThat(comment.id).isEqualTo(9001)
        assertThat(comment.author?.login).isEqualTo("me")
        assertThat(comment.body).isEqualTo("Works for me on 16.0")
        assertThat(comment.createdAt).isEqualTo(Instant.parse("2026-10-01T09:30:00Z"))
        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo/issues/14597/comments")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("token tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"body":"Works for me on 16.0"}""")
    }

    @Test
    fun a_comment_the_forge_refuses_is_a_failure() = runTest {
        val posting = with(codeberg) { ForgejoIssueApi(client { json("""{"message":"issue is locked"}""", HttpStatusCode.Forbidden) }, ForgeInstance.Codeberg) }

        assertThat(posting.comment("tok", pull, "Hello")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "issue is locked")))
    }

    // The answer below follows Forgejo's API description for a created issue: opening one can't be captured from a
    // real account in tests.
    private val opened = """{"id":77001,"number":9321,"title":"Crash when the list is empty","body":"Steps:\n1. Open it","state":"open",
        "user":{"login":"me","full_name":"","avatar_url":"https://codeberg.org/avatars/abc"},"labels":[],"comments":0,
        "created_at":"2026-10-02T04:20:00+02:00","updated_at":"2026-10-02T04:20:00+02:00","closed_at":null,"pull_request":null}"""

    @Test
    fun an_issue_is_opened_in_the_repository_and_comes_back_numbered() = runTest {
        val opening = with(codeberg) { ForgejoIssueApi(client { json(opened, HttpStatusCode.Created) }, ForgeInstance.Codeberg) }

        val created = opening.create("tok", pull.repo, "Crash when the list is empty", "Steps:\n1. Open it").value()

        assertThat(created.ref).isEqualTo(IssueRef(pull.repo, 9321))
        assertThat(created.title).isEqualTo("Crash when the list is empty")
        assertThat(created.body).isEqualTo("Steps:\n1. Open it")
        assertThat(created.state).isEqualTo(IssueState.OPEN)
        assertThat(created.author?.login).isEqualTo("me")
        assertThat(created.createdAt).isEqualTo(Instant.parse("2026-10-02T02:20:00Z"))
        assertThat(created.pullRequest).isNull()
        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo/issues")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("token tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"title":"Crash when the list is empty","body":"Steps:\n1. Open it"}""")
    }

    @Test
    fun an_issue_the_forge_refuses_is_a_failure() = runTest {
        val opening = with(codeberg) { ForgejoIssueApi(client { json("""{"message":"repository is archived"}""", HttpStatusCode.Forbidden) }, ForgeInstance.Codeberg) }

        assertThat(opening.create("tok", pull.repo, "Hello", "")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "repository is archived")))
    }

    @Test
    fun closing_and_reopening_change_the_state_of_the_issue_or_pull_request() = runTest {
        // The answer is the issue as Forgejo keeps it (201); only its success is read.
        val changing = with(codeberg) { ForgejoIssueApi(client { json(opened, HttpStatusCode.Created) }, ForgeInstance.Codeberg) }

        assertThat(changing.setOpen("tok", pull, open = false)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(changing.setOpen("tok", pull, open = true)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(codeberg.requests.map { it.method }).containsExactly(HttpMethod.Patch, HttpMethod.Patch)
        // A pull request goes through the issue endpoint too.
        assertThat(codeberg.requests.map { it.url.toString() }.distinct()).containsExactly("https://codeberg.org/api/v1/repos/forgejo/forgejo/issues/14597")
        assertThat(codeberg.requests.map { (it.body as TextContent).text }).containsExactly("""{"state":"closed"}""", """{"state":"open"}""").inOrder()
        assertThat(codeberg.requests.first().headers[HttpHeaders.Authorization]).isEqualTo("token tok")
    }

    @Test
    fun a_state_change_the_forge_refuses_is_a_failure() = runTest {
        val changing = with(codeberg) { ForgejoIssueApi(client { json("""{"message":"user should have permission to write"}""", HttpStatusCode.Forbidden) }, ForgeInstance.Codeberg) }

        assertThat(changing.setOpen("tok", pull, open = false)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "user should have permission to write")))
    }

    @Test
    fun the_forge_s_permissions_say_what_the_signed_in_user_may_do() = runTest {
        fun asking(admin: Boolean, push: Boolean) = with(codeberg) {
            ForgejoIssueApi(client { json("""{"name":"forgejo","permissions":{"admin":$admin,"push":$push,"pull":true}}""") }, ForgeInstance.Codeberg)
        }

        assertThat(asking(admin = true, push = true).access("tok", pull.repo).value()).isEqualTo(RepoAccess.ADMIN)
        // Forgejo has no triage role: pushing is what manages conversations.
        assertThat(asking(admin = false, push = true).access("tok", pull.repo).value()).isEqualTo(RepoAccess.WRITE)
        assertThat(asking(admin = false, push = false).access("tok", pull.repo).value()).isEqualTo(RepoAccess.NONE)
        assertThat(codeberg.requests.first().url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo")
        assertThat(codeberg.requests.first().headers[HttpHeaders.Authorization]).isEqualTo("token tok")
    }

    // The answers below follow Forgejo's API description (Codeberg, 16.0): no captured conversation holds these.
    private fun triaged(issueFields: String, timeline: String) = with(codeberg) {
        ForgejoIssueApi(
            client {
                val path = it.url.encodedPath
                when {
                    path.endsWith("/reactions") -> json("null")
                    path.endsWith("/timeline") -> json(timeline)
                    path.contains("/pulls/") -> json("""{"message":"not found"}""", HttpStatusCode.NotFound)
                    else -> json("""{"number":7,"title":"Crash","state":"open","created_at":"2026-10-02T04:20:00+02:00"$issueFields}""")
                }
            },
            ForgeInstance.Codeberg,
        )
    }

    @Test
    fun a_locked_assigned_issue_with_a_milestone_says_so() = runTest {
        val api = triaged(""","is_locked":true,"assignees":[{"login":"me"},{"login":"earl-warren"}],"milestone":{"id":9000,"title":"16.0"}""", "[]")

        val details = api.issue(null, IssueRef(pull.repo, 7)).value()

        assertThat(details.isLocked).isTrue()
        assertThat(details.assignees.map { it.login }).containsExactly("me", "earl-warren").inOrder()
        assertThat(details.milestone).isEqualTo(Milestone(9000, "16.0"))
    }

    @Test
    fun an_issue_nobody_triaged_has_neither() = runTest {
        // Nobody assigned and no milestone both answer null.
        val details = triaged(""","is_locked":false,"assignees":null,"milestone":null""", "[]").issue(null, IssueRef(pull.repo, 7)).value()

        assertThat(details.isLocked).isFalse()
        assertThat(details.assignees).isEmpty()
        assertThat(details.milestone).isNull()
    }

    @Test
    fun triage_events_name_who_and_what() = runTest {
        fun entry(id: Int, type: String, extra: String = "") =
            """{"id":$id,"type":"$type","user":{"login":"maintainer"},"created_at":"2026-10-02T05:00:00+02:00"$extra}"""
        val timeline = listOf(
            entry(1, "lock"), entry(2, "unlock"), entry(3, "pin"), entry(4, "unpin"),
            entry(5, "assignees", ""","assignee":{"login":"me"},"removed_assignee":false"""),
            entry(6, "assignees", ""","assignee":{"login":"me"},"removed_assignee":true"""),
            entry(7, "milestone", ""","milestone":{"id":9000,"title":"16.0"},"old_milestone":null"""),
            entry(8, "milestone", ""","milestone":null,"old_milestone":{"id":9000,"title":"16.0"}"""),
        ).joinToString(",", "[", "]")

        val events = triaged("", timeline).timeline(null, IssueRef(pull.repo, 7), 1).value().items.filterIsInstance<TimelineItem.Event>()

        assertThat(events.map { it.event }).containsExactly(
            ConversationEvent.LOCKED, ConversationEvent.UNLOCKED, ConversationEvent.PINNED, ConversationEvent.UNPINNED,
            ConversationEvent.ASSIGNED, ConversationEvent.UNASSIGNED, ConversationEvent.MILESTONED, ConversationEvent.DEMILESTONED,
        ).inOrder()
        assertThat(events.map { it.subject }).containsExactly(null, null, null, null, "me", "me", "16.0", "16.0").inOrder()
        assertThat(events.map { it.actor?.login }.distinct()).containsExactly("maintainer")
    }
}
