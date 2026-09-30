package fr.arthurbrugiere.forgeline.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxRepository
import fr.arthurbrugiere.forgeline.core.data.inbox.SyncResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError

/** Background Inbox check: syncs, then posts a notification for each thread with new activity. */
class InboxSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val inbox: InboxRepository,
    private val notifier: InboxNotifier,
) : CoroutineWorker(context, params) {

    // WorkManager's default factory uses this constructor.
    @Suppress("unused")
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        EntryPointAccessors.fromApplication<Dependencies>(context).inbox(),
        EntryPointAccessors.fromApplication<Dependencies>(context).notifier(),
    )

    // Waits for the follow-ups (states, conversations ahead): nothing keeps the process alive after the work ends.
    override suspend fun doWork(): Result = when (val result = inbox.sync(waitForFollowUps = true)) {
        is SyncResult.Failed -> if (result.error is ForgeError.Network) Result.retry() else Result.success()
        SyncResult.SignedOut -> Result.success()
        else -> {
            notifier.show(inbox.takeThreadsToNotify())
            Result.success()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun inbox(): InboxRepository

        fun notifier(): InboxNotifier
    }
}
