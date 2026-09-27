package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
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

class GitHubNotificationsApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : NotificationsApi {

    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> = gitHubCall {
        val threads = mutableListOf<NotificationThread>()
        var lastModified: String? = null
        var pollInterval: Int? = null
        var page: Int? = 1
        while (page != null && page <= maxPages) {
            val currentPage: Int = page
            val response = httpClient.request {
                method = HttpMethod.Get
                url {
                    takeFrom(apiBaseUrl)
                    appendPathSegments("notifications")
                    parameters.append("all", "true")
                    parameters.append("per_page", "50")
                    parameters.append("page", currentPage.toString())
                }
                gitHubHeaders(token)
                // Only the first page is conditional: a 304 there means nothing changed at all.
                if (currentPage == 1 && ifModifiedSince != null) header(HttpHeaders.IfModifiedSince, ifModifiedSince)
            }
            if (currentPage == 1) {
                lastModified = response.headers[HttpHeaders.LastModified] ?: ifModifiedSince
                pollInterval = response.headers["X-Poll-Interval"]?.toIntOrNull()
                if (response.status == HttpStatusCode.NotModified) return@gitHubCall ForgeResult.Success(NotificationsSync(null, lastModified, pollInterval))
            }
            if (response.status != HttpStatusCode.OK) return@gitHubCall response.failure()
            threads += response.body<List<ThreadJson>>().mapNotNull { it.toModel() }
            page = response.nextPage()
        }
        ForgeResult.Success(NotificationsSync(threads, lastModified, pollInterval))
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
}

@Serializable
private data class SubjectJson(val title: String, val url: String? = null, val type: String)

@Serializable
private data class ThreadRepoJson(@SerialName("full_name") val fullName: String)

@Serializable
private data class ThreadJson(
    val id: String,
    val unread: Boolean,
    val reason: String,
    @SerialName("updated_at") val updatedAt: String,
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
            repo = RepoId(owner, name),
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
        )
    }
}
