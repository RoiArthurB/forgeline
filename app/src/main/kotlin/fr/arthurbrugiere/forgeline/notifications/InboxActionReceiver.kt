package fr.arthurbrugiere.forgeline.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.InboxAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a phone notification's buttons ask, with the thread they ask it of. */
const val ACTION_MARK_READ = "fr.arthurbrugiere.forgeline.action.MARK_READ"
const val ACTION_MARK_DONE = "fr.arthurbrugiere.forgeline.action.MARK_DONE"
const val EXTRA_ACCOUNT_ID = "fr.arthurbrugiere.forgeline.ACCOUNT_ID"
const val EXTRA_THREAD_ID = "fr.arthurbrugiere.forgeline.THREAD_ID"

/**
 * Marks a thread read or done from its notification, without opening the app. The action is held first, as a swipe's
 * is: should the phone end the app before the forge answers, the next check sends it.
 */
suspend fun InboxRepository.actOnNotification(action: InboxAction, accountId: String, threadId: String): ForgeResult<Unit>? {
    val run: suspend () -> ForgeResult<Unit> = when (action) {
        InboxAction.READ -> { { markRead(accountId, threadId) } }
        InboxAction.DONE -> { { markDone(accountId, threadId) } }
        InboxAction.UNREAD, InboxAction.UNSUBSCRIBE -> return null
    }
    hold(listOf("$accountId|$threadId"), action)
    return run()
}

/**
 * The thread an unread notification or widget row opened, as its account and id: opening it reads it, as opening it
 * from the Inbox does. Null for any other link, or a thread already read.
 */
fun Intent.unreadThreadOpened(): Pair<String, String>? {
    if (!getBooleanExtra(EXTRA_UNREAD, false)) return null
    val accountId = getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotEmpty() } ?: return null
    val threadId = getStringExtra(EXTRA_THREAD_ID)?.takeIf { it.isNotEmpty() } ?: return null
    return accountId to threadId
}

/** Where a notification's "Mark as read" and "Done" land: the notification goes at once, the forge is told after. */
class InboxActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            ACTION_MARK_READ -> InboxAction.READ
            ACTION_MARK_DONE -> InboxAction.DONE
            else -> return
        }
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: return
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: return
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_TAG, notificationId(accountId, threadId))
        val dependencies = EntryPointAccessors.fromApplication<Dependencies>(context)
        // A receiver has about ten seconds: the forge's answer is waited for in the app's own scope, the receiver kept
        // alive meanwhile.
        val pending = goAsync()
        dependencies.scope().launch {
            try {
                dependencies.inbox().actOnNotification(action, accountId, threadId)
            } finally {
                pending.finish()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun inbox(): InboxRepository

        @BackgroundScope
        fun scope(): CoroutineScope
    }
}
