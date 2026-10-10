package fr.arthurbrugiere.forgeline.forge.github

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectType
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
import java.time.Instant

@Serializable
private data class StatesResponse(val data: JsonObject? = null)

class GitHubNotificationsApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : NotificationsApi {

    /**
     * The first page (conditional: a 304 there means no thread has new activity), then every other page at once: the
     * first announces the last, and waiting for each page's "next" would cost a round trip per page.
     *
     * GitHub's 304 only speaks of new activity (checked 2026-10-10: Last-Modified is the newest thread's date): a
     * thread read or marked done on github.com changes nothing to it. So [ifModifiedSince] also counts the checks
     * answered 304 in a row, and every [FULL_EVERY]th asks for the list whatever happened.
     */
    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> = gitHubCall {
        val since = ifModifiedSince?.substringBefore(CHECKS)
        val checks = ifModifiedSince?.substringAfter(CHECKS, "0")?.toIntOrNull() ?: 0
        val first = page(token, 1, since.takeIf { checks + 1 < FULL_EVERY })
        val pollInterval = first.headers["X-Poll-Interval"]?.toIntOrNull()
        if (first.status == HttpStatusCode.NotModified) {
            return@gitHubCall ForgeResult.Success(NotificationsSync(null, "$since$CHECKS${checks + 1}", pollInterval))
        }
        val lastModified = first.headers[HttpHeaders.LastModified] ?: since
        if (first.status != HttpStatusCode.OK) return@gitHubCall first.failure()
        val last = minOf(first.lastPage() ?: first.nextPage() ?: 1, maxPages)
        val rest = coroutineScope { (2..last).map { number -> async { page(token, number, null) } }.awaitAll() }
        rest.firstOrNull { it.status != HttpStatusCode.OK }?.let { return@gitHubCall it.failure() }
        val threads = (listOf(first) + rest).flatMap { response -> response.body<List<ThreadJson>>().mapNotNull { it.toModel() } }
        ForgeResult.Success(NotificationsSync(threads, lastModified, pollInterval))
    }

    private suspend fun page(token: String, number: Int, ifModifiedSince: String?) = httpClient.request {
        method = HttpMethod.Get
        url {
            takeFrom(apiBaseUrl)
            appendPathSegments("notifications")
            parameters.append("all", "true")
            parameters.append("per_page", "50")
            parameters.append("page", number.toString())
        }
        gitHubHeaders(token)
        if (ifModifiedSince != null) header(HttpHeaders.IfModifiedSince, ifModifiedSince)
    }

    override suspend fun subjectStates(token: String, subjects: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>> {
        if (subjects.isEmpty()) return ForgeResult.Success(emptyMap())
        // One GraphQL request per 100 subjects, where REST would need one per subject; the requests go out together.
        val answers = coroutineScope {
            subjects.distinct().chunked(STATES_PER_REQUEST).map { chunk -> async { states(token, chunk) } }.awaitAll()
        }
        answers.firstOrNull { it is ForgeResult.Failure }?.let { return it as ForgeResult.Failure }
        return ForgeResult.Success(answers.flatMap { (it as ForgeResult.Success).value.entries }.associate { it.key to it.value })
    }

    private suspend fun states(token: String, chunk: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>> {
        val repos = chunk.groupBy { it.repo }.entries.toList()
        val params = repos.indices.joinToString(", ") { "\$o$it: String!, \$n$it: String!" }
        val fields = repos.withIndex().joinToString(" ") { (i, entry) ->
            val items = entry.value.joinToString(" ") { "i${it.number}: issueOrPullRequest(number: ${it.number}) { ...state }" }
            "r$i: repository(owner: \$o$i, name: \$n$i) { $items }"
        }
        val query = "query SubjectStates($params) { $fields } fragment state on IssueOrPullRequest { " +
            "__typename ... on Issue { state stateReason } ... on PullRequest { state isDraft } }"
        val variables = repos.withIndex().flatMap { (i, entry) -> listOf("o$i" to entry.key.owner, "n$i" to entry.key.name) }.toMap()
        val result = gitHubCall {
            httpClient.gitHubApi(
                apiBaseUrl, token, "graphql", method = HttpMethod.Post,
                body = buildJsonObject {
                    put("query", query)
                    put("variables", buildJsonObject { variables.forEach { (key, value) -> put(key, value) } })
                },
            ).toResult { body<StatesResponse>() }
        }
        val data = when (result) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value.data ?: return ForgeResult.Success(emptyMap())
        }
        val states = mutableMapOf<IssueRef, SubjectState>()
        repos.forEachIndexed { i, (_, refs) ->
            val repo = data["r$i"] as? JsonObject ?: return@forEachIndexed
            refs.forEach { ref -> (repo["i${ref.number}"] as? JsonObject)?.let(::subjectState)?.let { states[ref] = it } }
        }
        return ForgeResult.Success(states)
    }

    private fun subjectState(node: JsonObject): SubjectState? {
        val state = node["state"]?.jsonPrimitive?.contentOrNull
        return when (node["__typename"]?.jsonPrimitive?.contentOrNull) {
            "PullRequest" -> when {
                state == "MERGED" -> SubjectState.MERGED
                state == "CLOSED" -> SubjectState.CLOSED
                node["isDraft"]?.jsonPrimitive?.booleanOrNull == true -> SubjectState.DRAFT
                state == "OPEN" -> SubjectState.OPEN
                else -> null
            }
            "Issue" -> when {
                state == "OPEN" -> SubjectState.OPEN
                node["stateReason"]?.jsonPrimitive?.contentOrNull == "NOT_PLANNED" -> SubjectState.NOT_PLANNED
                state == "CLOSED" -> SubjectState.CLOSED
                else -> null
            }
            else -> null
        }
    }

    override suspend fun markRead(token: String, threadId: String): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "notifications", "threads", threadId, method = HttpMethod.Patch).toResult { }
    }

    override suspend fun markDone(token: String, threadId: String): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "notifications", "threads", threadId, method = HttpMethod.Delete).toResult { }
    }

    override suspend fun unsubscribe(token: String, threadId: String): ForgeResult<Unit> = gitHubCall {
        httpClient.gitHubApi(apiBaseUrl, token, "notifications", "threads", threadId, "subscription", method = HttpMethod.Delete)
            .toResult { }
    }

    private companion object {
        const val STATES_PER_REQUEST = 100

        /** Follows the date in the marker kept between checks: how many were answered "not modified" since the last list. */
        const val CHECKS = ";checks="

        /** One check in this many lists the threads even with no new activity, as Forgejo's does. */
        const val FULL_EVERY = 4
    }
}

@Serializable
private data class SubjectJson(val title: String, val url: String? = null, val type: String)

@Serializable
private data class ThreadRepoJson(@SerialName("full_name") val fullName: String, val owner: ThreadOwnerJson? = null)

@Serializable
private data class ThreadOwnerJson(@SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class ThreadJson(
    val id: String,
    val unread: Boolean,
    val reason: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("last_read_at") val lastReadAt: String? = null,
    val subject: SubjectJson,
    val repository: ThreadRepoJson,
) {
    fun toModel(): NotificationThread? {
        val (owner, name) = repository.fullName.split('/').takeIf { it.size == 2 } ?: return null
        val type = when (subject.type) {
            "Issue" -> SubjectType.ISSUE
            "PullRequest" -> SubjectType.PULL_REQUEST
            "Release" -> SubjectType.RELEASE
            "Discussion" -> SubjectType.DISCUSSION
            "CheckSuite" -> SubjectType.CHECK_SUITE
            "Commit" -> SubjectType.COMMIT
            else -> SubjectType.OTHER
        }
        // Subject URLs end with the number for issues (/issues/42) and pull requests (/pulls/43).
        val number = if (type == SubjectType.ISSUE || type == SubjectType.PULL_REQUEST) subject.url?.substringAfterLast('/')?.toIntOrNull() else null
        return NotificationThread(
            id = id,
            repo = RepoId(owner, name, ForgeInstance.GitHub),
            title = subject.title,
            type = type,
            number = number,
            reason = when (reason) {
                "mention" -> NotificationReason.MENTION
                "team_mention" -> NotificationReason.TEAM_MENTION
                "review_requested" -> NotificationReason.REVIEW_REQUESTED
                "assign" -> NotificationReason.ASSIGN
                "author" -> NotificationReason.AUTHOR
                "comment" -> NotificationReason.COMMENT
                "state_change" -> NotificationReason.STATE_CHANGE
                "subscribed" -> NotificationReason.SUBSCRIBED
                "manual" -> NotificationReason.MANUAL
                "ci_activity" -> NotificationReason.CI_ACTIVITY
                "security_alert" -> NotificationReason.SECURITY_ALERT
                else -> NotificationReason.OTHER
            },
            unread = unread,
            updatedAt = Instant.parse(updatedAt),
            lastReadAt = lastReadAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            ownerAvatarUrl = repository.owner?.avatarUrl,
        )
    }
}
