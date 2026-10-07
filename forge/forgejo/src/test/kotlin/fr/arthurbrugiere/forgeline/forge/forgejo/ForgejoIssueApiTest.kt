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
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import java.time.LocalDate
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

        assertThat(asking(admin = true, push = true).access("tok", pull.repo).value().access).isEqualTo(RepoAccess.ADMIN)
        // Forgejo has no triage role: pushing is what manages conversations.
        assertThat(asking(admin = false, push = true).access("tok", pull.repo).value().access).isEqualTo(RepoAccess.WRITE)
        assertThat(asking(admin = false, push = false).access("tok", pull.repo).value().access).isEqualTo(RepoAccess.NONE)
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

    private fun answering(route: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        with(codeberg) { ForgejoIssueApi(client { route(it) }, ForgeInstance.Codeberg) }

    private val sent get() = codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }
    private val bodies get() = codeberg.requests.map { (it.body as? TextContent)?.text }
    private val seven = IssueRef(pull.repo, 7)

    @Test
    fun forgejo_neither_locks_a_conversation_nor_moves_an_issue() = runTest {
        val api = answering { with(codeberg) { json("{}") } }

        assertThat(api.actions).containsExactly(
            ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.PIN, ConversationAction.DELETE,
            ConversationAction.DUE_DATE, ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES,
        )
        assertThat(api.setLocked("tok", seven, locked = true)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.transfer("tok", seven, RepoId("forgejo", "docs", ForgeInstance.Codeberg))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(codeberg.requests).isEmpty()
    }

    @Test
    fun closing_keeps_no_reason() = runTest {
        answering { with(codeberg) { json(opened, HttpStatusCode.Created) } }.setOpen("tok", seven, open = false, reason = CloseReason.NOT_PLANNED)

        assertThat(bodies.single()).isEqualTo("""{"state":"closed"}""")
    }

    @Test
    fun a_repository_s_labels_are_its_own_then_its_organisation_s() = runTest {
        val api = answering {
            with(codeberg) {
                if (it.url.encodedPath.startsWith("/api/v1/orgs/")) json("""[{"id":3,"name":"Kind/Bug","color":"ee0701"},{"id":4,"name":"bug","color":"000000"}]""")
                else json("""[{"id":1,"name":"bug","color":"#d73a4a"},{"id":2,"name":"question","color":"d876e3"}]""")
            }
        }

        // A name both have is the repository's.
        assertThat(api.labels("tok", pull.repo).value())
            .containsExactly(Label("bug", "d73a4a"), Label("question", "d876e3"), Label("Kind/Bug", "ee0701")).inOrder()
        assertThat(sent).containsExactly("GET /api/v1/repos/forgejo/forgejo/labels", "GET /api/v1/orgs/forgejo/labels")
    }

    @Test
    fun an_owner_without_organisation_labels_still_lists_the_repository_s() = runTest {
        val api = answering {
            with(codeberg) {
                if (it.url.encodedPath.startsWith("/api/v1/orgs/")) json("""{"message":"not found"}""", HttpStatusCode.NotFound)
                else json("""[{"id":1,"name":"bug","color":"d73a4a"}]""")
            }
        }

        assertThat(api.labels("tok", pull.repo).value()).containsExactly(Label("bug", "d73a4a"))
    }

    @Test
    fun assignable_people_and_open_milestones_are_listed() = runTest {
        val api = answering {
            with(codeberg) {
                if (it.url.encodedPath.endsWith("/assignees")) json("""[{"login":"me","full_name":"","avatar_url":"https://codeberg.org/avatars/abc"}]""")
                else json("""[{"id":9000,"title":"16.0","state":"open"}]""")
            }
        }

        assertThat(api.assignable("tok", pull.repo).value().map { it.login }).containsExactly("me")
        assertThat(api.milestones("tok", pull.repo).value()).containsExactly(Milestone(9000, "16.0"))
        assertThat(sent).containsExactly("GET /api/v1/repos/forgejo/forgejo/assignees", "GET /api/v1/repos/forgejo/forgejo/milestones").inOrder()
        assertThat(codeberg.requests.last().url.parameters["state"]).isEqualTo("open")
    }

    @Test
    fun labels_assignees_and_milestone_are_set_whole() = runTest {
        val api = answering { with(codeberg) { json(opened, HttpStatusCode.Created) } }

        assertThat(api.setLabels("tok", seven, listOf("bug", "Kind/Bug"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setAssignees("tok", seven, listOf("me"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setMilestone("tok", seven, Milestone(9000, "16.0"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setMilestone("tok", seven, null)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent).containsExactly(
            "PUT /api/v1/repos/forgejo/forgejo/issues/7/labels",
            "PATCH /api/v1/repos/forgejo/forgejo/issues/7",
            "PATCH /api/v1/repos/forgejo/forgejo/issues/7",
            "PATCH /api/v1/repos/forgejo/forgejo/issues/7",
        ).inOrder()
        // Labels go by name, and no milestone is said with 0.
        assertThat(bodies).containsExactly(
            """{"labels":["bug","Kind/Bug"]}""", """{"assignees":["me"]}""", """{"milestone":9000}""", """{"milestone":0}""",
        ).inOrder()
    }

    @Test
    fun an_issue_is_pinned_unpinned_and_deleted() = runTest {
        val api = answering {
            with(codeberg) { if (it.method == HttpMethod.Get) json("""{"number":7,"title":"Crash","state":"open","created_at":"2026-10-02T04:20:00+02:00","pin_order":2}""") else json("", HttpStatusCode.NoContent) }
        }

        assertThat(api.isPinned("tok", seven).value()).isTrue()
        assertThat(api.setPinned("tok", seven, pinned = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setPinned("tok", seven, pinned = false)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.delete("tok", seven)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent).containsExactly(
            "GET /api/v1/repos/forgejo/forgejo/issues/7",
            "POST /api/v1/repos/forgejo/forgejo/issues/7/pin",
            "DELETE /api/v1/repos/forgejo/forgejo/issues/7/pin",
            "DELETE /api/v1/repos/forgejo/forgejo/issues/7",
        ).inOrder()
    }

    @Test
    fun an_issue_that_isn_t_pinned_says_so() = runTest {
        val api = answering { with(codeberg) { json("""{"number":7,"title":"Crash","state":"open","created_at":"2026-10-02T04:20:00+02:00","pin_order":0}""") } }

        assertThat(api.isPinned("tok", seven).value()).isFalse()
    }

    private fun rights(push: Boolean, tracker: String?) = with(codeberg) {
        ForgejoIssueApi(
            client { json("""{"name":"forgejo","permissions":{"admin":false,"push":$push,"pull":true}""" + (tracker?.let { ""","internal_tracker":$it""" } ?: "") + "}") },
            ForgeInstance.Codeberg,
        )
    }

    private fun tracker(time: Boolean, contributorsOnly: Boolean, dependencies: Boolean) =
        """{"enable_time_tracker":$time,"allow_only_contributors_to_track_time":$contributorsOnly,"enable_issue_dependencies":$dependencies}"""

    @Test
    fun a_repository_s_settings_switch_time_tracking_and_dependencies_off() = runTest {
        // forgejo/forgejo's own settings (2026-10-02): no time tracker, dependencies on.
        assertThat(rights(push = true, tracker(time = false, contributorsOnly = true, dependencies = true)).access("tok", pull.repo).value().switchedOff)
            .containsExactly(ConversationAction.TIME_TRACKING)
        assertThat(rights(push = true, tracker(time = true, contributorsOnly = true, dependencies = false)).access("tok", pull.repo).value().switchedOff)
            .containsExactly(ConversationAction.DEPENDENCIES)
        assertThat(rights(push = true, tracker(time = true, contributorsOnly = true, dependencies = true)).access("tok", pull.repo).value().switchedOff).isEmpty()
    }

    @Test
    fun time_tracking_kept_to_contributors_is_off_for_everyone_else() = runTest {
        assertThat(rights(push = false, tracker(time = true, contributorsOnly = true, dependencies = true)).access("tok", pull.repo).value().switchedOff)
            .containsExactly(ConversationAction.TIME_TRACKING)
        // Open to anyone: a passer-by can track time too.
        assertThat(rights(push = false, tracker(time = true, contributorsOnly = false, dependencies = true)).access("tok", pull.repo).value().switchedOff).isEmpty()
    }

    @Test
    fun a_repository_that_keeps_its_issues_elsewhere_tracks_neither() = runTest {
        assertThat(rights(push = true, tracker = null).access("tok", pull.repo).value().switchedOff)
            .containsExactly(ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES)
    }

    @Test
    fun the_day_an_issue_is_due_is_read_whichever_way_it_was_set() = runTest {
        // Set through the API, Forgejo answers the end of that day in its own zone, which is already the next day there.
        assertThat(triaged(""","due_date":"2026-10-11T01:59:59+02:00"""", "[]").issue(null, seven).value().dueDate).isEqualTo(LocalDate.parse("2026-10-10"))
        // Set on the site: the end of the day there.
        assertThat(triaged(""","due_date":"2026-10-10T23:59:59+02:00"""", "[]").issue(null, seven).value().dueDate).isEqualTo(LocalDate.parse("2026-10-10"))
        assertThat(triaged(""","due_date":null""", "[]").issue(null, seven).value().dueDate).isNull()
    }

    @Test
    fun a_due_date_is_set_as_a_day_and_taken_away_with_its_own_field() = runTest {
        val api = answering { with(codeberg) { json(opened, HttpStatusCode.Created) } }

        assertThat(api.setDueDate("tok", seven, LocalDate.parse("2026-10-10"))).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setDueDate("tok", seven, null)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent).containsExactly("PATCH /api/v1/repos/forgejo/forgejo/issues/7", "PATCH /api/v1/repos/forgejo/forgejo/issues/7")
        assertThat(bodies).containsExactly("""{"due_date":"2026-10-10T00:00:00Z"}""", """{"unset_due_date":true}""").inOrder()
    }

    @Test
    fun time_spent_is_everyone_s_and_the_timer_is_the_one_running_here() = runTest {
        val api = answering {
            with(codeberg) {
                if (it.url.encodedPath.endsWith("/user/stopwatches")) {
                    json(
                        """[{"created":"2026-10-02T09:00:00+02:00","issue_index":7,"repo_owner_name":"other","repo_name":"forgejo","seconds":60},
                        {"created":"2026-10-02T10:00:00+02:00","issue_index":7,"repo_owner_name":"forgejo","repo_name":"forgejo","seconds":60}]""",
                    )
                } else {
                    json("""[{"id":1,"time":3600,"user_name":"me"},{"id":2,"time":1800,"user_name":"earl-warren"}]""")
                }
            }
        }

        val tracking = api.timeTracking("tok", seven).value()

        assertThat(tracking).isEqualTo(TimeTracking(5_400, Instant.parse("2026-10-02T08:00:00Z")))
        assertThat(sent).containsExactly("GET /api/v1/repos/forgejo/forgejo/issues/7/times", "GET /api/v1/user/stopwatches")
    }

    @Test
    fun without_a_timer_here_none_runs_and_timers_that_can_t_be_read_don_t_hide_the_time_spent() = runTest {
        val api = answering {
            with(codeberg) { if (it.url.encodedPath.endsWith("/user/stopwatches")) json("""{"message":"no"}""", HttpStatusCode.Forbidden) else json("""[{"id":1,"time":120}]""") }
        }

        assertThat(api.timeTracking("tok", seven).value()).isEqualTo(TimeTracking(120, null))
    }

    @Test
    fun the_timer_starts_and_stops_and_time_is_added_in_seconds() = runTest {
        val api = answering { with(codeberg) { json("{}", HttpStatusCode.Created) } }

        assertThat(api.setTimerRunning("tok", seven, running = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.setTimerRunning("tok", seven, running = false)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.addTime("tok", seven, 5_400)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent).containsExactly(
            "POST /api/v1/repos/forgejo/forgejo/issues/7/stopwatch/start",
            "POST /api/v1/repos/forgejo/forgejo/issues/7/stopwatch/stop",
            "POST /api/v1/repos/forgejo/forgejo/issues/7/times",
        ).inOrder()
        assertThat(bodies.last()).isEqualTo("""{"time":5400}""")
    }

    @Test
    fun a_timer_that_already_runs_is_a_failure() = runTest {
        val api = answering { with(codeberg) { json("""{"message":"already running"}""", HttpStatusCode.Conflict) } }

        assertThat(api.setTimerRunning("tok", seven, running = true)).isEqualTo(ForgeResult.Failure(ForgeError.Http(409, "already running")))
    }

    @Test
    fun dependencies_name_their_repository_when_it_is_another() = runTest {
        val api = answering {
            with(codeberg) {
                json(
                    """[{"number":3,"title":"Schema first","state":"closed","created_at":"2026-09-30T05:00:00+02:00"},
                    {"number":106,"title":"Website copy","state":"open","created_at":"2026-09-30T05:00:00+02:00","repository":{"owner":"dzeuros","name":"website"}}]""",
                )
            }
        }

        assertThat(api.dependencies("tok", seven).value()).containsExactly(
            LinkedIssue(IssueRef(pull.repo, 3), "Schema first", IssueState.CLOSED),
            LinkedIssue(IssueRef(RepoId("dzeuros", "website", ForgeInstance.Codeberg), 106), "Website copy", IssueState.OPEN),
        ).inOrder()
        assertThat(sent).containsExactly("GET /api/v1/repos/forgejo/forgejo/issues/7/dependencies")
    }

    @Test
    fun a_dependency_is_added_and_removed_by_naming_the_other_issue() = runTest {
        val api = answering { with(codeberg) { json(opened, HttpStatusCode.Created) } }
        val other = IssueRef(RepoId("dzeuros", "website", ForgeInstance.Codeberg), 106)

        assertThat(api.addDependency("tok", seven, other)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.removeDependency("tok", seven, other)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(sent).containsExactly(
            "POST /api/v1/repos/forgejo/forgejo/issues/7/dependencies", "DELETE /api/v1/repos/forgejo/forgejo/issues/7/dependencies",
        ).inOrder()
        assertThat(bodies.distinct()).containsExactly("""{"owner":"dzeuros","repo":"website","index":106}""")
    }

    @Test
    fun deadlines_time_and_dependencies_are_part_of_the_conversation() = runTest {
        fun entry(id: Int, type: String, extra: String = "") =
            """{"id":$id,"type":"$type","user":{"login":"maintainer"},"created_at":"2026-10-02T05:00:00+02:00"$extra}"""
        // The dependency entries follow what Codeberg sends (seen on forgejo/forgejo, 2026-10-02); the others its API description.
        val timeline = listOf(
            entry(1, "added_deadline", ""","body":"2026-10-10""""),
            entry(2, "modified_deadline", ""","body":"2026-10-12|2026-10-10""""),
            entry(3, "removed_deadline", ""","body":"2026-10-12""""),
            entry(4, "start_tracking"), entry(5, "stop_tracking", ""","body":"|3600""""), entry(6, "add_time_manual", ""","body":"|600""""),
            entry(7, "add_dependency", ""","dependent_issue":{"number":3,"title":"Schema first","repository":{"full_name":"forgejo/forgejo"}}"""),
            entry(8, "remove_dependency", ""","dependent_issue":{"number":106,"title":"Website copy","repository":{"full_name":"dzeuros/website"}}"""),
        ).joinToString(",", "[", "]")

        val events = triaged("", timeline).timeline(null, seven, 1).value().items.filterIsInstance<TimelineItem.Event>()

        assertThat(events.map { it.event }).containsExactly(
            ConversationEvent.DEADLINE_SET, ConversationEvent.DEADLINE_SET, ConversationEvent.DEADLINE_REMOVED,
            ConversationEvent.TRACKING_STARTED, ConversationEvent.TRACKING_STOPPED, ConversationEvent.TIME_ADDED,
            ConversationEvent.DEPENDENCY_ADDED, ConversationEvent.DEPENDENCY_REMOVED,
        ).inOrder()
        // A changed date names the new one; a dependency in another repository names it.
        assertThat(events.map { it.subject }).containsExactly("2026-10-10", "2026-10-12", null, null, null, null, "#3 Schema first", "dzeuros/website#106 Website copy").inOrder()
    }

    @Test
    fun the_timeline_says_how_many_pages_there_are() = runTest {
        val counted = with(codeberg) {
            ForgejoIssueApi(client { json("[]", headers = mapOf("X-Total-Count" to "120")) }, ForgeInstance.Codeberg)
        }

        // 120 entries, 50 a page: the pages after the first can be asked together.
        assertThat(counted.timeline(null, pull, page = 1).value().lastPage).isEqualTo(3)
        // A forge that doesn't count leaves it unknown: pages are then asked one after the other.
        assertThat(api.timeline(null, pull, page = 1).value().lastPage).isNull()
    }

    @Test
    fun a_comment_is_rewritten_by_its_id() = runTest {
        val api = with(codeberg) { ForgejoIssueApi(client { json(created) }, ForgeInstance.Codeberg) }

        api.editComment("tok", pull, 9001, "Works for me on 16.1").value()

        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Patch)
        assertThat(request.url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo/issues/comments/9001")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("token tok")
        assertThat((request.body as TextContent).text).isEqualTo("""{"body":"Works for me on 16.1"}""")
    }

    @Test
    fun a_comment_is_deleted_by_its_id() = runTest {
        val api = with(codeberg) { ForgejoIssueApi(client { status(HttpStatusCode.NoContent) }, ForgeInstance.Codeberg) }

        api.deleteComment("tok", pull, 9001).value()

        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Delete)
        assertThat(request.url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo/issues/comments/9001")
    }

    @Test
    fun a_comment_the_forge_will_not_let_go_is_a_failure() = runTest {
        val api = with(codeberg) { ForgejoIssueApi(client { json("""{"message":"user should have permission to edit comment"}""", HttpStatusCode.Forbidden) }, ForgeInstance.Codeberg) }

        assertThat(api.deleteComment("tok", pull, 9001)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "user should have permission to edit comment")))
    }

    @Test
    fun an_issue_or_a_pull_request_gets_a_new_title_and_text_in_one_call() = runTest {
        val api = with(codeberg) { ForgejoIssueApi(client { json(opened, HttpStatusCode.Created) }, ForgeInstance.Codeberg) }

        api.edit("tok", pull, "Crash when the list is empty", "").value()

        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Patch)
        assertThat(request.url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo/issues/14597")
        assertThat((request.body as TextContent).text).isEqualTo("""{"title":"Crash when the list is empty","body":""}""")
    }

    // The list follows Forgejo's API description (each reaction with who gave it); "null" for none was read on
    // Codeberg on 2026-09-29. Giving and taking back were not tried on a real server.
    private fun reacting(listed: String) = with(codeberg) {
        ForgejoIssueApi(client { if (it.method == HttpMethod.Get) json(listed) else json("""{"content":"+1"}""", HttpStatusCode.Created) }, ForgeInstance.Codeberg)
    }

    private fun sent() = codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }

    @Test
    fun a_reaction_not_given_yet_is_given_and_counted_with_the_others() = runTest {
        val listed = """[{"user":{"login":"alice"},"content":"+1"},{"user":{"login":"bob"},"content":"heart"}]"""

        val counts = reacting(listed).toggleReaction("tok", "me", pull, 9001, fr.arthurbrugiere.forgeline.core.model.Reaction.THUMBS_UP).value()

        assertThat(counts).containsExactly(fr.arthurbrugiere.forgeline.core.model.Reaction.THUMBS_UP, 2, fr.arthurbrugiere.forgeline.core.model.Reaction.HEART, 1)
        assertThat(sent()).containsExactly(
            "GET /api/v1/repos/forgejo/forgejo/issues/comments/9001/reactions",
            "POST /api/v1/repos/forgejo/forgejo/issues/comments/9001/reactions",
        ).inOrder()
        assertThat((codeberg.requests.last().body as TextContent).text).isEqualTo("""{"content":"+1"}""")
        assertThat(codeberg.requests.last().headers[HttpHeaders.Authorization]).isEqualTo("token tok")
    }

    @Test
    fun one_s_own_reaction_is_taken_back_whatever_the_case_of_the_login() = runTest {
        val listed = """[{"user":{"login":"Me"},"content":"+1"},{"user":{"login":"alice"},"content":"+1"},{"user":{"login":"Me"},"content":"rocket"}]"""

        val counts = reacting(listed).toggleReaction("tok", "me", pull, 9001, fr.arthurbrugiere.forgeline.core.model.Reaction.ROCKET).value()

        // The last rocket is gone: it is no longer counted at all.
        assertThat(counts).containsExactly(fr.arthurbrugiere.forgeline.core.model.Reaction.THUMBS_UP, 2)
        assertThat(codeberg.requests.last().method).isEqualTo(HttpMethod.Delete)
        assertThat((codeberg.requests.last().body as TextContent).text).isEqualTo("""{"content":"rocket"}""")
    }

    @Test
    fun the_first_reaction_of_a_conversation_is_given_where_the_forge_lists_none() = runTest {
        val counts = reacting("null").toggleReaction("tok", "me", pull, null, fr.arthurbrugiere.forgeline.core.model.Reaction.EYES).value()

        assertThat(counts).containsExactly(fr.arthurbrugiere.forgeline.core.model.Reaction.EYES, 1)
        assertThat(sent()).containsExactly(
            "GET /api/v1/repos/forgejo/forgejo/issues/14597/reactions",
            "POST /api/v1/repos/forgejo/forgejo/issues/14597/reactions",
        ).inOrder()
    }

    @Test
    fun reactions_the_app_has_no_name_for_are_left_out_of_the_counts() = runTest {
        // A server can allow its own (":gitea:"): they are neither counted nor mistaken for the one asked.
        val listed = """[{"user":{"login":"me"},"content":"gitea"}]"""

        val counts = reacting(listed).toggleReaction("tok", "me", pull, 9001, fr.arthurbrugiere.forgeline.core.model.Reaction.HEART).value()

        assertThat(counts).containsExactly(fr.arthurbrugiere.forgeline.core.model.Reaction.HEART, 1)
        assertThat(codeberg.requests.last().method).isEqualTo(HttpMethod.Post)
    }

    @Test
    fun a_reaction_the_forge_refuses_is_a_failure() = runTest {
        val api = with(codeberg) {
            ForgejoIssueApi(client { if (it.method == HttpMethod.Get) json("null") else json("""{"message":"issue is locked"}""", HttpStatusCode.Forbidden) }, ForgeInstance.Codeberg)
        }

        assertThat(api.toggleReaction("tok", "me", pull, 9001, fr.arthurbrugiere.forgeline.core.model.Reaction.HEART))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "issue is locked")))
    }
}
