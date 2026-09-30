package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
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
        response.toResult { TimelinePage(body<List<EventJson>>().mapNotNull { it.toModel() }, nextPage()) }
    }
}

@Serializable
internal data class UserJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null) {
    fun toModel() = ForgeUser(login = login, name = null, avatarUrl = avatarUrl)
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
) {
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
            "committed" -> TimelineItem.Committed(sha ?: return null, message.orEmpty(), author?.name, author?.date?.let(Instant::parse))
            else -> null
        }
    }
}
