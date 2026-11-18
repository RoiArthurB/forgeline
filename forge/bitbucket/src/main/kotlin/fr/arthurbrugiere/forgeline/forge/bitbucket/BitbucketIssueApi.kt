package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedDiffstats
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedWorkspaceMemberships
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Pullrequest
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PullrequestComment
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * A pull request's conversation on Bitbucket: its description, its comments (those on no line: the others are its
 * review's threads, in [BitbucketPullRequestApi]) and what happened to it. Bitbucket has no issues since 2026, so a
 * conversation here is always a pull request's; opening an issue is Unsupported, and so is reopening a declined pull
 * request, which Bitbucket's API can't do.
 */
class BitbucketIssueApi(
    private val http: HttpClient,
    private val people: BitbucketPeople = BitbucketPeople(),
    private val repos: BitbucketRepoApi = BitbucketRepoApi(http, people),
) : IssueApi {
    override val actions: Set<ConversationAction> = setOf(ConversationAction.REVIEWERS, ConversationAction.DRAFT)

    override val editsComments: Boolean = true

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = forgeCall {
        coroutineScope {
            // How much it changes is on a page of its own.
            val stats = async { changeStats(token, ref) }
            http.bitbucket(token, *ref.pull()).toResult { body<Pullrequest>() }.map { it.toDetails(ref, stats.await()) }
        }
    }

    override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = forgeCall {
        http.bitbucket(token, *ref.pull(), query = listOf("fields" to "title")).toResult { body<Pullrequest>().title.orEmpty() }
    }

    private data class Stats(val additions: Int, val deletions: Int, val files: Int)

    private suspend fun changeStats(token: String?, ref: IssueRef): Stats? = runCatching {
        val answer = http.bitbucket(token, *ref.pull("diffstat"), query = listOf("pagelen" to "500"))
        if (!answer.status.isSuccess()) return null
        val files = answer.body<PaginatedDiffstats>()
        val values = files.propertyValues.orEmpty()
        Stats(values.sumOf { it.linesAdded ?: 0 }, values.sumOf { it.linesRemoved ?: 0 }, files.propertySize?.toInt() ?: values.size)
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }

    private fun Pullrequest.toDetails(ref: IssueRef, stats: Stats?): IssueDetails {
        val approved = participants.orEmpty().filter { it.approved == true || it.state != null }.mapNotNull { it.user?.uuid }.toSet()
        return IssueDetails(
            ref = ref.copy(isPullRequest = true),
            title = title.orEmpty(),
            body = summary?.raw?.takeIf { it.isNotBlank() },
            state = issueState(),
            // Why it was declined, as its decliner said.
            stateReason = when (state) {
                "DECLINED" -> "declined"
                "SUPERSEDED" -> "superseded"
                else -> null
            },
            author = people.user(author),
            labels = emptyList(),
            createdAt = bitbucketInstant(createdOn) ?: Instant.EPOCH,
            closedAt = bitbucketInstant(updatedOn).takeIf { state != "OPEN" },
            comments = commentCount ?: 0,
            reactions = emptyMap(),
            pullRequest = PullRequestInfo(
                isDraft = draft == true,
                isMerged = state == "MERGED",
                baseRef = destination?.branch?.name.orEmpty(),
                headRef = source?.branch?.name.orEmpty(),
                additions = stats?.additions ?: 0,
                deletions = stats?.deletions ?: 0,
                changedFiles = stats?.files ?: 0,
                // Bitbucket doesn't count them on the pull request itself.
                commits = 0,
                // Those asked who haven't said yet.
                reviewers = reviewers.orEmpty().filter { it.uuid !in approved }.mapNotNull { people.user(it) },
            ),
        )
    }

    /**
     * The conversation, oldest first: the comments on no line, the approvals and requests for changes, and what its
     * updates changed (its title, its branch moving on, its merge or decline). Bitbucket pages its activity by an
     * opaque cursor: the whole of it comes on the first page.
     */
    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = forgeCall {
        if (page > 1) return@forgeCall ForgeResult.Success(TimelinePage(emptyList(), null))
        val activity = mutableListOf<Activity>()
        var answer = http.bitbucket(token, *ref.pull("activity"), query = listOf("pagelen" to "50"))
        var pages = 0
        while (true) {
            if (answer.status != HttpStatusCode.OK) return@forgeCall answer.failure()
            val read = answer.body<ActivityPage>()
            activity += read.values
            val next = read.next?.takeIf { ++pages < MAX_ACTIVITY_PAGES } ?: break
            answer = http.bitbucketAt(token, next)
        }
        ForgeResult.Success(TimelinePage(timelineOf(activity.asReversed()), null))
    }

    /** What [activity] (oldest first) tells, as the app's timeline. An update is a snapshot: what changed is told against the one before. */
    private fun timelineOf(activity: List<Activity>): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
        var before: ActivityUpdate? = null
        activity.forEach { entry ->
            entry.comment?.takeIf { it.inline == null && it.deleted != true && it.parent == null }?.let { items += it.toItem() }
            entry.approval?.let { items += TimelineItem.Review(0, people.user(it.user), ReviewState.APPROVED, null, bitbucketInstant(it.date)) }
            entry.changesRequested?.let { items += TimelineItem.Review(0, people.user(it.user), ReviewState.CHANGES_REQUESTED, null, bitbucketInstant(it.date)) }
            entry.update?.let { update ->
                val at = bitbucketInstant(update.date) ?: return@let
                val actor = people.user(update.author)
                val previous = before
                if (previous != null) {
                    if (update.title != null && previous.title != null && update.title != previous.title) items += TimelineItem.Renamed(previous.title, update.title, actor, at)
                    val head = update.source?.commit?.hash
                    if (head != null && previous.source?.commit?.hash != null && head != previous.source.commit.hash) items += TimelineItem.Event(ConversationEvent.PUSHED, actor, null, at)
                    val base = update.destination?.branch?.name
                    if (base != null && previous.destination?.branch?.name != null && base != previous.destination.branch.name) items += TimelineItem.Event(ConversationEvent.BASE_CHANGED, actor, base, at)
                    if (update.draft == false && previous.draft == true) items += TimelineItem.Event(ConversationEvent.READY_FOR_REVIEW, actor, null, at)
                    if (update.draft == true && previous.draft == false) items += TimelineItem.Event(ConversationEvent.CONVERTED_TO_DRAFT, actor, null, at)
                    if (update.description != null && previous.description != null && update.description != previous.description) items += TimelineItem.Event(ConversationEvent.DESCRIPTION_CHANGED, actor, null, at)
                }
                if (update.state != previous?.state) {
                    when (update.state) {
                        "MERGED" -> items += TimelineItem.StateChanged(StateChange.MERGED, actor, null, at)
                        "DECLINED" -> items += TimelineItem.StateChanged(StateChange.CLOSED, actor, "declined", at)
                        "SUPERSEDED" -> items += TimelineItem.StateChanged(StateChange.CLOSED, actor, "superseded", at)
                        "OPEN" -> if (previous != null) items += TimelineItem.StateChanged(StateChange.REOPENED, actor, null, at)
                    }
                }
                before = update
            }
        }
        return items.sortedBy { it.createdAt ?: Instant.EPOCH }
    }

    private fun PullrequestComment.toItem(): TimelineItem.Comment = TimelineItem.Comment(
        id = id ?: 0,
        author = people.user(user),
        body = content?.raw.orEmpty(),
        createdAt = bitbucketInstant(createdOn) ?: Instant.EPOCH,
        reactions = emptyMap(),
    )

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = forgeCall {
        http.bitbucket(token, *ref.pull("comments"), method = HttpMethod.Post, body = content(body))
            .toResult { body<PullrequestComment>().toItem() }
    }

    override suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *ref.pull("comments", "$commentId"), method = HttpMethod.Put, body = content(body)).toResult { }
    }

    override suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *ref.pull("comments", "$commentId"), method = HttpMethod.Delete).toResult { }
    }

    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> =
        change(token, ref, buildJsonObject { put("title", title); put("description", body) })

    /** Makes [fields] what the pull request [ref] says: Bitbucket keeps what a change leaves out. */
    private suspend fun change(token: String, ref: IssueRef, fields: JsonObject): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *ref.pull(), method = HttpMethod.Put, body = fields).toResult { }
    }

    /** Bitbucket has no issues to open since 2026. */
    override suspend fun create(token: String, repo: RepoId, title: String, body: String, labels: List<String>): ForgeResult<IssueDetails> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** Declines the pull request [ref]; a declined one can't be reopened through Bitbucket's API. */
    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> {
        if (open) return ForgeResult.Failure(ForgeError.Unsupported)
        return forgeCall { http.bitbucket(token, *ref.pull("decline"), method = HttpMethod.Post).toResult { } }
    }

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = forgeCall {
        ForgeResult.Success(repos.rights(token, repo) ?: RepoRights(RepoAccess.NONE))
    }

    /** Who can be asked to review: the members of the repository's workspace. */
    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = forgeCall {
        http.bitbucket(token, "workspaces", repo.owner, "members", query = listOf("pagelen" to "100"))
            .toResult { body<PaginatedWorkspaceMemberships>().propertyValues.orEmpty().mapNotNull { people.user(it.user) } }
    }

    /** Makes [logins] the reviewers, each by the account behind their name (as [assignable] or the conversation told it). */
    override suspend fun setReviewers(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> {
        val uuids = logins.map { people.uuidOf(it) ?: return ForgeResult.Failure(ForgeError.Http(404, "No Bitbucket account named $it")) }
        // Bitbucket rewrites a pull request whole: the title goes along, which it asks for on every change.
        return when (val current = issue(token, ref)) {
            is ForgeResult.Failure -> current
            is ForgeResult.Success -> change(
                token, ref,
                buildJsonObject {
                    put("title", current.value.title)
                    put("reviewers", buildJsonArray { uuids.forEach { add(buildJsonObject { put("uuid", it) }) } })
                },
            )
        }
    }

    override suspend fun setDraft(token: String, ref: IssueRef, draft: Boolean): ForgeResult<Unit> = withTitle(token, ref) { put("draft", draft) }

    override suspend fun setBase(token: String, ref: IssueRef, branch: String): ForgeResult<Unit> = withTitle(token, ref) {
        putJsonObject("destination") { putJsonObject("branch") { put("name", branch) } }
    }

    private suspend fun withTitle(token: String, ref: IssueRef, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ForgeResult<Unit> =
        when (val current = title(token, ref)) {
            is ForgeResult.Failure -> current
            is ForgeResult.Success -> change(token, ref, buildJsonObject { put("title", current.value); fields() })
        }

    private companion object {
        const val MAX_ACTIVITY_PAGES = 20
    }
}

/** The segments of the pull request [this] names, then [more]. */
internal fun IssueRef.pull(vararg more: String): Array<String> = repo.api("pullrequests", "$number", *more)

/** A comment's or a description's words, as Bitbucket takes them: Markdown, under `content.raw`. */
internal fun content(body: String): JsonObject = buildJsonObject { putJsonObject("content") { put("raw", body) } }
