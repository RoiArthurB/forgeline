package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
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
            ForgeResult.Success(
                IssueDetails(
                    ref = ref,
                    title = issue.title,
                    body = issue.body?.ifBlank { null },
                    state = issue.state(),
                    stateReason = null,
                    author = issue.user?.toModel(),
                    labels = issue.labels.map { it.toModel() },
                    createdAt = instant(issue.createdAt) ?: Instant.EPOCH,
                    closedAt = instant(issue.closedAt),
                    comments = issue.comments,
                    reactions = counts,
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
                ),
            )
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
        val items = entries.mapNotNull { it.toItem(reviewStates) }
        val next = response.nextPage() ?: (page + 1).takeIf { entries.size == PAGE_SIZE }
        return ForgeResult.Success(TimelinePage(items, next))
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

    private fun TimelineJson.toItem(reviewStates: Map<Long, ReviewState>): TimelineItem? {
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
                    IssueRef(fr.arthurbrugiere.forgeline.core.model.RepoId(repo[0], repo[1], forge), source.number),
                    source.title,
                    source.pullRequest != null,
                    actor,
                    at,
                )
            }
            // Review requests, pushes, milestones, branch deletions... are noise in a conversation, as on GitHub.
            else -> null
        }
    }

    private companion object {
        const val PAGE_SIZE = 50

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
    val type: String,
    val body: String? = null,
    val user: UserJson? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val label: LabelJson? = null,
    @SerialName("old_title") val oldTitle: String? = null,
    @SerialName("new_title") val newTitle: String? = null,
    @SerialName("ref_issue") val refIssue: RefIssueJson? = null,
    @SerialName("review_id") val reviewId: Long? = null,
)
