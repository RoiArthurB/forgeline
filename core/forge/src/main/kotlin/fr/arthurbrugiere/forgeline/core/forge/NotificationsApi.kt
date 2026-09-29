package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectState

/** [threads] is null when nothing changed since `ifModifiedSince` (free on GitHub: no rate limit cost). */
data class NotificationsSync(
    val threads: List<NotificationThread>?,
    val lastModified: String?,
    /** Minimum seconds the forge asks clients to wait between polls. */
    val pollIntervalSeconds: Int?,
)

interface NotificationsApi {
    /** Read and unread threads, newest first, up to [maxPages] pages. */
    suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int = 3): ForgeResult<NotificationsSync>

    suspend fun markRead(token: String, threadId: String): ForgeResult<Unit>

    /** Removes the thread from the inbox until there's new activity. */
    suspend fun markDone(token: String, threadId: String): ForgeResult<Unit>

    suspend fun unsubscribe(token: String, threadId: String): ForgeResult<Unit>

    /** Where each issue or pull request stands now. Ones that no longer exist are left out. */
    suspend fun subjectStates(token: String, subjects: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>>
}
