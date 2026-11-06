package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What GitLab tells of a conversation beside its comments. `captured/system_notes.json` holds one real system note of
 * each kind, read on gitlab.com on 2026-11-05 from gitlab-org/gitlab, gitlab-org/gitlab-runner, inkscape/inkscape and
 * fdroid/fdroiddata; `captured/issue_*_events.json` gitlab-org/gitlab#373997's resource events.
 */
class GitLabTimelineEventsTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val home = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)
    private val issue = IssueRef(home, 373997, isPullRequest = false)

    private fun captured(name: String) = requireNotNull(javaClass.getResource("/gitlab/captured/$name")) { name }.readText()

    private fun api(notes: String = "[]", events: (String) -> String = { "[]" }) = GitLabIssueApi(
        gitlabHttpClient(
            MockEngine { request ->
                requests += request
                val path = request.url.encodedPath
                val body = if (path.endsWith("/notes")) notes else if ("/resource_" in path) events(path.substringAfterLast('/')) else "[]"
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private suspend fun items(ref: IssueRef = issue, notes: String = captured("system_notes.json"), events: (String) -> String = { "[]" }) =
        api(notes, events).timeline("tok", ref, 1).value().items

    @Test
    fun every_kind_of_system_note_is_told() = runTest {
        val items = items()
        fun TimelineItem.said(): String = when (this) {
            is TimelineItem.Event -> "${event.name} ${subject.orEmpty()}".trim()
            is TimelineItem.Review -> "REVIEW $state"
            is TimelineItem.Renamed -> "RENAMED $from -> $to"
            is TimelineItem.CrossReferenced -> "MENTIONED ${source.repo.fullName}${if (sourceIsPullRequest) "!" else "#"}${source.number}"
            is TimelineItem.CommitReferenced -> "COMMIT ${commit.sha.take(8)}"
            else -> javaClass.simpleName
        }

        assertThat(items.sortedBy { (it as? TimelineItem.Event)?.createdAt ?: it.createdAt }.map { it.said() }).containsExactly(
            "ASSIGNED vitallium",
            "UNASSIGNED vitallium",
            "REVIEW_REQUESTED GitLabDuo",
            "REVIEW_REQUEST_REMOVED vvempati",
            "REVIEW APPROVED",
            "REVIEW CHANGES_REQUESTED",
            "REVIEW COMMENTED",
            "RENAMED Add MILES (com.aistudio.miles.track) -> New app: MILES",
            "DESCRIPTION_CHANGED",
            "MENTIONED gitlab-org/gitlab!50786",
            "MENTIONED gitlab-org/gitlab#430036",
            "MENTIONED gitlab-org/admin#377",
            "COMMIT 2e5d38f0",
            "COMMIT ced28f18",
            "RELATED_ADDED gitlab-org/release/tasks#4893",
            "RELATED_ADDED gitlab-org/quality/triage-reports#33631",
            "BLOCKING_ADDED #396273",
            "MOVED_AWAY rfp#4733",
            "TRANSFERRED rfp#4737",
            "STATUS_CHANGED To do",
            "PARENT_ADDED gitlab-org#23883",
            "READY_FOR_REVIEW",
            "PUSHED 1",
            "THREADS_RESOLVED",
            "REMOVED_FROM_MERGE_QUEUE",
            "AUTO_MERGE_ENABLED",
            "TIME_ADDED 33m 20s",
            // A ticked checklist item and approvals reset by a push are noise, as on GitLab's own page they are folded.
        )
    }

    @Test
    fun what_a_note_names_opens_where_it_can_be_told() = runTest {
        val events = items().filterIsInstance<TimelineItem.Event>()

        assertThat(events.first { it.event == ConversationEvent.BLOCKING_ADDED }.target).isEqualTo(IssueRef(home, 396273, isPullRequest = false))
        assertThat(events.first { it.event == ConversationEvent.MOVED_AWAY }.target).isEqualTo(IssueRef(RepoId("gitlab-org", "rfp", home.forge), 4733, isPullRequest = false))
        assertThat(events.first { it.event == ConversationEvent.RELATED_ADDED }.target)
            .isEqualTo(IssueRef(RepoId("gitlab-org/release", "tasks", home.forge), 4893, isPullRequest = false))
        // A group's own work item (its parent epic here) isn't a project's: nothing opens.
        assertThat(events.first { it.event == ConversationEvent.PARENT_ADDED }.target).isNull()
        val commit = items().filterIsInstance<TimelineItem.CommitReferenced>().first()
        assertThat(commit.commit).isEqualTo(
            ClosingCommit("2e5d38f0449252770f494820ee3d547c7bd59604", null, "https://gitlab.com/gitlab-org/gitlab/-/commit/2e5d38f0449252770f494820ee3d547c7bd59604"),
        )
    }

    @Test
    fun labels_milestones_and_weights_come_from_resource_events() = runTest {
        val items = items(notes = "[]") { segment -> if (segment.removePrefix("resource_").removeSuffix("_events") in setOf("label", "milestone", "weight")) captured("issue_${segment.removePrefix("resource_")}.json") else "[]" }

        assertThat(items.filterIsInstance<TimelineItem.Labeled>().map { it.added to it.label }).containsExactly(
            true to Label("maintenance::dependency", "7f8c8d"),
            true to Label("type::maintenance", "330066"),
            true to Label("[deprecated] Accepting merge requests", "69d100"),
        )
        val events = items.filterIsInstance<TimelineItem.Event>()
        assertThat(events.map { it.event to it.subject }).containsExactly(
            ConversationEvent.MILESTONED to "Next 1-3 releases",
            ConversationEvent.MILESTONED to "15.8",
            ConversationEvent.MILESTONED to "15.9",
            ConversationEvent.WEIGHT_SET to "4",
        ).inOrder()
        assertThat(events.last().actor?.login).isEqualTo("vitallium")
        // Weights and iterations are issues' only: a merge request doesn't ask.
        requests.clear()
        items(IssueRef(home, 1, isPullRequest = true), notes = "[]")
        assertThat(requests.map { it.url.encodedPath.substringAfterLast('/') }).containsNoneOf("resource_weight_events", "resource_iteration_events")
        assertThat(requests.map { it.url.encodedPath.substringAfterLast('/') }).containsAtLeast("resource_label_events", "resource_milestone_events", "resource_state_events")
    }

    @Test
    fun documented_notes_and_events_no_capture_holds_are_told() = runTest {
        // GitLab's own wording (app/services/system_notes, read 2026-11-05), and the documented shapes of the events.
        val sentences = listOf(
            "assigned to @alice and @bob and unassigned @carol",
            "requested review from @dave and removed review request for @erin",
            "changed title from **{-Old-} title** to **{+New+} title**",
            "marked this issue as a duplicate of #12",
            "marked #13 as a duplicate of this issue",
            "removed the relation with #14",
            "marked this item as blocked by other/project#15",
            "made the issue confidential",
            "made the issue visible to everyone",
            "cloned to group/elsewhere#16",
            "changed type to **Incident**",
            "added #17 as child item",
            "removed child item #17",
            "removed parent item #18",
            "unapproved this merge request",
            "marked this merge request as **draft**",
            "changed target branch from `main` to `release`",
            "deleted the `next` branch. This merge request now targets the `main` branch",
            "deleted source branch `fix/it`",
            "restored source branch `fix/it`",
            "enabled an automatic merge when all merge checks for 1a2b3c4d pass",
            "canceled the automatic merge",
            "added this merge request to the merge train at position 2",
            "subtracted 1h of time spent at 2026-11-01",
            "added time estimate of 2h",
            "changed time estimate to 3h from 2h",
            "removed time estimate of 3h",
            "mentioned in task #19",
            "mentioned in commit other@1a2b3c4d5e",
            "created merge request !20 to address this issue",
        )
        val notes = sentences.mapIndexed { i, body ->
            """{"id":${i + 1},"body":${kotlinx.serialization.json.JsonPrimitive(body)},"author":{"id":1,"username":"maintainer","name":"M"},"created_at":"2026-11-01T10:${"%02d".format(i)}:00Z","system":true}"""
        }.joinToString(",", "[", "]")
        val iteration = """[{"id":1,"user":{"id":1,"username":"maintainer","name":"M"},"created_at":"2026-11-01T11:00:00Z","iteration":{"title":null,"start_date":"2026-11-02","due_date":"2026-11-15"},"action":"add"}]"""

        val items = items(notes = notes) { if (it == "resource_iteration_events") iteration else "[]" }
        fun TimelineItem.said(): String = when (this) {
            is TimelineItem.Event -> "${event.name} ${subject.orEmpty()}".trim()
            is TimelineItem.Renamed -> "RENAMED $from -> $to"
            is TimelineItem.CrossReferenced -> "MENTIONED ${source.number}"
            is TimelineItem.CommitReferenced -> "COMMIT ${commit.webUrl}"
            else -> javaClass.simpleName
        }

        assertThat(items.map { it.said() }).containsExactly(
            "ASSIGNED alice", "ASSIGNED bob", "UNASSIGNED carol",
            "REVIEW_REQUESTED dave", "REVIEW_REQUEST_REMOVED erin",
            "RENAMED Old title -> New title",
            "MARKED_DUPLICATE #12", "DUPLICATE_ADDED #13", "RELATED_REMOVED #14", "DEPENDENCY_ADDED other/project#15",
            "CONFIDENTIAL", "NOT_CONFIDENTIAL", "CLONED group/elsewhere#16", "TYPE_SET Incident",
            "SUB_ISSUE_ADDED #17", "SUB_ISSUE_REMOVED #17", "PARENT_REMOVED #18",
            "APPROVAL_REVOKED", "CONVERTED_TO_DRAFT", "BASE_CHANGED release", "BASE_CHANGED main",
            "BRANCH_DELETED fix/it", "BRANCH_RESTORED fix/it", "AUTO_MERGE_ENABLED", "AUTO_MERGE_DISABLED", "ADDED_TO_MERGE_QUEUE",
            "TIME_REMOVED", "ESTIMATE_SET 2h", "ESTIMATE_SET 3h", "ESTIMATE_REMOVED",
            "MENTIONED 19", "COMMIT https://gitlab.com/gitlab-org/other/-/commit/1a2b3c4d5e", "LINKED !20",
            "ITERATION_SET 2026-11-02 – 2026-11-15",
        ).inOrder()
        assertThat(items.filterIsInstance<TimelineItem.Event>().first().actor).isEqualTo(ForgeUser("maintainer", "M", null))
    }

    @Test
    fun a_note_written_before_its_events_keeps_them_on_its_page_by_time() = runTest {
        val notes = """[{"id":1,"body":"Hello","created_at":"2026-11-01T10:00:00Z","system":false},{"id":2,"body":"Bye","created_at":"2026-11-01T12:00:00Z","system":false}]"""
        val labels = """[{"id":1,"created_at":"2026-11-01T11:00:00Z","label":{"name":"bug","color":"#d73a4a"},"action":"remove"}]"""

        val items = items(notes = notes) { if (it == "resource_label_events") labels else "[]" }

        assertThat(items.map { it.createdAt }).isInOrder()
        assertThat((items[1] as TimelineItem.Labeled).added).isFalse()
        assertThat(items[1].createdAt).isEqualTo(Instant.parse("2026-11-01T11:00:00Z"))
    }
}
