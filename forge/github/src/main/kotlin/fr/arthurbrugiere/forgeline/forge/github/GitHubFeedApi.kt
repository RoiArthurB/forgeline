package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant

class GitHubFeedApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : FeedApi {

    override suspend fun receivedEvents(token: String?, login: String, page: Int, ifModifiedSince: String?): ForgeResult<FeedPage> = gitHubCall {
        val response = httpClient.request {
            method = HttpMethod.Get
            url {
                takeFrom(apiBaseUrl)
                appendPathSegments("users", login, "received_events")
                parameters.append("per_page", "100")
                parameters.append("page", page.toString())
            }
            gitHubHeaders(token)
            if (ifModifiedSince != null) header(HttpHeaders.IfModifiedSince, ifModifiedSince)
        }
        val lastModified = response.headers[HttpHeaders.LastModified] ?: ifModifiedSince
        val pollInterval = response.headers["X-Poll-Interval"]?.toIntOrNull()
        when (response.status) {
            HttpStatusCode.NotModified -> ForgeResult.Success(FeedPage(null, null, lastModified, pollInterval))
            HttpStatusCode.OK -> ForgeResult.Success(
                FeedPage(response.body<List<FeedEventJson>>().mapNotNull { it.toModel() }, response.nextPage(), lastModified, pollInterval),
            )
            else -> response.failure()
        }
    }
}

@Serializable
private data class FeedActorJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class FeedEventRepoJson(val name: String)

@Serializable
private data class FeedEventJson(
    val id: String,
    val type: String,
    val actor: FeedActorJson,
    val repo: FeedEventRepoJson,
    val payload: JsonObject,
    @SerialName("created_at") val createdAt: String,
) {
    fun toModel(): FeedEvent? {
        val (owner, name) = repo.name.split('/').takeIf { it.size == 2 } ?: return null
        val action = runCatching { action() }.getOrNull() ?: return null
        return FeedEvent(id, ForgeUser(actor.login, null, actor.avatarUrl), RepoId(owner, name), action, Instant.parse(createdAt))
    }

    private inline fun <reified T> payload(): T = GitHubJson.decodeFromJsonElement(payload)

    /** Null for activity the Feed doesn't show: label changes, wiki edits, unknown future events. */
    private fun action(): FeedAction? = when (type) {
        "WatchEvent" -> FeedAction.Starred
        "ForkEvent" -> payload<FeedForkPayload>().forkee.fullName.toRepoId()?.let { FeedAction.Forked(it) }
        "PublicEvent" -> FeedAction.MadePublic
        "ReleaseEvent" -> payload<FeedReleasePayload>().takeIf { it.action == "published" }?.release
            ?.let { FeedAction.Released(it.tagName, it.name?.takeIf(String::isNotBlank), it.prerelease) }
        "IssuesEvent" -> payload<FeedIssuePayload>().let { p ->
            val action = when (p.action) {
                "opened" -> IssueAction.OPENED
                "closed" -> IssueAction.CLOSED
                "reopened" -> IssueAction.REOPENED
                else -> return null
            }
            FeedAction.Issue(action, p.issue.number, p.issue.title)
        }
        "PullRequestEvent" -> payload<FeedPullRequestPayload>().let { p ->
            val action = when (p.action) {
                "opened" -> PullRequestAction.OPENED
                "closed" -> PullRequestAction.CLOSED
                "merged" -> PullRequestAction.MERGED
                "reopened" -> PullRequestAction.REOPENED
                else -> return null
            }
            FeedAction.PullRequest(action, p.number ?: p.pullRequest.number)
        }
        "IssueCommentEvent" -> payload<FeedIssuePayload>().takeIf { it.action == "created" }?.issue
            ?.let { FeedAction.Commented(it.number, it.title, isPullRequest = it.pullRequest != null) }
        "PullRequestReviewCommentEvent" -> payload<FeedPullRequestPayload>().takeIf { it.action == "created" }
            ?.let { FeedAction.Commented(it.pullRequest.number, null, isPullRequest = true) }
        "PullRequestReviewEvent" -> payload<FeedReviewPayload>().let { p ->
            val state = when (p.review.state.lowercase()) {
                "approved" -> ReviewState.APPROVED
                "changes_requested" -> ReviewState.CHANGES_REQUESTED
                "dismissed" -> ReviewState.DISMISSED
                else -> ReviewState.COMMENTED
            }
            FeedAction.Reviewed(p.pullRequest.number, state)
        }
        "PushEvent" -> payload<FeedPushPayload>().ref.removePrefix("refs/heads/").let { FeedAction.Pushed(it) }
        "CreateEvent" -> payload<FeedRefPayload>().let { p ->
            when (p.refType) {
                "repository" -> FeedAction.CreatedRepo(p.description?.takeIf(String::isNotBlank))
                "branch", "tag" -> p.ref?.let { FeedAction.Branch(it, isTag = p.refType == "tag", deleted = false) }
                else -> null
            }
        }
        "DeleteEvent" -> payload<FeedRefPayload>().let { p ->
            p.ref?.takeIf { p.refType == "branch" || p.refType == "tag" }?.let { FeedAction.Branch(it, isTag = p.refType == "tag", deleted = true) }
        }
        "MemberEvent" -> payload<FeedMemberPayload>().takeIf { it.action == "added" }?.let { FeedAction.AddedMember(it.member.login) }
        else -> null
    }
}

private fun String.toRepoId(): RepoId? = split('/').takeIf { it.size == 2 }?.let { RepoId(it[0], it[1]) }

@Serializable
private data class FeedForkeeJson(@SerialName("full_name") val fullName: String)

@Serializable
private data class FeedForkPayload(val forkee: FeedForkeeJson)

@Serializable
private data class FeedReleaseJson(@SerialName("tag_name") val tagName: String, val name: String? = null, val prerelease: Boolean = false)

@Serializable
private data class FeedReleasePayload(val action: String, val release: FeedReleaseJson)

@Serializable
private data class FeedEventIssueJson(val number: Int, val title: String, @SerialName("pull_request") val pullRequest: JsonObject? = null)

@Serializable
private data class FeedIssuePayload(val action: String, val issue: FeedEventIssueJson)

@Serializable
private data class FeedEventPullRequestJson(val number: Int)

@Serializable
private data class FeedPullRequestPayload(
    val action: String,
    val number: Int? = null,
    @SerialName("pull_request") val pullRequest: FeedEventPullRequestJson,
)

@Serializable
private data class FeedEventReviewJson(val state: String)

@Serializable
private data class FeedReviewPayload(val review: FeedEventReviewJson, @SerialName("pull_request") val pullRequest: FeedEventPullRequestJson)

@Serializable
private data class FeedPushPayload(val ref: String)

@Serializable
private data class FeedRefPayload(val ref: String? = null, @SerialName("ref_type") val refType: String, val description: String? = null)

@Serializable
private data class FeedMemberPayload(val action: String, val member: FeedActorJson)
