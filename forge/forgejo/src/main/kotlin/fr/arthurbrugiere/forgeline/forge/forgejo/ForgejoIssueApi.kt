package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import io.ktor.http.HttpMethod
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant

class ForgejoIssueApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : IssueApi {

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = forgejoCall {
        coroutineScope {
            val reactions = async { get(token, ref, "issues", ref.number.toString(), "reactions") }
            // The pull request's branches and size live on its own endpoint. Asked alongside the issue, before knowing
            // it's a pull request: a plain issue wastes one 404, a pull request saves a round trip to a far forge.
            val pullAnswer = async { get(token, ref, "pulls", ref.number.toString()) }
            val response = get(token, ref, "issues", ref.number.toString())
            if (response.status != HttpStatusCode.OK) return@coroutineScope response.failure()
            val issue = response.body<IssueJson>()
            val pull = if (issue.isPullRequest) {
                val details = pullAnswer.await()
                if (details.status != HttpStatusCode.OK) return@coroutineScope details.failure()
                details.body<PullJson>()
            } else {
                // Not a pull request: don't wait for the 404.
                pullAnswer.cancel()
                null
            }
            // No reactions answers null, not an empty list (Codeberg, 2026-09-29).
            val counts = reactions.await().takeIf { it.status == HttpStatusCode.OK }
                ?.let { runCatching { it.body<List<ReactionJson>?>() }.getOrNull() }.orEmpty()
                .mapNotNull { reaction(it.content) }.groupingBy { it }.eachCount()
            ForgeResult.Success(issue.toDetails(ref, pull, counts))
        }
    }

    /** The issue endpoint names pull requests too: no second call for the title. */
    override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = forgejoCall {
        get(token, ref, "issues", ref.number.toString()).toResult { body<IssueJson>().title }
    }

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = forgejoCall {
        coroutineScope {
            // A review entry doesn't say whether it approved; the pull request's reviews do. Asked alongside the
            // timeline, before knowing there are reviews: one round trip instead of two.
            val reviews = async { reviewStates(token, ref) }
            timelinePage(token, ref, page) { entries -> if (entries.any { it.type == "review" }) reviews.await() else emptyMap() }
                .also { reviews.cancel() }
        }
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "comments",
            method = HttpMethod.Post, body = buildJsonObject { put("body", body) },
        ).toResult {
            val created = body<TimelineJson>()
            TimelineItem.Comment(created.id, created.user?.toModel(), created.body.orEmpty(), instant(created.createdAt) ?: Instant.now(), emptyMap())
        }
    }

    override suspend fun create(token: String, repo: RepoId, title: String, body: String): ForgeResult<IssueDetails> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", repo.owner, repo.name, "issues",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("title", title)
                put("body", body)
            },
        ).toResult { body<IssueJson>().let { it.toDetails(IssueRef(repo, it.number), pull = null, reactions = emptyMap()) } }
    }

    /** A pull request is an issue to this endpoint: one call closes either. */
    /** Forgejo keeps no reason for closing: [reason] is left out. */
    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> =
        edit(token, ref) { put("state", if (open) "open" else "closed") }

    private suspend fun edit(token: String, ref: IssueRef, fields: JsonObjectBuilder.() -> Unit): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(),
            method = HttpMethod.Patch, body = buildJsonObject(fields),
        ).toResult { }
    }

    /** Forgejo's API neither locks a conversation nor moves an issue to another repository (16.0). */
    override val actions: Set<ConversationAction> = setOf(
        ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.PIN, ConversationAction.DELETE,
        ConversationAction.DUE_DATE, ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES,
    )

    /**
     * The day is sent at midnight UTC: Forgejo keeps the end of that day. Taking the date away has its own field,
     * since a missing date means "leave it".
     */
    override suspend fun setDueDate(token: String, ref: IssueRef, date: LocalDate?): ForgeResult<Unit> = edit(token, ref) {
        if (date == null) put("unset_due_date", true) else put("due_date", "${date}T00:00:00Z")
    }

    /** Everyone's time on the conversation, from its first page of entries, and the signed-in user's timer if it runs here. */
    override suspend fun timeTracking(token: String, ref: IssueRef): ForgeResult<TimeTracking> = forgejoCall {
        coroutineScope {
            val timers = async { httpClient.forgejoApi(forge, token, "user", "stopwatches") }
            val times = get(token, ref, "issues", ref.number.toString(), "times", query = FIRST_PAGE)
            if (times.status != HttpStatusCode.OK) {
                timers.cancel()
                return@coroutineScope times.failure()
            }
            val running = timers.await().takeIf { it.status == HttpStatusCode.OK }?.body<List<StopwatchJson>>().orEmpty()
                .firstOrNull { it.issue == ref.number && it.repo.equals(ref.repo.name, ignoreCase = true) && it.owner.equals(ref.repo.owner, ignoreCase = true) }
            ForgeResult.Success(TimeTracking(times.body<List<TrackedTimeJson>>().sumOf { it.time }, running?.let { instant(it.created) }))
        }
    }

    override suspend fun setTimerRunning(token: String, ref: IssueRef, running: Boolean): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "stopwatch", if (running) "start" else "stop",
            method = HttpMethod.Post,
        ).toResult { }
    }

    override suspend fun addTime(token: String, ref: IssueRef, seconds: Long): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "times",
            method = HttpMethod.Post, body = buildJsonObject { put("time", seconds) },
        ).toResult { }
    }

    override suspend fun dependencies(token: String, ref: IssueRef): ForgeResult<List<LinkedIssue>> = forgejoCall {
        get(token, ref, "issues", ref.number.toString(), "dependencies").toResult {
            body<List<IssueJson>>().map { issue ->
                // A dependency can live in another repository, which it then names.
                val repo = issue.repository?.let { RepoId(it.owner, it.name, forge) } ?: ref.repo
                LinkedIssue(IssueRef(repo, issue.number), issue.title, issue.state())
            }
        }
    }

    override suspend fun addDependency(token: String, ref: IssueRef, on: IssueRef): ForgeResult<Unit> = dependency(token, ref, on, HttpMethod.Post)

    override suspend fun removeDependency(token: String, ref: IssueRef, on: IssueRef): ForgeResult<Unit> = dependency(token, ref, on, HttpMethod.Delete)

    private suspend fun dependency(token: String, ref: IssueRef, on: IssueRef, method: HttpMethod): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "dependencies",
            method = method,
            body = buildJsonObject {
                put("owner", on.repo.owner)
                put("repo", on.repo.name)
                put("index", on.number)
            },
        ).toResult { }
    }

    // The lists below stop at their first page: a repository with more is rare, and none needs them all to triage.

    /** The repository's own labels, then its organisation's: both can be put on its conversations. */
    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = forgejoCall {
        coroutineScope {
            val shared = async { httpClient.forgejoApi(forge, token, "orgs", repo.owner, "labels", query = FIRST_PAGE) }
            val own = httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "labels", query = FIRST_PAGE)
            if (own.status != HttpStatusCode.OK) {
                shared.cancel()
                return@coroutineScope own.failure()
            }
            // An owner that is a person has no organisation labels: that answers 404.
            val fromOrganisation = shared.await().takeIf { it.status == HttpStatusCode.OK }?.body<List<LabelJson>>().orEmpty()
            ForgeResult.Success((own.body<List<LabelJson>>() + fromOrganisation).map { it.toModel() }.distinctBy { it.name })
        }
    }

    /** Forgejo takes names as well as ids here. */
    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "labels",
            method = HttpMethod.Put, body = buildJsonObject { putJsonArray("labels") { names.forEach { add(it) } } },
        ).toResult { }
    }

    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "assignees").toResult { body<List<UserJson>>().map { it.toModel() } }
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> =
        edit(token, ref) { putJsonArray("assignees") { logins.forEach { add(it) } } }

    override suspend fun milestones(token: String, repo: RepoId): ForgeResult<List<Milestone>> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "milestones", query = FIRST_PAGE + ("state" to "open"))
            .toResult { body<List<MilestoneJson>>().map { it.toModel() } }
    }

    /** No milestone is said with 0. */
    override suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?): ForgeResult<Unit> =
        edit(token, ref) { put("milestone", milestone?.id ?: 0) }

    override suspend fun isPinned(token: String, ref: IssueRef): ForgeResult<Boolean> = forgejoCall {
        get(token, ref, "issues", ref.number.toString()).toResult { body<PinnedJson>().pinOrder > 0 }
    }

    override suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "pin",
            method = if (pinned) HttpMethod.Post else HttpMethod.Delete,
        ).toResult { }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), method = HttpMethod.Delete).toResult { }
    }

    /**
     * Forgejo has no triage role: whoever can push manages the conversations too. A repository's own settings switch
     * time tracking and dependencies on, and may keep tracking to those who can push.
     */
    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = forgejoCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name).toResult {
            val answer = body<PermittedRepoJson>()
            val permissions = answer.permissions
            // Without its own tracker (issues kept elsewhere), neither applies.
            val tracker = answer.tracker
            val tracksTime = tracker != null && tracker.timeTracker && (permissions.push || !tracker.contributorsOnly)
            RepoRights(
                access = when {
                    permissions.admin -> RepoAccess.ADMIN
                    permissions.push -> RepoAccess.WRITE
                    else -> RepoAccess.NONE
                },
                switchedOff = setOfNotNull(
                    ConversationAction.TIME_TRACKING.takeUnless { tracksTime },
                    ConversationAction.DEPENDENCIES.takeUnless { tracker?.dependencies == true },
                ),
            )
        }
    }

    private fun IssueJson.toDetails(ref: IssueRef, pull: PullJson?, reactions: Map<Reaction, Int>) = IssueDetails(
        ref = ref,
        title = title,
        body = body?.ifBlank { null },
        state = state(),
        stateReason = null,
        author = user?.toModel(),
        labels = labels.map { it.toModel() },
        createdAt = instant(createdAt) ?: Instant.EPOCH,
        closedAt = instant(closedAt),
        comments = comments,
        reactions = reactions,
        isLocked = isLocked,
        assignees = assignees.orEmpty().map { it.toModel() },
        milestone = milestone?.takeIf { it.id > 0 }?.toModel(),
        // Kept as the end of a day; read in UTC, where it was sent as that day.
        dueDate = instant(dueDate)?.atOffset(ZoneOffset.UTC)?.toLocalDate(),
        pullRequest = pull?.let {
            PullRequestInfo(
                isDraft = it.draft,
                isMerged = it.merged,
                baseRef = it.base.ref,
                // A pull request from another repository only names a pull ref; its label says owner:branch.
                headRef = it.head.label?.takeIf { _ -> it.head.ref.startsWith("refs/pull/") } ?: it.head.ref,
                additions = it.additions ?: 0,
                deletions = it.deletions ?: 0,
                changedFiles = it.changedFiles ?: 0,
                commits = it.commits ?: 0,
            )
        },
    )

    private suspend fun timelinePage(
        token: String?,
        ref: IssueRef,
        page: Int,
        reviews: suspend (List<TimelineJson>) -> Map<Long, ReviewState>,
    ): ForgeResult<TimelinePage> {
        val response = get(token, ref, "issues", ref.number.toString(), "timeline", query = mapOf("page" to page.toString(), "limit" to "$PAGE_SIZE"))
        if (response.status != HttpStatusCode.OK) return response.failure()
        val entries = response.body<List<TimelineJson>>()
        val reviewStates = reviews(entries)
        val items = entries.mapNotNull { it.toItem(reviewStates, ref.repo) }
        val next = response.nextPage() ?: (page + 1).takeIf { entries.size == PAGE_SIZE }
        // The count covers every entry, shown or not: it gives the number of pages, not of items.
        val last = response.totalCount()?.let { (it + PAGE_SIZE - 1) / PAGE_SIZE }
        return ForgeResult.Success(TimelinePage(items, next, last))
    }

    private suspend fun reviewStates(token: String?, ref: IssueRef): Map<Long, ReviewState> {
        val response = get(token, ref, "pulls", ref.number.toString(), "reviews")
        if (response.status != HttpStatusCode.OK) return emptyMap()
        return response.body<List<ReviewJson>>().mapNotNull { review ->
            when (review.state) {
                "APPROVED" -> ReviewState.APPROVED
                "REQUEST_CHANGES" -> ReviewState.CHANGES_REQUESTED
                "COMMENT" -> ReviewState.COMMENTED
                else -> null
            }?.let { review.id to it }
        }.toMap()
    }

    private suspend fun get(token: String?, ref: IssueRef, vararg segments: String, query: Map<String, String> = emptyMap()): HttpResponse =
        httpClient.forgejoApi(forge, token, "repos", ref.repo.owner, ref.repo.name, *segments, query = query)

    private fun TimelineJson.toItem(reviewStates: Map<Long, ReviewState>, home: RepoId): TimelineItem? {
        val at = instant(createdAt) ?: return null
        val actor = user?.toModel()
        return when (type) {
            "comment" -> TimelineItem.Comment(id, actor, body.orEmpty(), at, emptyMap())
            "review" -> reviewId?.let { reviewStates[it] }?.let { TimelineItem.Review(reviewId, actor, it, body?.ifBlank { null }, at) }
            "close" -> TimelineItem.StateChanged(StateChange.CLOSED, actor, null, at)
            "reopen" -> TimelineItem.StateChanged(StateChange.REOPENED, actor, null, at)
            "merge_pull" -> TimelineItem.StateChanged(StateChange.MERGED, actor, null, at)
            // A label entry's body is "1" when added, empty when removed.
            "label" -> label?.let { TimelineItem.Labeled(body == "1", it.toModel(), actor, at) }
            "change_title" -> if (oldTitle != null && newTitle != null) TimelineItem.Renamed(oldTitle, newTitle, actor, at) else null
            "issue_ref", "pull_ref", "comment_ref" -> refIssue?.let { source ->
                val repo = source.repository?.fullName?.split('/')?.takeIf { it.size == 2 } ?: return null
                TimelineItem.CrossReferenced(
                    IssueRef(RepoId(repo[0], repo[1], forge), source.number),
                    source.title,
                    source.pullRequest != null,
                    actor,
                    at,
                )
            }
            "lock" -> TimelineItem.Event(ConversationEvent.LOCKED, actor, null, at)
            "unlock" -> TimelineItem.Event(ConversationEvent.UNLOCKED, actor, null, at)
            "pin" -> TimelineItem.Event(ConversationEvent.PINNED, actor, null, at)
            "unpin" -> TimelineItem.Event(ConversationEvent.UNPINNED, actor, null, at)
            "assignees" -> assignee?.let {
                TimelineItem.Event(if (removedAssignee) ConversationEvent.UNASSIGNED else ConversationEvent.ASSIGNED, actor, it.login, at)
            }
            // Setting one names it; taking it away names the one that was there.
            "milestone" -> milestone?.takeIf { it.id > 0 }?.let { TimelineItem.Event(ConversationEvent.MILESTONED, actor, it.title, at) }
                ?: oldMilestone?.takeIf { it.id > 0 }?.let { TimelineItem.Event(ConversationEvent.DEMILESTONED, actor, it.title, at) }
            // A new date is written alone, a changed one as "new|old", a removed one as the date it was.
            "added_deadline", "modified_deadline" -> TimelineItem.Event(ConversationEvent.DEADLINE_SET, actor, body?.substringBefore('|')?.ifBlank { null }, at)
            "removed_deadline" -> TimelineItem.Event(ConversationEvent.DEADLINE_REMOVED, actor, null, at)
            "start_tracking" -> TimelineItem.Event(ConversationEvent.TRACKING_STARTED, actor, null, at)
            "stop_tracking" -> TimelineItem.Event(ConversationEvent.TRACKING_STOPPED, actor, null, at)
            "add_time_manual" -> TimelineItem.Event(ConversationEvent.TIME_ADDED, actor, null, at)
            "add_dependency", "remove_dependency" -> dependentIssue?.let {
                TimelineItem.Event(
                    if (type == "add_dependency") ConversationEvent.DEPENDENCY_ADDED else ConversationEvent.DEPENDENCY_REMOVED,
                    actor,
                    // One in another repository names it.
                    it.repository?.fullName?.takeUnless { name -> name.equals(home.fullName, ignoreCase = true) }.orEmpty() + "#${it.number} ${it.title}",
                    at,
                )
            }
            // Review requests, pushes, branch deletions... are noise in a conversation, as on GitHub.
            else -> null
        }
    }

    private companion object {
        const val PAGE_SIZE = 50

        val FIRST_PAGE = mapOf("limit" to "$PAGE_SIZE")

        fun reaction(content: String): Reaction? = when (content) {
            "+1" -> Reaction.THUMBS_UP
            "-1" -> Reaction.THUMBS_DOWN
            "laugh" -> Reaction.LAUGH
            "hooray" -> Reaction.HOORAY
            "confused" -> Reaction.CONFUSED
            "heart" -> Reaction.HEART
            "rocket" -> Reaction.ROCKET
            "eyes" -> Reaction.EYES
            else -> null
        }
    }
}

@Serializable
private data class PinnedJson(@SerialName("pin_order") val pinOrder: Int = 0)

@Serializable
private data class PermissionsJson(val admin: Boolean = false, val push: Boolean = false)

@Serializable
private data class TrackerJson(
    @SerialName("enable_time_tracker") val timeTracker: Boolean = false,
    @SerialName("allow_only_contributors_to_track_time") val contributorsOnly: Boolean = false,
    @SerialName("enable_issue_dependencies") val dependencies: Boolean = false,
)

@Serializable
private data class PermittedRepoJson(
    val permissions: PermissionsJson = PermissionsJson(),
    @SerialName("internal_tracker") val tracker: TrackerJson? = null,
)

@Serializable
private data class TrackedTimeJson(val time: Long = 0)

@Serializable
private data class StopwatchJson(
    val created: String? = null,
    @SerialName("issue_index") val issue: Int = 0,
    @SerialName("repo_owner_name") val owner: String = "",
    @SerialName("repo_name") val repo: String = "",
)

@Serializable
private data class BranchJson(val ref: String, val label: String? = null)

@Serializable
private data class PullJson(
    val draft: Boolean = false,
    val merged: Boolean = false,
    val base: BranchJson,
    val head: BranchJson,
    val additions: Int? = null,
    val deletions: Int? = null,
    @SerialName("changed_files") val changedFiles: Int? = null,
    val commits: Int? = null,
)

@Serializable
private data class ReactionJson(val content: String)

@Serializable
private data class ReviewJson(val id: Long, val state: String)

@Serializable
private data class RefRepoJson(@SerialName("full_name") val fullName: String)

@Serializable
private data class RefIssueJson(
    val number: Int,
    val title: String,
    @SerialName("pull_request") val pullRequest: IssuePullJson? = null,
    val repository: RefRepoJson? = null,
)

@Serializable
private data class TimelineJson(
    val id: Long,
    // A created comment comes back without a type.
    val type: String = "comment",
    val body: String? = null,
    val user: UserJson? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val label: LabelJson? = null,
    @SerialName("old_title") val oldTitle: String? = null,
    @SerialName("new_title") val newTitle: String? = null,
    @SerialName("ref_issue") val refIssue: RefIssueJson? = null,
    @SerialName("review_id") val reviewId: Long? = null,
    val assignee: UserJson? = null,
    @SerialName("removed_assignee") val removedAssignee: Boolean = false,
    val milestone: MilestoneJson? = null,
    @SerialName("old_milestone") val oldMilestone: MilestoneJson? = null,
    @SerialName("dependent_issue") val dependentIssue: RefIssueJson? = null,
)
