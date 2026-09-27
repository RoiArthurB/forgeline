package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import java.time.Instant

class FakeNotificationsApi : NotificationsApi {
    var threads: List<NotificationThread> = emptyList()
    /** When set, the next sync answers "not modified". */
    var notModified = false
    var pollIntervalSeconds: Int? = 60
    var failure: ForgeError? = null
    val calls = mutableListOf<String>()
    val ifModifiedSince = mutableListOf<String?>()

    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> {
        calls += "threads"
        this.ifModifiedSince += ifModifiedSince
        failure?.let { return ForgeResult.Failure(it) }
        if (notModified) return ForgeResult.Success(NotificationsSync(null, ifModifiedSince, pollIntervalSeconds))
        return ForgeResult.Success(NotificationsSync(threads, "modified-${calls.size}", pollIntervalSeconds))
    }

    private fun action(name: String): ForgeResult<Unit> {
        calls += name
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(Unit)
    }

    override suspend fun markRead(token: String, threadId: String) = action("read:$threadId")

    override suspend fun markDone(token: String, threadId: String) = action("done:$threadId")

    override suspend fun unsubscribe(token: String, threadId: String) = action("unsubscribe:$threadId")
}

fun notificationThread(
    id: String,
    repo: String = "acme/rocket",
    title: String = "Thread $id",
    reason: NotificationReason = NotificationReason.MENTION,
    unread: Boolean = true,
    updatedAt: String = "2026-09-27T09:00:00Z",
    type: SubjectType = SubjectType.ISSUE,
    number: Int? = id.toIntOrNull(),
): NotificationThread {
    val (owner, name) = repo.split('/')
    return NotificationThread(id, RepoId(owner, name), title, type, number, reason, unread, Instant.parse(updatedAt))
}
