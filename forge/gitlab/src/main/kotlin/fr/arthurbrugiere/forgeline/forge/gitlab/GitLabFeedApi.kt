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
            httpClient.gitlabApi(forge, token, "events", query = mapOf("per_page" to "$PAGE_SIZE", "page" to page.toString()))
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
        val action = when {
            actionName == "pushed to" || actionName == "pushed new" -> {
                val branch = pushData?.ref ?: ""
                FeedAction.Pushed(branch)
            }
            actionName == "created" && targetType == "Issue" -> {
                FeedAction.Issue(IssueAction.OPENED, targetIid ?: 0, targetTitle.orEmpty())
            }
            actionName == "closed" && targetType == "Issue" -> {
                FeedAction.Issue(IssueAction.CLOSED, targetIid ?: 0, targetTitle.orEmpty())
            }
            actionName == "reopened" && targetType == "Issue" -> {
                FeedAction.Issue(IssueAction.REOPENED, targetIid ?: 0, targetTitle.orEmpty())
            }
            actionName == "created" && targetType == "MergeRequest" -> {
                FeedAction.PullRequest(PullRequestAction.OPENED, targetIid ?: 0, targetTitle)
            }
            actionName == "closed" && targetType == "MergeRequest" -> {
                FeedAction.PullRequest(PullRequestAction.CLOSED, targetIid ?: 0, targetTitle)
            }
            actionName == "accepted" && targetType == "MergeRequest" -> {
                FeedAction.PullRequest(PullRequestAction.MERGED, targetIid ?: 0, targetTitle)
            }
            actionName == "reopened" && targetType == "MergeRequest" -> {
                FeedAction.PullRequest(PullRequestAction.REOPENED, targetIid ?: 0, targetTitle)
            }
            actionName == "commented on" -> {
                val isPr = targetType == "MergeRequest" || targetType == "DiffNote"
                FeedAction.Commented(targetIid ?: 0, targetTitle, isPullRequest = isPr)
            }
            actionName == "approved" && targetType == "MergeRequest" -> {
                FeedAction.Reviewed(targetIid ?: 0, ReviewState.APPROVED)
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
