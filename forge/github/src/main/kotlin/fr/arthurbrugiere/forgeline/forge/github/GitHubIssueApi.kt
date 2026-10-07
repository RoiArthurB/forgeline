package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonObject
import io.ktor.http.HttpMethod
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

class GitHubIssueApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : IssueApi {

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = gitHubCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString())
        if (response.status.value != 200) return@gitHubCall response.failure()
        val issue = response.body<IssueJson>()
        // The issue endpoint lacks merge and branch data; pull requests need their own call.
        val pull = if (issue.pullRequest != null) {
            val pullResponse = httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString())
            if (pullResponse.status.value != 200) return@gitHubCall pullResponse.failure()
            pullResponse.body<PullJson>()
        } else {
            null
        }
        ForgeResult.Success(issue.toModel(ref, pull))
    }

    /** The issue endpoint names pull requests too: no second call for the title. */
    override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString())
            .toResult { body<IssueJson>().title }
    }

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = gitHubCall {
        val response = httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "timeline",
            query = mapOf("per_page" to "100", "page" to page.toString()),
        )
        response.toResult { TimelinePage(body<List<EventJson>>().mapNotNull { it.toModel() }, nextPage(), lastPage()) }
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "comments",
            method = HttpMethod.Post, body = buildJsonObject { put("body", body) },
        ).toResult { body<CommentJson>().toModel() }
    }

    override suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", "comments", commentId.toString(),
            method = HttpMethod.Patch, body = buildJsonObject { put("body", body) },
        ).toResult { }
    }

    override suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", "comments", commentId.toString(),
            method = HttpMethod.Delete,
        ).toResult { }
    }

    /** A pull request is an issue to this endpoint: one call renames either. */
    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = edit(token, ref) {
        put("title", title)
        put("body", body)
    }

    override suspend fun create(token: String, repo: RepoId, title: String, body: String): ForgeResult<IssueDetails> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", repo.owner, repo.name, "issues",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("title", title)
                put("body", body)
            },
        ).toResult { body<IssueJson>().let { it.toModel(IssueRef(repo, it.number), pull = null) } }
    }

    /** A pull request is an issue to this endpoint: one call closes either. */
    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> = edit(token, ref) {
        put("state", if (open) "open" else "closed")
        if (!open && reason != null) put("state_reason", reason.name.lowercase())
    }

    private suspend fun edit(token: String, ref: IssueRef, fields: JsonObjectBuilder.() -> Unit): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(),
            method = HttpMethod.Patch, body = buildJsonObject(fields),
        ).toResult { }
    }

    /** GitHub keeps no due date, time spent or dependencies on an issue. */
    override val actions: Set<ConversationAction> = setOf(
        ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.CLOSE_REASON,
        ConversationAction.LOCK, ConversationAction.PIN, ConversationAction.TRANSFER, ConversationAction.DELETE,
    )

    // The lists below stop at their first hundred: a repository with more is rare, and none needs them all to triage.

    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "labels", query = FIRST_HUNDRED)
            .toResult { body<List<LabelJson>>().map { it.toModel() } }
    }

    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "labels",
            method = HttpMethod.Put, body = buildJsonObject { putJsonArray("labels") { names.forEach { add(it) } } },
        ).toResult { }
    }

    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "assignees", query = FIRST_HUNDRED)
            .toResult { body<List<UserJson>>().map { it.toModel() } }
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> =
        edit(token, ref) { putJsonArray("assignees") { logins.forEach { add(it) } } }

    override suspend fun milestones(token: String, repo: RepoId): ForgeResult<List<Milestone>> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "milestones", query = FIRST_HUNDRED + ("state" to "open"))
            .toResult { body<List<MilestoneJson>>().map { it.toModel() } }
    }

    override suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?): ForgeResult<Unit> =
        edit(token, ref) { put("milestone", milestone?.id) }

    override suspend fun setLocked(token: String, ref: IssueRef, locked: Boolean): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "lock",
            method = if (locked) HttpMethod.Put else HttpMethod.Delete,
        ).toResult { }
    }

    // Pinning, transferring and deleting only exist in GitHub's GraphQL API, which names an issue by its node id.

    override suspend fun isPinned(token: String, ref: IssueRef): ForgeResult<Boolean> =
        node(token, ref).map { it.getValue("isPinned").jsonPrimitive.booleanOrNull == true }

    override suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean): ForgeResult<Unit> = onNode(token, ref) { id ->
        val mutation = if (pinned) "pinIssue" else "unpinIssue"
        graphQl(token, "mutation(\$id: ID!) { $mutation(input: {issueId: \$id}) { clientMutationId } }", buildJsonObject { put("id", id) }).map { }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = onNode(token, ref) { id ->
        graphQl(token, "mutation(\$id: ID!) { deleteIssue(input: {issueId: \$id}) { clientMutationId } }", buildJsonObject { put("id", id) }).map { }
    }

    override suspend fun transfer(token: String, ref: IssueRef, to: RepoId): ForgeResult<IssueRef> {
        val found = graphQl(
            token,
            "query(\$owner: String!, \$name: String!, \$number: Int!, \$toOwner: String!, \$toName: String!) { " +
                "from: repository(owner: \$owner, name: \$name) { issue(number: \$number) { id } } " +
                "to: repository(owner: \$toOwner, name: \$toName) { id } }",
            buildJsonObject {
                put("owner", ref.repo.owner)
                put("name", ref.repo.name)
                put("number", ref.number)
                put("toOwner", to.owner)
                put("toName", to.name)
            },
        )
        val ids = when (found) {
            is ForgeResult.Failure -> return found
            is ForgeResult.Success -> found.value
        }
        val issueId = ids.objectAt("from", "issue")?.get("id")?.jsonPrimitive?.contentOrNull
        val repositoryId = ids.objectAt("to")?.get("id")?.jsonPrimitive?.contentOrNull
        if (issueId == null || repositoryId == null) return ForgeResult.Failure(ForgeError.Http(404, null))
        return graphQl(
            token,
            "mutation(\$issue: ID!, \$repository: ID!) { transferIssue(input: {issueId: \$issue, repositoryId: \$repository}) { " +
                "issue { number repository { name owner { login } } } } }",
            buildJsonObject {
                put("issue", issueId)
                put("repository", repositoryId)
            },
        ).map { data ->
            val moved = data.objectAt("transferIssue", "issue") ?: throw NoSuchElementException("transferIssue.issue")
            val repository = moved.getValue("repository").jsonObject
            IssueRef(
                RepoId(repository.getValue("owner").jsonObject.getValue("login").jsonPrimitive.content, repository.getValue("name").jsonPrimitive.content, ref.repo.forge),
                moved.getValue("number").jsonPrimitive.int,
            )
        }
    }

    /** The issue's node: its id and whether it is pinned. 404 when GitHub doesn't know it, or it is a pull request. */
    private suspend fun node(token: String, ref: IssueRef): ForgeResult<JsonObject> {
        val found = graphQl(
            token,
            "query(\$owner: String!, \$name: String!, \$number: Int!) { repository(owner: \$owner, name: \$name) { issue(number: \$number) { id isPinned } } }",
            buildJsonObject {
                put("owner", ref.repo.owner)
                put("name", ref.repo.name)
                put("number", ref.number)
            },
        )
        return when (found) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success -> found.value.objectAt("repository", "issue")?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }
    }

    private suspend fun <T> onNode(token: String, ref: IssueRef, act: suspend (id: String) -> ForgeResult<T>): ForgeResult<T> =
        when (val node = node(token, ref)) {
            is ForgeResult.Failure -> node
            is ForgeResult.Success -> act(node.value.getValue("id").jsonPrimitive.content)
        }

    /**
     * One GraphQL request. GitHub answers 200 even when it refuses: a refusal is told apart by its errors, FORBIDDEN
     * for a missing permission and NOT_FOUND for what the token can't see.
     */
    private suspend fun graphQl(token: String, query: String, variables: JsonObject): ForgeResult<JsonObject> = gitHubCall {
        val response = httpClient.post("$apiBaseUrl/graphql") {
            bearerAuth(token)
            header("X-GitHub-Api-Version", GitHubAuthApi.API_VERSION)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("query", query)
                    put("variables", variables)
                },
            )
        }
        if (!response.status.isSuccess()) return@gitHubCall response.failure()
        val answer = response.body<GraphQlAnswer>()
        val error = answer.errors.firstOrNull()
        when {
            error != null -> ForgeResult.Failure(
                ForgeError.Http(
                    when (error.type) {
                        "FORBIDDEN" -> 403
                        "NOT_FOUND" -> 404
                        else -> 422
                    },
                    error.message,
                ),
            )
            else -> ForgeResult.Success(answer.data ?: JsonObject(emptyMap()))
        }
    }

    private fun JsonObject.objectAt(vararg path: String): JsonObject? =
        path.fold<String, JsonObject?>(this) { at, key -> at?.get(key)?.takeUnless { it is JsonNull }?.jsonObject }

    private inline fun <T, R> ForgeResult<T>.map(transform: (T) -> R): ForgeResult<R> = when (this) {
        is ForgeResult.Failure -> this
        is ForgeResult.Success -> ForgeResult.Success(transform(value))
    }

    private companion object {
        val FIRST_HUNDRED = mapOf("per_page" to "100")
    }

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name).toResult {
            val permissions = body<PermittedRepoJson>().permissions
            RepoRights(
                when {
                    permissions.admin -> RepoAccess.ADMIN
                    permissions.push || permissions.maintain -> RepoAccess.WRITE
                    permissions.triage -> RepoAccess.TRIAGE
                    else -> RepoAccess.NONE
                },
            )
        }
    }
}

@Serializable
private data class GraphQlErrorJson(val type: String? = null, val message: String? = null)

@Serializable
private data class GraphQlAnswer(val data: JsonObject? = null, val errors: List<GraphQlErrorJson> = emptyList())

@Serializable
private data class PermissionsJson(val admin: Boolean = false, val maintain: Boolean = false, val push: Boolean = false, val triage: Boolean = false)

@Serializable
private data class PermittedRepoJson(val permissions: PermissionsJson = PermissionsJson())

@Serializable
private data class CommentJson(
    val id: Long,
    val user: UserJson? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String,
    val reactions: ReactionsJson? = null,
) {
    fun toModel() = TimelineItem.Comment(id, user?.toModel(), body.orEmpty(), Instant.parse(createdAt), reactions?.toModel().orEmpty())
}

@Serializable
internal data class UserJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null) {
    fun toModel() = ForgeUser(login = login, name = null, avatarUrl = avatarUrl)
}

@Serializable
private data class MilestoneJson(val number: Long = 0, val title: String) {
    fun toModel() = Milestone(number, title)
}

@Serializable
private data class LabelJson(val name: String, val color: String? = null) {
    fun toModel() = Label(name, color)
}

@Serializable
private data class ReactionsJson(
    @SerialName("+1") val thumbsUp: Int = 0,
    @SerialName("-1") val thumbsDown: Int = 0,
    val laugh: Int = 0,
    val hooray: Int = 0,
    val confused: Int = 0,
    val heart: Int = 0,
    val rocket: Int = 0,
    val eyes: Int = 0,
) {
    fun toModel(): Map<Reaction, Int> = mapOf(
        Reaction.THUMBS_UP to thumbsUp, Reaction.THUMBS_DOWN to thumbsDown, Reaction.LAUGH to laugh, Reaction.HOORAY to hooray,
        Reaction.CONFUSED to confused, Reaction.HEART to heart, Reaction.ROCKET to rocket, Reaction.EYES to eyes,
    ).filterValues { it > 0 }
}

@Serializable
private data class PullRefJson(val ref: String)

@Serializable
private data class PullJson(
    val draft: Boolean = false,
    val merged: Boolean = false,
    val base: PullRefJson,
    val head: PullRefJson,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
    val commits: Int = 0,
)

@Serializable
private data class IssueJson(
    // Only read from a created issue: one that was asked for is already numbered.
    val number: Int = 0,
    val title: String,
    val body: String? = null,
    val state: String,
    @SerialName("state_reason") val stateReason: String? = null,
    val user: UserJson? = null,
    val labels: List<LabelJson> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    val comments: Int = 0,
    val reactions: ReactionsJson? = null,
    @SerialName("pull_request") val pullRequest: kotlinx.serialization.json.JsonElement? = null,
    val locked: Boolean = false,
    val assignees: List<UserJson> = emptyList(),
    val milestone: MilestoneJson? = null,
) {
    fun toModel(ref: IssueRef, pull: PullJson?) = IssueDetails(
        ref = ref,
        title = title,
        body = body?.ifBlank { null },
        state = when {
            pull?.merged == true -> IssueState.MERGED
            state == "closed" -> IssueState.CLOSED
            else -> IssueState.OPEN
        },
        stateReason = stateReason,
        author = user?.toModel(),
        labels = labels.map { it.toModel() },
        createdAt = Instant.parse(createdAt),
        closedAt = closedAt?.let(Instant::parse),
        comments = comments,
        reactions = reactions?.toModel().orEmpty(),
        pullRequest = pull?.let {
            PullRequestInfo(it.draft, it.merged, it.base.ref, it.head.ref, it.additions, it.deletions, it.changedFiles, it.commits)
        },
        isLocked = locked,
        assignees = assignees.map { it.toModel() },
        milestone = milestone?.toModel(),
    )
}

@Serializable
private data class RepositoryJson(@SerialName("full_name") val fullName: String)

@Serializable
private data class SourceIssueJson(
    val number: Int,
    val title: String,
    val repository: RepositoryJson? = null,
    @SerialName("pull_request") val pullRequest: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
private data class SourceJson(val issue: SourceIssueJson? = null)

@Serializable
private data class RenameJson(val from: String, val to: String)

@Serializable
private data class CommitAuthorJson(val name: String? = null, val date: String? = null)

@Serializable
private data class EventJson(
    val event: String,
    val id: Long? = null,
    val actor: UserJson? = null,
    val user: UserJson? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("state_reason") val stateReason: String? = null,
    val state: String? = null,
    val reactions: ReactionsJson? = null,
    val label: LabelJson? = null,
    val rename: RenameJson? = null,
    val source: SourceJson? = null,
    val sha: String? = null,
    val message: String? = null,
    val author: CommitAuthorJson? = null,
    val assignee: UserJson? = null,
    val milestone: MilestoneJson? = null,
) {
    private fun event(event: ConversationEvent, at: Instant?, subject: String? = null): TimelineItem? =
        at?.let { TimelineItem.Event(event, actor?.toModel(), subject, it) }

    /** Null for events that are noise in a conversation (subscribed, mentioned, head_ref_deleted...). */
    fun toModel(): TimelineItem? {
        val at = createdAt?.let(Instant::parse)
        return when (event) {
            "commented" -> TimelineItem.Comment(
                id = id ?: 0,
                author = (user ?: actor)?.toModel(),
                body = body.orEmpty(),
                createdAt = at ?: return null,
                reactions = reactions?.toModel().orEmpty(),
            )
            "reviewed" -> TimelineItem.Review(
                id = id ?: 0,
                author = user?.toModel(),
                state = when (state?.lowercase()) {
                    "approved" -> ReviewState.APPROVED
                    "changes_requested" -> ReviewState.CHANGES_REQUESTED
                    "dismissed" -> ReviewState.DISMISSED
                    else -> ReviewState.COMMENTED
                },
                body = body?.ifBlank { null },
                createdAt = submittedAt?.let(Instant::parse),
            )
            "closed" -> TimelineItem.StateChanged(StateChange.CLOSED, actor?.toModel(), stateReason, at ?: return null)
            "reopened" -> TimelineItem.StateChanged(StateChange.REOPENED, actor?.toModel(), stateReason, at ?: return null)
            "merged" -> TimelineItem.StateChanged(StateChange.MERGED, actor?.toModel(), null, at ?: return null)
            "labeled", "unlabeled" -> TimelineItem.Labeled(event == "labeled", label?.toModel() ?: return null, actor?.toModel(), at ?: return null)
            "renamed" -> TimelineItem.Renamed(rename?.from ?: return null, rename.to, actor?.toModel(), at ?: return null)
            "cross-referenced" -> {
                val issue = source?.issue ?: return null
                val repo = issue.repository?.fullName?.split('/')?.takeIf { it.size == 2 } ?: return null
                TimelineItem.CrossReferenced(
                    IssueRef(RepoId(repo[0], repo[1], ForgeInstance.GitHub), issue.number), issue.title, issue.pullRequest != null, actor?.toModel(), at ?: return null,
                )
            }
            "locked" -> event(ConversationEvent.LOCKED, at)
            "unlocked" -> event(ConversationEvent.UNLOCKED, at)
            "pinned" -> event(ConversationEvent.PINNED, at)
            "unpinned" -> event(ConversationEvent.UNPINNED, at)
            "assigned" -> event(ConversationEvent.ASSIGNED, at, assignee?.login ?: return null)
            "unassigned" -> event(ConversationEvent.UNASSIGNED, at, assignee?.login ?: return null)
            "milestoned" -> event(ConversationEvent.MILESTONED, at, milestone?.title ?: return null)
            "demilestoned" -> event(ConversationEvent.DEMILESTONED, at, milestone?.title ?: return null)
            "transferred" -> event(ConversationEvent.TRANSFERRED, at)
            "converted_to_discussion" -> event(ConversationEvent.CONVERTED_TO_DISCUSSION, at)
            "committed" -> TimelineItem.Committed(sha ?: return null, message.orEmpty(), author?.name, author?.date?.let(Instant::parse))
            else -> null
        }
    }
}
