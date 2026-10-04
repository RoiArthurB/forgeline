package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class GitLabFeedApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : FeedApi {

    private val projectCache = ConcurrentHashMap<Long, RepoId>()

    override suspend fun receivedEvents(token: String?, login: String, page: Int, ifModifiedSince: String?): ForgeResult<FeedPage> = gitlabCall {
        val response = if (token != null) {
            // Everything in the reader's projects, not only what they did themselves (which is all `/events` gives alone).
            httpClient.gitlabApi(forge, token, "events", query = mapOf("scope" to "all", "per_page" to "$PAGE_SIZE", "page" to page.toString()))
        } else {
            httpClient.gitlabApi(forge, null, "users", login, "events", query = mapOf("per_page" to "$PAGE_SIZE", "page" to page.toString()))
        }
        if (!response.status.isSuccess()) return@gitlabCall response.failure()

        val events = response.body<List<GitLabEventJson>>()
        resolveProjects(token, events)

        val feedEvents = events.mapNotNull { it.toModel() }
        val nextPage = response.nextPage() ?: (page + 1).takeIf { events.size == PAGE_SIZE }
        ForgeResult.Success(FeedPage(feedEvents, nextPage = nextPage))
    }

    private suspend fun resolveProjects(token: String?, events: List<GitLabEventJson>) {
        val missing = events.mapNotNull { it.projectId }.distinct().filter { !projectCache.containsKey(it) }
        if (missing.isEmpty()) return

        val gate = Semaphore(CONCURRENCY)
        coroutineScope {
            missing.map { projectId ->
                async {
                    gate.withPermit {
                        val res = httpClient.gitlabApi(forge, token, "projects", projectId.toString())
                        if (res.status == HttpStatusCode.OK) {
                            val proj = res.body<GitLabProjectJson>()
                            projectCache[projectId] = proj.repoId(forge)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun GitLabEventJson.toModel(): FeedEvent? {
        val pid = projectId ?: return null
        val repo = projectCache[pid] ?: return null
        val actUser = author?.toForgeUser() ?: ForgeUser(login = authorUsername ?: "user", name = null, avatarUrl = null)
        // An issue or merge request's own number fits an Int; anything larger is the id of something else.
        val number = targetIid?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        val title = targetTitle.orEmpty()
        val action = when (actionName) {
            "pushed to", "pushed new" -> FeedAction.Pushed(pushData?.ref ?: "")
            // GitLab says "opened" for a new one and for one reopened alike (read on gitlab.com, 2026-10-04).
            "opened", "created", "reopened" -> when (targetType) {
                "Issue", "WorkItem" -> number?.let { FeedAction.Issue(if (actionName == "reopened") IssueAction.REOPENED else IssueAction.OPENED, it, title) }
                "MergeRequest" -> number?.let { FeedAction.PullRequest(if (actionName == "reopened") PullRequestAction.REOPENED else PullRequestAction.OPENED, it, targetTitle) }
                else -> null
            }
            "closed" -> when (targetType) {
                "Issue", "WorkItem" -> number?.let { FeedAction.Issue(IssueAction.CLOSED, it, title) }
                "MergeRequest" -> number?.let { FeedAction.PullRequest(PullRequestAction.CLOSED, it, targetTitle) }
                else -> null
            }
            "accepted" -> number?.takeIf { targetType == "MergeRequest" }?.let { FeedAction.PullRequest(PullRequestAction.MERGED, it, targetTitle) }
            "approved" -> number?.takeIf { targetType == "MergeRequest" }?.let { FeedAction.Reviewed(it, ReviewState.APPROVED) }
            // What was commented on is the note's to say: the event's own target is the note.
            "commented on" -> when (note?.noteableType) {
                "Issue", "WorkItem" -> note.noteableIid?.let { FeedAction.Commented(it, targetTitle, isPullRequest = false) }
                "MergeRequest" -> note.noteableIid?.let { FeedAction.Commented(it, targetTitle, isPullRequest = true) }
                else -> null
            }
            else -> null
        } ?: return null

        val at = gitlabInstant(createdAt) ?: Instant.EPOCH
        return FeedEvent(
            id = (id ?: 0L).toString(),
            actor = actUser,
            repo = repo,
            action = action,
            createdAt = at,
        )
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val CONCURRENCY = 4
    }
}
