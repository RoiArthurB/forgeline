package fr.arthurbrugiere.forgeline.forge.github

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
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
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

    /**
     * GitHub's events never mention starred repositories. Asking for every star's releases and discussions in one
     * GraphQL request is too heavy (100 repositories: 7 s for releases alone, HTTP 504 with discussions, measured
     * 2026-10-01), so this goes in two steps: the stars are listed over REST, every page at once, then only those pushed
     * to since [since] are asked about, [BATCH] repositories per request, all requests together.
     */
    override suspend fun starredActivity(token: String, since: Instant): ForgeResult<List<FeedEvent>> {
        val first = when (val result = gitHubCall { starredPage(token, 1) }) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        val last = (first.second ?: 1).coerceAtMost(MAX_STARRED_PAGES)
        val stars = first.first + coroutineScope {
            // A later page that fails only loses its own stars.
            (2..last).map { page -> async { (gitHubCall { starredPage(token, page) } as? ForgeResult.Success)?.value?.first.orEmpty() } }.awaitAll().flatten()
        }
        val active = stars.filter { star -> !star.archived && (star.pushedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { it >= since } ?: true) }
        val events = coroutineScope {
            active.chunked(BATCH).map { batch -> async { published(token, batch, since) } }.awaitAll().flatten()
        }
        return ForgeResult.Success(events)
    }

    /** One page of stars, and the last page's number when GitHub announces it. */
    private suspend fun starredPage(token: String, page: Int): ForgeResult<Pair<List<StarJson>, Int?>> {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "user", "starred", query = mapOf("per_page" to "100", "page" to page.toString()))
        return if (response.status == HttpStatusCode.OK) ForgeResult.Success(response.body<List<StarJson>>() to response.lastPage()) else response.failure()
    }

    /** The latest release of each repository in [batch], and the newest discussions where there are any; empty when the request fails. */
    private suspend fun published(token: String, batch: List<StarJson>, since: Instant): List<FeedEvent> {
        val params = batch.indices.joinToString(", ") { "\$o$it: String!, \$n$it: String!" }
        val fields = batch.withIndex().joinToString(" ") { (i, star) ->
            "r$i: repository(owner: \$o$i, name: \$n$i) { $RELEASES${if (star.hasDiscussions) " $DISCUSSIONS" else ""} }"
        }
        val result = gitHubCall {
            httpClient.gitHubApi(
                apiBaseUrl, token, "graphql", method = HttpMethod.Post,
                body = buildJsonObject {
                    put("query", "query Published($params) { $fields }")
                    put(
                        "variables",
                        buildJsonObject {
                            batch.forEachIndexed { i, star ->
                                put("o$i", star.fullName.substringBefore('/'))
                                put("n$i", star.fullName.substringAfter('/'))
                            }
                        },
                    )
                },
            ).toResult { body<PublishedResponse>() }
        }
        val data = (result as? ForgeResult.Success)?.value?.data ?: return emptyList()
        return batch.withIndex().flatMap { (i, star) ->
            val node = data["r$i"] as? JsonObject ?: return@flatMap emptyList()
            runCatching { GitHubJson.decodeFromJsonElement<PublishedJson>(node) }.getOrNull()?.events(star, since).orEmpty()
        }
    }

    private companion object {
        /** 1,000 starred repositories at most: beyond that, the oldest stars are left out. */
        const val MAX_STARRED_PAGES = 10

        /** Repositories per GraphQL request: 20 answer in about 3 s, far from GitHub's 10 s limit. */
        const val BATCH = 20
        const val RELEASES = "releases(first: 1, orderBy: {field: CREATED_AT, direction: DESC}) { nodes { tagName name publishedAt isPrerelease isDraft author { login avatarUrl } } }"

        /** Enough discussions to find an announcement behind a few newer questions. */
        const val DISCUSSIONS = "discussions(first: 5, orderBy: {field: CREATED_AT, direction: DESC}) { nodes { number title createdAt category { slug } author { login avatarUrl } } }"
    }
}

@Serializable
private data class StarOwnerJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class StarJson(
    @SerialName("full_name") val fullName: String,
    val owner: StarOwnerJson,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("has_discussions") val hasDiscussions: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
private data class PublishedResponse(val data: JsonObject? = null)

@Serializable
private data class PublishedActorJson(val login: String, val avatarUrl: String? = null) {
    /** Null for automation ("github-actions[bot]"): a bot's name says nothing, so the repository's owner signs instead. */
    fun toModel(): ForgeUser? = if (login.endsWith("[bot]")) null else ForgeUser(login, null, avatarUrl)
}

@Serializable
private data class PublishedNodes<T>(val nodes: List<T> = emptyList())

@Serializable
private data class PublishedReleaseJson(
    val tagName: String,
    val name: String? = null,
    val publishedAt: String? = null,
    val isPrerelease: Boolean = false,
    val isDraft: Boolean = false,
    val author: PublishedActorJson? = null,
)

@Serializable
private data class PublishedCategoryJson(val slug: String? = null)

@Serializable
private data class PublishedDiscussionJson(
    val number: Int,
    val title: String,
    val createdAt: String,
    val category: PublishedCategoryJson? = null,
    val author: PublishedActorJson? = null,
)

@Serializable
private data class PublishedJson(
    val releases: PublishedNodes<PublishedReleaseJson> = PublishedNodes(),
    val discussions: PublishedNodes<PublishedDiscussionJson> = PublishedNodes(),
) {
    fun events(star: StarJson, since: Instant): List<FeedEvent> {
        val (ownerLogin, name) = star.fullName.split('/').takeIf { it.size == 2 } ?: return emptyList()
        val repo = RepoId(ownerLogin, name)
        // A release made by automation has no author, or a bot: the repository's owner signs it.
        val owner = ForgeUser(star.owner.login, null, star.owner.avatarUrl)
        val release = releases.nodes.firstOrNull()?.takeIf { !it.isDraft }?.let { json ->
            val at = json.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { it >= since } ?: return@let null
            FeedEvent(
                "starred-release:${star.fullName}:${json.tagName}",
                json.author?.toModel() ?: owner,
                repo,
                FeedAction.Released(json.tagName, json.name?.ifBlank { null }, json.isPrerelease),
                at,
            )
        }
        val announcements = discussions.nodes.filter { it.category?.slug == ANNOUNCEMENTS }.mapNotNull { json ->
            val at = runCatching { Instant.parse(json.createdAt) }.getOrNull()?.takeIf { it >= since } ?: return@mapNotNull null
            // Posted with the release (many projects automate it): the release row already says it.
            if (release != null && kotlin.math.abs(at.epochSecond - release.createdAt.epochSecond) <= WITH_RELEASE_SECONDS) return@mapNotNull null
            FeedEvent("announcement:${star.fullName}:${json.number}", json.author?.toModel() ?: owner, repo, FeedAction.Announced(json.number, json.title), at)
        }
        return listOfNotNull(release) + announcements
    }
}

// Not in a companion: a private companion on a @Serializable class hides the serializer generated beside it.
private const val ANNOUNCEMENTS = "announcements"
private const val WITH_RELEASE_SECONDS = 300L

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
        return FeedEvent(id, ForgeUser(actor.login, null, actor.avatarUrl), RepoId(owner, name, ForgeInstance.GitHub), action, Instant.parse(createdAt))
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

private fun String.toRepoId(): RepoId? = split('/').takeIf { it.size == 2 }?.let { RepoId(it[0], it[1], ForgeInstance.GitHub) }

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
