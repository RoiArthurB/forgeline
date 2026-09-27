package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.inbox.InboxRepository
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxSnapshot
import fr.arthurbrugiere.forgeline.core.data.inbox.SyncResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeInboxRepository : InboxRepository {
    val snapshot = MutableStateFlow(InboxSnapshot(emptyList(), null))
    var nextSync: SyncResult = SyncResult.NotModified
    val syncs = mutableListOf<Boolean>()
    val actions = mutableListOf<String>()
    var actionFailure: ForgeError? = null
    var toNotify: List<NotificationThread> = emptyList()

    fun set(vararg threads: NotificationThread) {
        snapshot.value = InboxSnapshot(threads.toList(), 1_000)
    }

    override fun observe(): Flow<InboxSnapshot> = snapshot

    override suspend fun sync(force: Boolean): SyncResult {
        syncs += force
        return nextSync
    }

    private fun act(name: String, id: String, apply: (List<NotificationThread>) -> List<NotificationThread>): ForgeResult<Unit> {
        actions += "$name:$id"
        actionFailure?.let { return ForgeResult.Failure(it) }
        snapshot.update { it.copy(threads = apply(it.threads)) }
        return ForgeResult.Success(Unit)
    }

    override suspend fun markRead(threadId: String) = act("read", threadId) { list -> list.map { if (it.id == threadId) it.copy(unread = false) else it } }

    override suspend fun markDone(threadId: String) = act("done", threadId) { list -> list.filterNot { it.id == threadId } }

    override suspend fun unsubscribe(threadId: String) = act("unsubscribe", threadId) { list -> list.filterNot { it.id == threadId } }

    override suspend fun takeThreadsToNotify(): List<NotificationThread> = toNotify.also { toNotify = emptyList() }
}
