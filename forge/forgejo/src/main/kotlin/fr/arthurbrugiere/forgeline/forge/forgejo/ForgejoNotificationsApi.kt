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
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Issue
import fr.arthurbrugiere.forgeline.forge.forgejo.api.NotificationCount
import fr.arthurbrugiere.forgeline.forge.forgejo.api.User
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.ProtocolException
import fr.arthurbrugiere.forgeline.forge.forgejo.api.NotificationThread as ApiThread

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
    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> = forgeCall {
        val checks = ifModifiedSince?.removePrefix(MARKER)?.toIntOrNull()
        if (checks != null && checks + 1 < FULL_EVERY) {
            val count = httpClient.forgejoApi(forge, token, "notifications", "new")
            if (count.status != HttpStatusCode.OK && count.status != HttpStatusCode.NoContent) return@forgeCall count.failure()
            val new = if (count.status == HttpStatusCode.NoContent) 0 else count.body<NotificationCount>().new.count()
            if (new == 0) return@forgeCall ForgeResult.Success(NotificationsSync(null, "$MARKER${checks + 1}", POLL_SECONDS))
        }
        // The first page says how many threads there are: the others are asked all at once, not one after another.
        val first = page(token, 1)
        if (first.status != HttpStatusCode.OK) return@forgeCall first.failure()
        val firstBatch = first.body<List<ApiThread>>()
        val pages = first.totalCount()?.let { (it + PAGE_SIZE - 1) / PAGE_SIZE }
            ?: first.nextPage()?.let { 2 }
            ?: if (firstBatch.size == PAGE_SIZE) 2 else 1
        val rest = coroutineScope { (2..minOf(pages, maxPages)).map { number -> async { page(token, number) } }.awaitAll() }
        rest.firstOrNull { it.status != HttpStatusCode.OK }?.let { return@forgeCall it.failure() }
        val threads = firstBatch + rest.flatMap { it.body<List<ApiThread>>() }
        val reasons = reasons(token, threads)
        ForgeResult.Success(NotificationsSync(threads.mapNotNull { it.toModel(reasons) }, "${MARKER}0", POLL_SECONDS))
    }

    private suspend fun page(token: String, number: Int) = httpClient.forgejoApi(
        forge, token, "notifications",
        query = mapOf("all" to "true", "limit" to "$PAGE_SIZE", "page" to number.toString()),
    )

    override suspend fun markRead(token: String, threadId: String): ForgeResult<Unit> = mark(token, threadId, "read")

    override suspend fun markUnread(token: String, threadId: String): ForgeResult<Unit> = mark(token, threadId, "unread")

    private suspend fun mark(token: String, threadId: String, status: String): ForgeResult<Unit> = forgeCall {
        try {
            httpClient.forgejoApi(forge, token, "notifications", "threads", threadId, method = HttpMethod.Patch, query = mapOf("to-status" to status))
                .toResult { }
        } catch (e: IOException) {
            // Forgejo answers 205 with the thread as a body, which HTTP forbids and OkHttp refuses to read. The answer
            // is thrown away, but it was a yes: the thread is marked.
            if (e.isResetContentWithBody()) ForgeResult.Success(Unit) else throw e
        }
    }

    private fun Throwable.isResetContentWithBody(): Boolean =
        generateSequence(this) { it.cause }.any { it is ProtocolException && it.message?.startsWith("HTTP 205") == true }

    /** Forgejo can't make a thread go away: it's marked read, and the Inbox remembers it was done. */
    override suspend fun markDone(token: String, threadId: String): ForgeResult<Unit> = markRead(token, threadId)

    override suspend fun unsubscribe(token: String, threadId: String): ForgeResult<Unit> = forgeCall {
        // Subscriptions belong to issues here: find the thread's issue, and who "me" is.
        val thread = httpClient.forgejoApi(forge, token, "notifications", "threads", threadId)
        if (thread.status != HttpStatusCode.OK) return@forgeCall thread.failure()
        val subject = thread.body<ApiThread>()
        val repo = subject.repository?.id(forge) ?: return@forgeCall ForgeResult.Success(Unit)
        val number = subject.number() ?: return@forgeCall ForgeResult.Success(Unit)
        val me = httpClient.forgejoApi(forge, token, "user")
        if (me.status != HttpStatusCode.OK) return@forgeCall me.failure()
        httpClient.forgejoApi(
            forge, token, "repos", repo.owner, repo.name, "issues", number.toString(), "subscriptions", me.body<User>().name,
            method = HttpMethod.Delete,
        ).toResult { }
    }

    /** States come with the threads themselves. */
    override suspend fun subjectStates(token: String, subjects: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>> =
        ForgeResult.Success(emptyMap())

    /** Each conversation's strongest reason, from searches that mirror GitHub's reasons. */
    private suspend fun reasons(token: String, threads: List<ApiThread>): Map<String, NotificationReason> {
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
                    // These searches are slow on a big instance (Codeberg: 7 to 11 s each, measured 2026-10-01), so they
                    // get more time than other calls, and one that still fails only loses its own reason: the threads
                    // themselves must show whatever happens here.
                    val response = try {
                        httpClient.forgejoApi(forge, token, "repos", "issues", "search", query = filter + ("limit" to "$PAGE_SIZE"), patienceMillis = SEARCH_PATIENCE_MILLIS)
                    } catch (e: IOException) {
                        return@async emptyList()
                    }
                    if (response.status != HttpStatusCode.OK) return@async emptyList()
                    response.body<List<Issue>>().mapNotNull { issue -> issue.repository?.let { repo -> "${repo.owner ?: return@mapNotNull null}/${repo.name ?: return@mapNotNull null}#${issue.number}".lowercase() to reason } }
                }
            }.awaitAll().flatten()
        }
        // Searches run strongest first: the first reason found for a conversation wins.
        return found.groupBy({ it.first }, { it.second }).mapValues { (_, reasons) -> reasons.first() }
    }

    private fun ApiThread.toModel(reasons: Map<String, NotificationReason>): NotificationThread? {
        val repository = repository ?: return null
        val repo = repository.id(forge)
        val subject = subject ?: return null
        val number = number()
        val type = when (subject.type) {
            "Issue" -> SubjectType.ISSUE
            "Pull" -> SubjectType.PULL_REQUEST
            "Commit" -> SubjectType.COMMIT
            else -> SubjectType.OTHER
        }
        return NotificationThread(
            id = required(id, "a thread's id").toString(),
            repo = repo,
            title = required(subject.title, "a thread's title"),
            type = type,
            number = number,
            reason = number?.let { reasons["${repo.fullName}#$it".lowercase()] } ?: NotificationReason.SUBSCRIBED,
            unread = unread == true,
            updatedAt = instant(updatedAt) ?: java.time.Instant.EPOCH,
            ownerAvatarUrl = repository.logoUrl,
            state = when (subject.state) {
                "open" -> SubjectState.OPEN
                "merged" -> SubjectState.MERGED
                "closed" -> SubjectState.CLOSED
                else -> null
            },
        )
    }

    private companion object {
        /** How long a reason search may stay silent: well past the slowest seen, short of leaving the Inbox waiting forever. */
        const val SEARCH_PATIENCE_MILLIS = 30_000L
        const val PAGE_SIZE = 50
        const val MARKER = "checks:"
        const val FULL_EVERY = 4

        /** Forgejo announces no poll interval; a minute, like GitHub's usual. */
        const val POLL_SECONDS = 60

        fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
            pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    }
}

/** The issue or pull request number, from the subject's page (`.../issues/12`, `.../pulls/7`). */
private fun ApiThread.number(): Int? = subject?.takeIf { it.type == "Issue" || it.type == "Pull" }?.htmlUrl?.substringAfterLast('/')?.toIntOrNull()
