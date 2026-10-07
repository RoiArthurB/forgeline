package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

class GitLabIssueApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : IssueApi {

    override val actions: Set<ConversationAction> = setOf(
        ConversationAction.LABELS,
        ConversationAction.ASSIGNEES,
        ConversationAction.MILESTONE,
        ConversationAction.LOCK,
        ConversationAction.DUE_DATE,
        ConversationAction.TIME_TRACKING,
        ConversationAction.DELETE,
    )

    /** Merge requests have no due date, and only issues are deleted from here. */
    override val issueOnly: Set<ConversationAction> = setOf(ConversationAction.DUE_DATE, ConversationAction.DELETE)

    private fun projectPath(repo: RepoId): String = encodePath(repo.fullName)

    /** GitLab numbers merge requests apart from issues: a reference that doesn't say which is an issue, here as everywhere. */
    private fun targetKind(ref: IssueRef): String =
        if (ref.isPullRequest == true) "merge_requests" else "issues"

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = gitlabCall {
        // No guessing: a merge request found where an issue was asked for would be read here and written to there.
        if (ref.isPullRequest == true) fetchMergeRequest(token, ref) else fetchIssue(token, ref)
    }

    private suspend fun fetchIssue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = coroutineScope {
        val reactionsDeferred = async {
            httpClient.gitlabApi(ref.repo.forge, token, "projects", projectPath(ref.repo), "issues", ref.number.toString(), "award_emoji")
        }
        val response = httpClient.gitlabApi(ref.repo.forge, token, "projects", projectPath(ref.repo), "issues", ref.number.toString())
        if (response.status != HttpStatusCode.OK) return@coroutineScope response.failure()

        val issueJson = response.body<GitLabIssueJson>()
        val reactionsRes = reactionsDeferred.await()
        val reactions = if (reactionsRes.status == HttpStatusCode.OK) {
            parseReactions(reactionsRes.body<List<GitLabAwardEmojiJson>>())
        } else {
            emptyMap()
        }
        ForgeResult.Success(issueJson.toDetails(ref.repo, reactions))
    }

    private suspend fun fetchMergeRequest(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = coroutineScope {
        val reactionsDeferred = async {
            httpClient.gitlabApi(ref.repo.forge, token, "projects", projectPath(ref.repo), "merge_requests", ref.number.toString(), "award_emoji")
        }
        val response = httpClient.gitlabApi(ref.repo.forge, token, "projects", projectPath(ref.repo), "merge_requests", ref.number.toString())
        if (response.status != HttpStatusCode.OK) return@coroutineScope response.failure()

        val mrJson = response.body<GitLabMergeRequestJson>()
        val reactionsRes = reactionsDeferred.await()
        val reactions = if (reactionsRes.status == HttpStatusCode.OK) {
            parseReactions(reactionsRes.body<List<GitLabAwardEmojiJson>>())
        } else {
            emptyMap()
        }
        ForgeResult.Success(mrJson.toDetails(ref.repo, reactions))
    }

    private fun parseReactions(emojis: List<GitLabAwardEmojiJson>): Map<Reaction, Int> {
        val counts = mutableMapOf<Reaction, Int>()
        for (emoji in emojis) {
            val reaction = emoji.toReaction() ?: continue
            counts[reaction] = (counts[reaction] ?: 0) + 1
        }
        return counts
    }

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = gitlabCall {
        coroutineScope {
            val kind = targetKind(ref)
            // Closing, reopening and merging are kept apart from the notes: asked alongside, not after.
            val stateEvents = async {
                httpClient.gitlabApi(
                    ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(), "resource_state_events",
                    query = mapOf("per_page" to "100"),
                )
            }
            val response = httpClient.gitlabApi(
                ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(), "notes",
                query = mapOf("sort" to "asc", "page" to page.toString(), "per_page" to "$NOTES_PER_PAGE"),
            )
            if (response.status != HttpStatusCode.OK) {
                stateEvents.cancel()
                return@coroutineScope response.failure()
            }
            val notes = response.body<List<GitLabNoteJson>>().mapNotNull { it.toTimelineItem() }
            val nextPage = response.nextPage()
            // Each page takes the changes of its own stretch of time: the first from the start, the last to the end.
            val from = if (page == 1) Instant.MIN else notes.firstOrNull()?.createdAt ?: Instant.MIN
            val until = if (nextPage == null) Instant.MAX else notes.lastOrNull()?.createdAt ?: Instant.MIN
            val events = stateEvents.await()
            // The notes are the conversation: state changes that can't be had or read only leave it without them.
            val changes = if (events.status == HttpStatusCode.OK) {
                runCatching { events.body<List<GitLabStateEventJson>>() }.getOrDefault(emptyList())
                    .mapNotNull { it.toTimelineItem() }.filter { it.createdAt >= from && it.createdAt <= until }
            } else {
                emptyList()
            }
            ForgeResult.Success(TimelinePage((notes + changes).sortedBy { it.createdAt ?: Instant.MIN }, nextPage, response.lastPage()))
        }
    }

    private fun GitLabNoteJson.toTimelineItem(): TimelineItem? {
        if (!system) return toComment()
        val (event, subject) = systemNoteEvent(body) ?: return null
        return TimelineItem.Event(event, author?.toForgeUser(), subject, gitlabInstant(createdAt) ?: Instant.EPOCH)
    }

    /**
     * What a system note says happened, with whom or what it was about. GitLab writes them as sentences, in English
     * whatever the reader's language (read on gitlab.com, 2026-10-04): "assigned to @alice", "unassigned @alice",
     * "locked the discussion in this issue", "changed due date to March 14, 2031", "removed due date March 14, 2031",
     * "added 1h of time spent at ...". The longer word is looked for first: "unlocked" holds "locked".
     */
    private fun systemNoteEvent(body: String): Pair<ConversationEvent, String?>? = when {
        body.startsWith("unassigned ") -> ConversationEvent.UNASSIGNED to body.substringAfter('@', "").substringBefore(' ').ifEmpty { null }
        body.startsWith("assigned to ") -> ConversationEvent.ASSIGNED to body.substringAfter('@', "").substringBefore(' ').ifEmpty { null }
        body.startsWith("unlocked ") -> ConversationEvent.UNLOCKED to null
        body.startsWith("locked ") -> ConversationEvent.LOCKED to null
        body.startsWith("changed due date to ") -> ConversationEvent.DEADLINE_SET to body.removePrefix("changed due date to ")
        body.startsWith("removed due date") -> ConversationEvent.DEADLINE_REMOVED to null
        body.startsWith("added ") && " of time spent" in body -> ConversationEvent.TIME_ADDED to body.removePrefix("added ").substringBefore(" of time spent")
        body.startsWith("changed milestone to ") -> ConversationEvent.MILESTONED to body.removePrefix("changed milestone to ").removePrefix("%")
        body.startsWith("removed milestone") -> ConversationEvent.DEMILESTONED to null
        else -> null
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = gitlabCall {
        val kind = targetKind(ref)
        val payload = buildJsonObject { put("body", body) }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(), "notes",
            method = HttpMethod.Post,
            body = payload,
        ).toResult { body<GitLabNoteJson>().toComment() }
    }

    override suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), targetKind(ref), ref.number.toString(), "notes", commentId.toString(),
            method = HttpMethod.Put,
            body = buildJsonObject { put("body", body) },
        ).toResult { }
    }

    override suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), targetKind(ref), ref.number.toString(), "notes", commentId.toString(),
            method = HttpMethod.Delete,
        ).toResult { }
    }

    /** GitLab calls the text of an issue or a merge request its description. */
    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), targetKind(ref), ref.number.toString(),
            method = HttpMethod.Put,
            body = buildJsonObject {
                put("title", title)
                put("description", body)
            },
        ).toResult { }
    }

    override suspend fun create(token: String, repo: RepoId, title: String, body: String): ForgeResult<IssueDetails> = gitlabCall {
        val payload = buildJsonObject {
            put("title", title)
            put("description", body)
        }
        httpClient.gitlabApi(
            repo.forge, token, "projects", projectPath(repo), "issues",
            method = HttpMethod.Post,
            body = payload,
        ).toResult { body<GitLabIssueJson>().toDetails(repo) }
    }

    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> = gitlabCall {
        val kind = targetKind(ref)
        val payload = buildJsonObject {
            put("state_event", if (open) "reopen" else "close")
        }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(),
            method = HttpMethod.Put,
            body = payload,
        ).toResult { }
    }

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = gitlabCall {
        val response = httpClient.gitlabApi(repo.forge, token, "projects", projectPath(repo))
        if (response.status != HttpStatusCode.OK) return@gitlabCall response.failure()

        val project = response.body<GitLabProjectJson>()
        val accessLevel = maxOf(
            project.permissions?.projectAccess?.accessLevel ?: 0,
            project.permissions?.groupAccess?.accessLevel ?: 0,
        )
        val access = when {
            accessLevel >= 40 -> RepoAccess.ADMIN
            accessLevel >= 30 -> RepoAccess.WRITE
            accessLevel >= 20 -> RepoAccess.TRIAGE
            else -> RepoAccess.NONE
        }
        ForgeResult.Success(RepoRights(access))
    }

    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = gitlabCall {
        httpClient.gitlabApi(repo.forge, token, "projects", projectPath(repo), "labels", query = mapOf("per_page" to "100"))
            .toResult { body<List<GitLabLabelJson>>().map { it.toModel() } }
    }

    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = gitlabCall {
        val kind = targetKind(ref)
        val payload = buildJsonObject {
            put("labels", names.joinToString(","))
        }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(),
            method = HttpMethod.Put,
            body = payload,
        ).toResult { }
    }

    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = gitlabCall {
        httpClient.gitlabApi(repo.forge, token, "projects", projectPath(repo), "users", query = mapOf("per_page" to "100"))
            .toResult { body<List<GitLabUserJson>>().map { it.toForgeUser() } }
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> = gitlabCall {
        val kind = targetKind(ref)
        // Look up member IDs for these usernames
        val membersRes = httpClient.gitlabApi(ref.repo.forge, token, "projects", projectPath(ref.repo), "users", query = mapOf("per_page" to "100"))
        if (membersRes.status != HttpStatusCode.OK) return@gitlabCall membersRes.failure()
        val members = membersRes.body<List<GitLabUserJson>>()
        val assigneeIds = logins.mapNotNull { login -> members.firstOrNull { it.username.equals(login, ignoreCase = true) }?.id }

        val payload = buildJsonObject {
            put("assignee_ids", assigneeIds.joinToString(","))
        }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(),
            method = HttpMethod.Put,
            body = payload,
        ).toResult { }
    }

    override suspend fun milestones(token: String, repo: RepoId): ForgeResult<List<Milestone>> = gitlabCall {
        httpClient.gitlabApi(repo.forge, token, "projects", projectPath(repo), "milestones", query = mapOf("state" to "active", "per_page" to "100"))
            .toResult { body<List<GitLabMilestoneJson>>().map { Milestone(it.id, it.title) } }
    }

    override suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?): ForgeResult<Unit> = gitlabCall {
        val kind = targetKind(ref)
        val payload = buildJsonObject {
            put("milestone_id", milestone?.id ?: 0L)
        }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(),
            method = HttpMethod.Put,
            body = payload,
        ).toResult { }
    }

    override suspend fun setLocked(token: String, ref: IssueRef, locked: Boolean): ForgeResult<Unit> = gitlabCall {
        val kind = targetKind(ref)
        val payload = buildJsonObject {
            put("discussion_locked", locked)
        }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(),
            method = HttpMethod.Put,
            body = payload,
        ).toResult { }
    }

    override suspend fun setDueDate(token: String, ref: IssueRef, date: LocalDate?): ForgeResult<Unit> = gitlabCall {
        // Only issues have one. The issue with a merge request's number is another conversation: never written to.
        if (ref.isPullRequest == true) return@gitlabCall ForgeResult.Failure(ForgeError.Unsupported)
        val payload = buildJsonObject {
            put("due_date", date?.toString() ?: "")
        }
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), "issues", ref.number.toString(),
            method = HttpMethod.Put,
            body = payload,
        ).toResult { }
    }

    override suspend fun timeTracking(token: String, ref: IssueRef): ForgeResult<TimeTracking> = gitlabCall {
        val kind = targetKind(ref)
        httpClient.gitlabApi(ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(), "time_stats")
            .toResult {
                val stats = body<GitLabTimeStatsJson>()
                TimeTracking(totalSeconds = stats.totalTimeSpent, runningSince = null)
            }
    }

    override suspend fun addTime(token: String, ref: IssueRef, seconds: Long): ForgeResult<Unit> = gitlabCall {
        val kind = targetKind(ref)
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(), "add_spent_time",
            method = HttpMethod.Post,
            query = mapOf("duration" to "${seconds}s"),
        ).toResult { }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = gitlabCall {
        if (ref.isPullRequest == true) return@gitlabCall ForgeResult.Failure(ForgeError.Unsupported)
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), "issues", ref.number.toString(),
            method = HttpMethod.Delete,
        ).toResult { }
    }

    private companion object {
        const val NOTES_PER_PAGE = 30
    }
}
