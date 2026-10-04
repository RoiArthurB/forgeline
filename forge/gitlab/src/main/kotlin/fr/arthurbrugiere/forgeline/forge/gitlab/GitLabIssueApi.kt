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

    private fun projectPath(repo: RepoId): String = encodePath(repo.fullName)

    private fun targetKind(ref: IssueRef): String =
        if (ref.isPullRequest == true) "merge_requests" else "issues"

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = gitlabCall {
        if (ref.isPullRequest == true) {
            fetchMergeRequest(token, ref)
        } else if (ref.isPullRequest == false) {
            fetchIssue(token, ref)
        } else {
            // Ambiguous ref: try issue first, fall back to merge request if 404
            val issueResult = fetchIssue(token, ref)
            if (issueResult is ForgeResult.Failure && (issueResult.error as? ForgeError.Http)?.status == 404) {
                fetchMergeRequest(token, ref)
            } else {
                issueResult
            }
        }
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
        val kind = targetKind(ref)
        val response = httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), kind, ref.number.toString(), "notes",
            query = mapOf("sort" to "asc", "page" to page.toString(), "per_page" to "30"),
        )
        if (response.status != HttpStatusCode.OK) return@gitlabCall response.failure()

        val notes = response.body<List<GitLabNoteJson>>()
        val items = mutableListOf<TimelineItem>()
        for (note in notes) {
            if (!note.system) {
                items += note.toComment()
            } else {
                val event = parseSystemNoteEvent(note.body)
                if (event != null) {
                    items += TimelineItem.Event(event, note.author?.toForgeUser(), null, gitlabInstant(note.createdAt) ?: Instant.EPOCH)
                }
            }
        }
        ForgeResult.Success(TimelinePage(items, response.nextPage()))
    }

    private fun parseSystemNoteEvent(body: String): ConversationEvent? = when {
        body.contains("closed", ignoreCase = true) -> null // State change handled elsewhere
        body.contains("locked", ignoreCase = true) -> ConversationEvent.LOCKED
        body.contains("unlocked", ignoreCase = true) -> ConversationEvent.UNLOCKED
        body.contains("assigned", ignoreCase = true) -> ConversationEvent.ASSIGNED
        body.contains("unassigned", ignoreCase = true) -> ConversationEvent.UNASSIGNED
        body.contains("added milestone", ignoreCase = true) -> ConversationEvent.MILESTONED
        body.contains("removed milestone", ignoreCase = true) -> ConversationEvent.DEMILESTONED
        body.contains("due date", ignoreCase = true) -> ConversationEvent.DEADLINE_SET
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
        // Due date is only on issues in GitLab
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
        httpClient.gitlabApi(
            ref.repo.forge, token, "projects", projectPath(ref.repo), "issues", ref.number.toString(),
            method = HttpMethod.Delete,
        ).toResult { }
    }
}
