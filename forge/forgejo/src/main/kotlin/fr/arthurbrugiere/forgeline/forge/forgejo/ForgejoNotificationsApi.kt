package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Forgejo instance's notifications. Unlike GitHub, Forgejo (checked against Codeberg's API description, 2026-09-29):
 * - sends no reason, so "Needs you" is rebuilt from issue searches (review requested, mentioned, assigned, created);
 * - has no conditional GET, but `/notifications/new` counts what's new, cheaply;
 * - has no "done": done is "read" here, and the Inbox hides the thread until it has new activity ([supportsDone]);
 * - unsubscribes per issue, not per thread;
 * - tells each subject's state (open, closed, merged) with the thread.
 */
class ForgejoNotificationsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : NotificationsApi {

    override val supportsDone: Boolean = false

    /**
     * [ifModifiedSince] is this client's own marker ("checks:N"), not an HTTP date: with nothing new, the list is only
     * fetched again every [FULL_EVERY] checks, to catch threads read on the web.
     */
    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> = forgejoCall {
        val checks = ifModifiedSince?.removePrefix(MARKER)?.toIntOrNull()
        if (checks != null && checks + 1 < FULL_EVERY) {
            val count = httpClient.forgejoApi(forge, token, "notifications", "new")
            if (count.status != HttpStatusCode.OK && count.status != HttpStatusCode.NoContent) return@forgejoCall count.failure()
            val new = if (count.status == HttpStatusCode.NoContent) 0 else count.body<CountJson>().new
            if (new == 0) return@forgejoCall ForgeResult.Success(NotificationsSync(null, "$MARKER${checks + 1}", POLL_SECONDS))
        }
        val threads = mutableListOf<ThreadJson>()
        var page: Int? = 1
        while (page != null && page <= maxPages) {
            val response = httpClient.forgejoApi(
                forge, token, "notifications",
                query = mapOf("all" to "true", "limit" to "$PAGE_SIZE", "page" to page.toString()),
            )
            if (response.status != HttpStatusCode.OK) return@forgejoCall response.failure()
            val batch = response.body<List<ThreadJson>>()
            threads += batch
            page = response.nextPage() ?: (page + 1).takeIf { batch.size == PAGE_SIZE }
        }
        val reasons = reasons(token, threads)
        ForgeResult.Success(NotificationsSync(threads.mapNotNull { it.toModel(reasons) }, "${MARKER}0", POLL_SECONDS))
    }

    override suspend fun markRead(token: String, threadId: String): ForgeResult<Unit> = forgejoCall {
        httpClient.forgejoApi(forge, token, "notifications", "threads", threadId, method = HttpMethod.Patch, query = mapOf("to-status" to "read"))
            .toResult { }
    }

    /** Forgejo can't make a thread go away: it's marked read, and the Inbox remembers it was done. */
    override suspend fun markDone(token: String, threadId: String): ForgeResult<Unit> = markRead(token, threadId)

    override suspend fun unsubscribe(token: String, threadId: String): ForgeResult<Unit> = forgejoCall {
        // Subscriptions belong to issues here: find the thread's issue, and who "me" is.
        val thread = httpClient.forgejoApi(forge, token, "notifications", "threads", threadId)
        if (thread.status != HttpStatusCode.OK) return@forgejoCall thread.failure()
        val subject = thread.body<ThreadJson>()
        val repo = subject.repository ?: return@forgejoCall ForgeResult.Success(Unit)
        val number = subject.number() ?: return@forgejoCall ForgeResult.Success(Unit)
        val me = httpClient.forgejoApi(forge, token, "user")
        if (me.status != HttpStatusCode.OK) return@forgejoCall me.failure()
        httpClient.forgejoApi(
            forge, token, "repos", repo.owner.login, repo.name, "issues", number.toString(), "subscriptions", me.body<UserJson>().login,
            method = HttpMethod.Delete,
        ).toResult { }
    }

    /** States come with the threads themselves. */
    override suspend fun subjectStates(token: String, subjects: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>> =
        ForgeResult.Success(emptyMap())

    /** Each conversation's strongest reason, from searches that mirror GitHub's reasons. */
    private suspend fun reasons(token: String, threads: List<ThreadJson>): Map<String, NotificationReason> {
        if (threads.none { it.number() != null }) return emptyMap()
        val since = threads.mapNotNull { it.updatedAt }.minOrNull()
        val searches = listOf(
            NotificationReason.REVIEW_REQUESTED to mapOf("review_requested" to "true", "state" to "open"),
            NotificationReason.MENTION to mapOfNotNull("mentioned" to "true", "since" to since),
            NotificationReason.ASSIGN to mapOf("assigned" to "true", "state" to "open"),
            NotificationReason.AUTHOR to mapOfNotNull("created" to "true", "since" to since),
        )
        val found = coroutineScope {
            searches.map { (reason, filter) ->
                async {
                    val response = httpClient.forgejoApi(forge, token, "repos", "issues", "search", query = filter + ("limit" to "$PAGE_SIZE"))
                    if (response.status != HttpStatusCode.OK) return@async emptyList()
                    response.body<List<IssueJson>>().mapNotNull { issue -> issue.repository?.let { "${it.owner}/${it.name}#${issue.number}".lowercase() to reason } }
                }
            }.awaitAll().flatten()
        }
        // Searches run strongest first: the first reason found for a conversation wins.
        return found.groupBy({ it.first }, { it.second }).mapValues { (_, reasons) -> reasons.first() }
    }

    private fun ThreadJson.toModel(reasons: Map<String, NotificationReason>): NotificationThread? {
        val repo = repository ?: return null
        val subject = subject ?: return null
        val number = number()
        val type = when (subject.type) {
            "Issue" -> SubjectType.ISSUE
            "Pull" -> SubjectType.PULL_REQUEST
            "Commit" -> SubjectType.COMMIT
            else -> SubjectType.OTHER
        }
        return NotificationThread(
            id = id.toString(),
            repo = RepoId(repo.owner.login, repo.name, forge),
            title = subject.title,
            type = type,
            number = number,
            reason = number?.let { reasons["${repo.owner.login}/${repo.name}#$it".lowercase()] } ?: NotificationReason.SUBSCRIBED,
            unread = unread,
            updatedAt = instant(updatedAt) ?: java.time.Instant.EPOCH,
            ownerAvatarUrl = repo.owner.avatarUrl,
            state = when (subject.state) {
                "open" -> SubjectState.OPEN
                "merged" -> SubjectState.MERGED
                "closed" -> SubjectState.CLOSED
                else -> null
            },
        )
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MARKER = "checks:"
        const val FULL_EVERY = 4

        /** Forgejo announces no poll interval; a minute, like GitHub's usual. */
        const val POLL_SECONDS = 60

        fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    }
}

@Serializable
private data class CountJson(val new: Int = 0)

@Serializable
private data class SubjectJson(
    val title: String,
    val type: String? = null,
    val state: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
private data class ThreadRepoJson(val name: String, val owner: UserJson)

@Serializable
private data class ThreadJson(
    val id: Long,
    val unread: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    val repository: ThreadRepoJson? = null,
    val subject: SubjectJson? = null,
) {
    /** The issue or pull request number, from the subject's page (`.../issues/12`, `.../pulls/7`). */
    fun number(): Int? = subject?.takeIf { it.type == "Issue" || it.type == "Pull" }?.htmlUrl?.substringAfterLast('/')?.toIntOrNull()
}
