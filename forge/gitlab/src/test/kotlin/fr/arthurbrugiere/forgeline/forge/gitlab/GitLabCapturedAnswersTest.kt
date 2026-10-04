package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The clients against what gitlab.com really answers. Everything under `gitlab/captured` was read from a scratch
 * project on 2026-10-04 (issue #1 and merge request !1 of `RoiArthurB/forgeline-scratch`), e-mail fields removed:
 * shapes written by hand had let through numbers that don't fit, actions GitLab doesn't send and notes it words
 * differently.
 */
class GitLabCapturedAnswersTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("RoiArthurB", "forgeline-scratch", ForgeInstance.GitLab)
    private val issue = IssueRef(repo, 1, isPullRequest = false)
    private val mergeRequest = IssueRef(repo, 1, isPullRequest = true)

    private fun captured(name: String) = requireNotNull(javaClass.getResource("/gitlab/captured/$name")) { name }.readText()

    /** Serves the captured answer for each path; [headers] go with every answer. */
    private fun client(headers: Map<String, String> = emptyMap()) = gitlabHttpClient(
        MockEngine { request ->
            requests += request
            val path = request.url.encodedPath.substringAfter("/api/v4")
            val body = when {
                request.method != HttpMethod.Get -> "{}"
                path == "/events" -> captured("events.json")
                path == "/todos" -> captured("todos.json")
                path.endsWith("/issues/1/notes") -> captured("issue_notes.json")
                path.endsWith("/issues/1/resource_state_events") -> captured("issue_state_events.json")
                path.endsWith("/issues/1/award_emoji") -> captured("issue_award_emoji.json")
                path.endsWith("/issues/1") -> captured("issue.json")
                path.endsWith("/merge_requests/1/notes") -> captured("merge_request_notes.json")
                path.endsWith("/merge_requests/1/resource_state_events") -> "[]"
                path.endsWith("/merge_requests/1/award_emoji") -> "[]"
                path.endsWith("/merge_requests/1") -> captured("merge_request.json")
                path.endsWith("/issues") -> captured("issues_list.json")
                path.endsWith("/merge_requests") -> captured("merge_requests_list.json")
                path.startsWith("/projects/") && path.count { it == '/' } == 2 -> captured("project.json")
                else -> "[]"
            }
            respond(body, HttpStatusCode.OK, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
        },
    )

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private fun paths() = requests.map { "${it.method.value} ${it.url.encodedPath.substringAfter("/api/v4")}" }

    // Feed

    @Test
    fun the_feed_reads_real_events() = runTest {
        // Regression: a comment's `target_iid` is the note's own id, which doesn't fit an Int: the whole page failed.
        val events = GitLabFeedApi(client()).receivedEvents("tok", "RoiArthurB", page = 1, ifModifiedSince = null).value().events!!

        val actions = events.map { it.action }
        // The conversation a comment is on comes from the note, with its kind.
        assertThat(actions).contains(FeedAction.Commented(1, "Keep install flags on retry", isPullRequest = true))
        assertThat(actions).contains(FeedAction.Commented(1, "Heartbeat recovery escalates too early", isPullRequest = false))
        // GitLab says "opened", not "created".
        assertThat(actions).contains(FeedAction.Issue(IssueAction.OPENED, 1, "Heartbeat recovery escalates too early"))
        assertThat(actions).contains(FeedAction.Issue(IssueAction.CLOSED, 1, "Heartbeat recovery escalates too early"))
        assertThat(actions).contains(FeedAction.PullRequest(PullRequestAction.OPENED, 1, "Keep install flags on retry"))
        assertThat(actions.filterIsInstance<FeedAction.Pushed>()).isNotEmpty()
        assertThat(events.map { it.repo }.distinct()).containsExactly(repo)
    }

    @Test
    fun the_feed_asks_for_what_happened_around_the_reader_not_only_what_they_did() = runTest {
        GitLabFeedApi(client()).receivedEvents("tok", "RoiArthurB", page = 1, ifModifiedSince = null)

        val asked = requests.first { it.url.encodedPath.endsWith("/events") }
        assertThat(asked.url.parameters["scope"]).isEqualTo("all")
    }

    // Conversations

    @Test
    fun an_issue_is_read_with_its_reactions() = runTest {
        val details = GitLabIssueApi(client()).issue("tok", issue).value()

        assertThat(details.ref).isEqualTo(issue)
        assertThat(details.title).isEqualTo("Heartbeat recovery escalates too early")
        assertThat(details.state).isEqualTo(IssueState.OPEN)
        assertThat(details.pullRequest).isNull()
        assertThat(details.dueDate).isEqualTo(LocalDate.of(2031, 3, 14))
        assertThat(details.milestone?.title).isEqualTo("v1.0")
        assertThat(details.assignees.map { it.login }).containsExactly("RoiArthurB")
        assertThat(details.reactions).containsExactly(Reaction.THUMBS_UP, 1)
    }

    @Test
    fun a_merge_request_is_read_from_its_own_numbering() = runTest {
        val details = GitLabIssueApi(client()).issue("tok", mergeRequest).value()

        assertThat(details.ref).isEqualTo(mergeRequest)
        assertThat(details.title).isEqualTo("Keep install flags on retry")
        assertThat(details.pullRequest?.headRef).isEqualTo("keep-flags")
        assertThat(details.pullRequest?.baseRef).isEqualTo("main")
        assertThat(paths()).contains("GET /projects/RoiArthurB%2Fforgeline-scratch/merge_requests/1")
        assertThat(paths().none { it.contains("/issues/") }).isTrue()
    }

    @Test
    fun a_reference_that_does_not_say_is_an_issue_and_nothing_else_is_tried() = runTest {
        // Regression: it fell back to the merge request with the same number, and every call after it went to the issue.
        val unknown = IssueRef(repo, 1)

        assertThat(GitLabIssueApi(client()).issue("tok", unknown).value().pullRequest).isNull()

        assertThat(paths().none { it.contains("/merge_requests/") }).isTrue()
    }

    @Test
    fun the_timeline_tells_what_was_done_in_gitlabs_own_words() = runTest {
        val items = GitLabIssueApi(client()).timeline("tok", issue, page = 1).value().items

        assertThat((items.first() as TimelineItem.Comment).body).isEqualTo("I can reproduce this on every restart.")
        // Regression: "unlocked" contains "locked" and "unassigned" contains "assigned": the first test won every time.
        assertThat(items.filterIsInstance<TimelineItem.Event>().map { it.event }).containsExactly(
            ConversationEvent.ASSIGNED, ConversationEvent.UNASSIGNED, ConversationEvent.ASSIGNED, ConversationEvent.UNASSIGNED,
            ConversationEvent.ASSIGNED, ConversationEvent.LOCKED, ConversationEvent.UNLOCKED, ConversationEvent.DEADLINE_SET,
            ConversationEvent.DEADLINE_REMOVED, ConversationEvent.DEADLINE_SET, ConversationEvent.TIME_ADDED,
        ).inOrder()
        assertThat(items.filterIsInstance<TimelineItem.Event>().first().subject).isEqualTo("RoiArthurB")
    }

    @Test
    fun closing_and_reopening_show_in_the_timeline_in_their_place() = runTest {
        // GitLab keeps them apart from the notes: they are asked alongside.
        val items = GitLabIssueApi(client()).timeline("tok", issue, page = 1).value().items

        val changes = items.filterIsInstance<TimelineItem.StateChanged>()
        assertThat(changes.map { it.change }).containsExactly(StateChange.CLOSED, StateChange.REOPENED).inOrder()
        assertThat(changes.first().actor?.login).isEqualTo("RoiArthurB")
        // In time order with the rest: after the time spent, before the mention from the merge request.
        assertThat(items.mapNotNull { it.createdAt }).isInOrder()
    }

    @Test
    fun the_timeline_says_how_many_pages_there_are() = runTest {
        val page = GitLabIssueApi(client(mapOf("x-next-page" to "2", "x-total-pages" to "7"))).timeline("tok", issue, page = 1).value()

        assertThat(page.nextPage).isEqualTo(2)
        assertThat(page.lastPage).isEqualTo(7)
    }

    @Test
    fun a_merge_requests_notes_come_from_the_merge_request() = runTest {
        val items = GitLabIssueApi(client()).timeline("tok", mergeRequest, page = 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.Comment>().map { it.body }).containsExactly("Looks right to me.")
        assertThat(paths().none { it.contains("/issues/") }).isTrue()
    }

    @Test
    fun a_due_date_is_never_set_on_a_merge_request() = runTest {
        // Regression: it was sent to the issue with the same number, which is another conversation.
        val api = GitLabIssueApi(client())

        assertThat(api.setDueDate("tok", mergeRequest, LocalDate.of(2031, 3, 14))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(requests).isEmpty()
        assertThat(api.issueOnly).containsAtLeast(fr.arthurbrugiere.forgeline.core.model.ConversationAction.DUE_DATE, fr.arthurbrugiere.forgeline.core.model.ConversationAction.DELETE)
    }

    // Inbox

    @Test
    fun todos_become_threads_that_know_their_kind_and_where_they_stand() = runTest {
        val threads = GitLabNotificationsApi(client()).threads("tok", ifModifiedSince = null).value().threads!!

        val onMergeRequest = threads.single { it.type == SubjectType.PULL_REQUEST }
        assertThat(onMergeRequest.subject).isEqualTo(mergeRequest)
        assertThat(onMergeRequest.subject?.isPullRequest).isTrue()
        assertThat(onMergeRequest.title).isEqualTo("Keep install flags on retry")
        assertThat(onMergeRequest.state).isEqualTo(SubjectState.OPEN)
        val onIssue = threads.single { it.type == SubjectType.ISSUE }
        assertThat(onIssue.subject).isEqualTo(issue)
        assertThat(onIssue.repo).isEqualTo(repo)
        assertThat(onIssue.unread).isTrue()
    }

    @Test
    fun reading_a_todo_leaves_it_on_gitlab() = runTest {
        // Regression: opening a thread marks it read, and that marked the todo done: gone from GitLab for good.
        val api = GitLabNotificationsApi(client())

        assertThat(api.markRead("tok", "777014993")).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests).isEmpty()
    }

    @Test
    fun done_marks_the_todo_done() = runTest {
        GitLabNotificationsApi(client()).markDone("tok", "777014993")

        assertThat(paths()).containsExactly("POST /todos/777014993/mark_as_done")
    }

    @Test
    fun unsubscribing_leaves_the_conversation_then_clears_its_todo() = runTest {
        // Regression: it only marked the todo done, and the reader stayed subscribed.
        val api = GitLabNotificationsApi(client())
        api.threads("tok", ifModifiedSince = null)
        requests.clear()

        assertThat(api.unsubscribe("tok", "777015091")).isEqualTo(ForgeResult.Success(Unit))

        assertThat(paths()).containsExactly(
            "POST /projects/RoiArthurB%2Fforgeline-scratch/merge_requests/1/unsubscribe",
            "POST /todos/777015091/mark_as_done",
        ).inOrder()
    }

    @Test
    fun unsubscribing_finds_the_todo_again_after_a_restart() = runTest {
        // Nothing synced yet in this process: the todo is looked up before leaving its conversation.
        val api = GitLabNotificationsApi(client())

        api.unsubscribe("tok", "777014993")

        assertThat(paths()).containsExactly(
            "GET /todos",
            "POST /projects/RoiArthurB%2Fforgeline-scratch/issues/1/unsubscribe",
            "POST /todos/777014993/mark_as_done",
        ).inOrder()
    }

    // Repository

    @Test
    fun a_repositorys_lists_know_issues_from_merge_requests() = runTest {
        val api = GitLabRepoApi(client())

        assertThat(api.issues("tok", repo, fr.arthurbrugiere.forgeline.core.model.IssueQuery()).value().single().isPullRequest).isFalse()
        assertThat(api.pullRequests("tok", repo, fr.arthurbrugiere.forgeline.core.model.IssueQuery()).value().single().isPullRequest).isTrue()
        assertThat(api.repo("tok", repo).value().id).isEqualTo(repo)
    }

    @Test
    fun closed_merge_requests_are_asked_for_as_such() = runTest {
        // Regression: the 50 newest of every state were asked and the open ones dropped: a busy project showed none.
        GitLabRepoApi(client()).pullRequests("tok", repo, fr.arthurbrugiere.forgeline.core.model.IssueQuery(open = false))

        val states = requests.filter { it.url.encodedPath.endsWith("/merge_requests") }.map { it.url.parameters["state"] }
        assertThat(states).containsExactly("merged", "closed")
    }

    @Test
    fun a_timeline_still_shows_when_its_state_changes_cannot_be_read() = runTest {
        val api = GitLabIssueApi(
            gitlabHttpClient(
                MockEngine { request ->
                    val body = if (request.url.encodedPath.endsWith("/resource_state_events")) """{"message":"not a list"}""" else captured("issue_notes.json")
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )

        val items = api.timeline("tok", issue, page = 1).value().items

        assertThat(items.filterIsInstance<TimelineItem.Comment>()).hasSize(1)
        assertThat(items.filterIsInstance<TimelineItem.StateChanged>()).isEmpty()
    }

    // Search and stars

    @Test
    fun search_results_whose_project_cannot_be_told_are_left_out() = runTest {
        // Regression: they were filed under a made-up "gitlab/project".
        val api = GitLabSearchApi(
            gitlabHttpClient(
                MockEngine { request ->
                    val body = if (request.url.encodedPath.endsWith("/issues")) {
                        // An address with nothing to tell the project by.
                        captured("issues_list.json").replace("/-/work_items/1", "")
                    } else {
                        captured("merge_requests_list.json")
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )

        val found = api.issues("tok", "retry", page = 1).value().items

        assertThat(found.map { it.repo }).containsExactly(repo)
        assertThat(found.single().issue.isPullRequest).isTrue()
    }

    @Test
    fun what_is_starred_is_asked_once_for_every_repository() = runTest {
        // Regression: one request per repository, a Trending list's worth each time it showed.
        val other = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)
        val api = GitLabStarApi(
            gitlabHttpClient(
                MockEngine { request ->
                    requests += request
                    respond("[" + captured("project.json") + "]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )

        val starred = api.starredStatus("tok", listOf(repo, other, RepoId("a", "b", ForgeInstance.GitLab))).value()

        assertThat(starred[repo]).isTrue()
        assertThat(starred[other]).isFalse()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().url.parameters["starred"]).isEqualTo("true")
    }
}
