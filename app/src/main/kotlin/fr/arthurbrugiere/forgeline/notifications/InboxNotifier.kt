package fr.arthurbrugiere.forgeline.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.arthurbrugiere.forgeline.MainActivity
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.SubjectType
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

/** The web page of a thread; the app routes it back in-app when the notification is tapped. */
fun inboxLink(thread: NotificationThread): String {
    val base = "https://github.com/${thread.repo.fullName}"
    val number = thread.number ?: return base
    return when (thread.type) {
        SubjectType.ISSUE -> "$base/issues/$number"
        SubjectType.PULL_REQUEST -> "$base/pull/$number"
        else -> base
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
        threads.forEach { thread -> manager.notify(TAG, thread.id.hashCode(), build(thread)) }
    }

    private fun build(thread: NotificationThread): Notification {
        val open = Intent(Intent.ACTION_VIEW, inboxLink(thread).toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(
            context,
            thread.id.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, InboxChannel.forReason(thread.reason).id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(thread.repo.fullName)
            .setContentText(thread.title)
            .setWhen(thread.updatedAt.toEpochMilli())
            .setShowWhen(true)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .build()
    }

    private companion object {
        const val TAG = "inbox"
    }
}
