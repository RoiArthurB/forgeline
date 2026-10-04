package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class GitLabNotificationsApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : NotificationsApi {

    override val supportsDone: Boolean = true

    /** Where each todo last listed points: unsubscribing is from its conversation, which a todo's id doesn't say. */
    private val targets = ConcurrentHashMap<String, IssueRef>()

    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> = gitlabCall {
        val first = page(token, 1)
        if (!first.status.isSuccess()) return@gitlabCall first.failure()
        val firstBatch = first.body<List<GitLabTodoJson>>()
        val total = first.totalCount()
        val pages = total?.let { (it + PAGE_SIZE - 1) / PAGE_SIZE }
            ?: first.nextPage()?.let { 2 }
            ?: if (firstBatch.size == PAGE_SIZE) 2 else 1
        val rest = coroutineScope {
            (2..minOf(pages, maxPages)).map { number ->
                async { page(token, number) }
            }.awaitAll()
        }
        rest.firstOrNull { !it.status.isSuccess() }?.let { return@gitlabCall it.failure() }
        val todos = firstBatch + rest.flatMap { it.body<List<GitLabTodoJson>>() }
        val threads = todos.mapNotNull { it.toModel() }
        threads.forEach { thread -> thread.subject?.let { targets[thread.id] = it } }
        ForgeResult.Success(NotificationsSync(threads, null, POLL_INTERVAL_SECONDS))
    }

    private suspend fun page(token: String, number: Int) = httpClient.gitlabApi(
        forge, token, "todos",
        query = mapOf("state" to "pending", "per_page" to "$PAGE_SIZE", "page" to number.toString()),
    )

    /**
     * A todo is pending or done, nothing in between: there is no "read" to tell GitLab about. Reading one leaves it
     * where it is, so opening a thread never takes it off the reader's list: only done does.
     */
    override suspend fun markRead(token: String, threadId: String): ForgeResult<Unit> = ForgeResult.Success(Unit)

    override suspend fun markDone(token: String, threadId: String): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(forge, token, "todos", threadId, "mark_as_done", method = HttpMethod.Post)
            .toResult { }
    }

    /** Leaves the conversation the todo is about, then clears the todo. */
    override suspend fun unsubscribe(token: String, threadId: String): ForgeResult<Unit> = gitlabCall {
        // Not listed since the app started: looked up among the pending ones.
        val target = targets[threadId] ?: run {
            val listed = page(token, 1)
            if (!listed.status.isSuccess()) return@gitlabCall listed.failure()
            listed.body<List<GitLabTodoJson>>().firstOrNull { it.id.toString() == threadId }?.toModel()?.subject
        }
        if (target != null) {
            val kind = if (target.isPullRequest == true) "merge_requests" else "issues"
            val left = httpClient.gitlabApi(
                forge, token, "projects", encodePath(target.repo.fullName), kind, target.number.toString(), "unsubscribe", method = HttpMethod.Post,
            )
            // 304: not subscribed to begin with, which is where this was going.
            if (!left.status.isSuccess() && left.status != HttpStatusCode.NotModified) return@gitlabCall left.failure()
        }
        markDone(token, threadId)
    }

    override suspend fun subjectStates(token: String, subjects: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>> = gitlabCall {
        if (subjects.isEmpty()) return@gitlabCall ForgeResult.Success(emptyMap())
        val gate = Semaphore(CONCURRENCY)
        val answers = coroutineScope {
            subjects.distinct().map { ref ->
                async {
                    gate.withPermit {
                        val kind = if (ref.isPullRequest == true) "merge_requests" else "issues"
                        val response = httpClient.gitlabApi(ref.repo.forge, token, "projects", encodePath(ref.repo.fullName), kind, ref.number.toString())
                        if (response.status != HttpStatusCode.OK) return@withPermit null
                        val state = if (ref.isPullRequest == true) {
                            val mr = response.body<GitLabMergeRequestJson>()
                            // Where it ended first: one merged with "Draft:" still in its title is merged.
                            when {
                                mr.state == "merged" -> SubjectState.MERGED
                                mr.state == "closed" -> SubjectState.CLOSED
                                mr.isDraft -> SubjectState.DRAFT
                                mr.state == "opened" -> SubjectState.OPEN
                                else -> null
                            }
                        } else {
                            val issue = response.body<GitLabIssueJson>()
                            when (issue.state) {
                                "opened" -> SubjectState.OPEN
                                "closed" -> SubjectState.CLOSED
                                else -> null
                            }
                        }
                        state?.let { ref to it }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        ForgeResult.Success(answers)
    }

    private fun GitLabTodoJson.toModel(): NotificationThread? {
        val proj = project ?: return null
        val fullName = proj.pathWithNamespace
        val owner = if (fullName.contains('/')) fullName.substringBeforeLast('/') else fullName
        val name = if (fullName.contains('/')) fullName.substringAfterLast('/') else fullName
        val repo = RepoId(owner, name, forge)

        val type = when (targetType) {
            "Issue" -> SubjectType.ISSUE
            "MergeRequest" -> SubjectType.PULL_REQUEST
            "Commit" -> SubjectType.COMMIT
            else -> SubjectType.OTHER
        }

        val reason = when (actionName) {
            "assigned" -> NotificationReason.ASSIGN
            "mentioned", "directly_addressed" -> NotificationReason.MENTION
            "build_failed" -> NotificationReason.CI_ACTIVITY
            "marked" -> NotificationReason.MANUAL
            "approval_required", "review_requested" -> NotificationReason.REVIEW_REQUESTED
            "unmergeable" -> NotificationReason.STATE_CHANGE
            else -> NotificationReason.SUBSCRIBED
        }

        val subjectState = when (type) {
            SubjectType.PULL_REQUEST -> when {
                target?.state == "merged" -> SubjectState.MERGED
                target?.state == "closed" -> SubjectState.CLOSED
                target?.draft == true -> SubjectState.DRAFT
                target?.state == "opened" -> SubjectState.OPEN
                else -> null
            }
            SubjectType.ISSUE -> when (target?.state) {
                "opened" -> SubjectState.OPEN
                "closed" -> SubjectState.CLOSED
                else -> null
            }
            else -> null
        }

        return NotificationThread(
            id = id.toString(),
            repo = repo,
            title = target?.title ?: body ?: "",
            type = type,
            number = target?.iid,
            reason = reason,
            unread = state == "pending",
            updatedAt = gitlabInstant(updatedAt) ?: gitlabInstant(createdAt) ?: Instant.EPOCH,
            ownerAvatarUrl = author?.avatarUrl,
            state = subjectState,
        )
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val POLL_INTERVAL_SECONDS = 60
        const val CONCURRENCY = 4
    }
}
