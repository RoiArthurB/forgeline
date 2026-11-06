package fr.arthurbrugiere.forgeline.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.annotation.StringRes
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.arthurbrugiere.forgeline.MainActivity
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.model.webUrl
import javax.inject.Inject

interface InboxNotifier {
    fun show(threads: List<NotificationThread>)
}

/** One Android channel per kind of activity, so people can silence CI without missing mentions. */
enum class InboxChannel(val id: String, @param:StringRes val label: Int, val importance: Int) {
    DIRECT("inbox_direct", R.string.channel_direct, NotificationManager.IMPORTANCE_HIGH),
    PARTICIPATING("inbox_participating", R.string.channel_participating, NotificationManager.IMPORTANCE_DEFAULT),
    WATCHING("inbox_watching", R.string.channel_watching, NotificationManager.IMPORTANCE_LOW),
    CI("inbox_ci", R.string.channel_ci, NotificationManager.IMPORTANCE_LOW),
    SECURITY("inbox_security", R.string.channel_security, NotificationManager.IMPORTANCE_HIGH),
    ;

    companion object {
        fun forReason(reason: NotificationReason): InboxChannel = when (reason) {
            NotificationReason.MENTION, NotificationReason.TEAM_MENTION,
            NotificationReason.REVIEW_REQUESTED, NotificationReason.ASSIGN,
            -> DIRECT
            NotificationReason.AUTHOR, NotificationReason.COMMENT, NotificationReason.STATE_CHANGE -> PARTICIPATING
            NotificationReason.CI_ACTIVITY -> CI
            NotificationReason.SECURITY_ALERT -> SECURITY
            else -> WATCHING
        }
    }
}

/** Whether the thread a notification opens is unread, and when it was last read: see [linkRoute]. */
const val EXTRA_UNREAD = "fr.arthurbrugiere.forgeline.UNREAD"
const val EXTRA_LAST_READ_AT = "fr.arthurbrugiere.forgeline.LAST_READ_AT"

/** Every Inbox notification is posted under this tag, with [notificationId] for its thread. */
const val NOTIFICATION_TAG = "inbox"

/** A thread's notification, by account and id: two forges can use the same thread id. */
fun notificationId(accountId: String, threadId: String): Int = "$accountId|$threadId".hashCode()

/** The web page of a thread; the app routes it back in-app when the notification is tapped. */
fun inboxLink(thread: NotificationThread): String {
    val number = thread.number ?: return thread.repo.webUrl
    return when (thread.type) {
        SubjectType.ISSUE -> IssueRef(thread.repo, number).webUrl(isPullRequest = false)
        SubjectType.PULL_REQUEST -> IssueRef(thread.repo, number).webUrl(isPullRequest = true)
        else -> thread.repo.webUrl
    }
}

class SystemInboxNotifier @Inject constructor(@param:ApplicationContext private val context: Context) : InboxNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun show(threads: List<NotificationThread>) {
        manager.createNotificationChannels(
            InboxChannel.entries.map { NotificationChannel(it.id, context.getString(it.label), it.importance) },
        )
        // Also false on Android 13+ until the notification permission is granted.
        if (!manager.areNotificationsEnabled()) return
        threads.forEach { thread -> manager.notify(NOTIFICATION_TAG, notificationId(thread.accountId, thread.id), build(thread)) }
    }

    private fun build(thread: NotificationThread): Notification {
        val open = Intent(Intent.ACTION_VIEW, inboxLink(thread).toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // What the link can't say: the conversation opens at what is new since it was last read.
            .putExtra(EXTRA_UNREAD, thread.unread)
            .apply { thread.lastReadAt?.let { putExtra(EXTRA_LAST_READ_AT, it.toEpochMilli()) } }
        val tap = PendingIntent.getActivity(
            context,
            thread.key.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, InboxChannel.forReason(thread.reason).id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(thread.repo.fullName)
            .setContentText(thread.title)
            // Named when it's not GitHub, so nothing changes with GitHub alone.
            .apply { if (thread.repo.forge != ForgeInstance.GitHub) setSubText(thread.repo.forge.displayName) }
            .setWhen(thread.updatedAt.toEpochMilli())
            .setShowWhen(true)
            .setContentIntent(tap)
            // Sorted from the notification shade, as with a swipe in the Inbox: read (when it isn't yet) or done.
            .apply { if (thread.unread) addAction(action(thread, ACTION_MARK_READ, R.string.inbox_mark_read)) }
            .addAction(action(thread, ACTION_MARK_DONE, R.string.inbox_mark_done))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .build()
    }

    private fun action(thread: NotificationThread, what: String, @StringRes label: Int): Notification.Action {
        val intent = Intent(what, null, context, InboxActionReceiver::class.java)
            .putExtra(EXTRA_ACCOUNT_ID, thread.accountId)
            .putExtra(EXTRA_THREAD_ID, thread.id)
        // One per thread and button: a request code shared by two would hand the second the first one's thread.
        val pending = PendingIntent.getBroadcast(
            context,
            "$what|${thread.key}".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), context.getString(label), pending).build()
    }
}
