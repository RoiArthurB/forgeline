package fr.arthurbrugiere.forgeline.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.MainActivity
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxRepository
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.notifications.EXTRA_LAST_READ_AT
import fr.arthurbrugiere.forgeline.notifications.EXTRA_UNREAD
import fr.arthurbrugiere.forgeline.notifications.inboxLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the widget shows: whether anyone is signed in, and what they haven't read, newest first. */
data class InboxWidgetState(val signedIn: Boolean, val unread: List<NotificationThread>)

/** The Inbox on the home screen: what is unread, each line one tap from its conversation. */
class InboxWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        EntryPointAccessors.fromApplication<Dependencies>(context).inboxWidget().refresh()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun inboxWidget(): InboxWidgetUpdater
    }
}

/**
 * Keeps the widgets on the home screen in line with the inbox. It reads what the app already keeps (a background
 * check fills it), and asks the forges nothing itself. With no widget placed it does nothing, and opens nothing.
 */
@Singleton
class InboxWidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    // Lazy: they open the database, which must not happen where this is injected at launch.
    private val inbox: Lazy<InboxRepository>,
    private val accounts: Lazy<AccountRepository>,
    @param:BackgroundScope private val scope: CoroutineScope,
) {
    private val manager: AppWidgetManager? get() = AppWidgetManager.getInstance(context)
    private var watching: Job? = null
    private var last: InboxWidgetState? = null

    private fun widgetIds(): IntArray = manager?.getAppWidgetIds(ComponentName(context, InboxWidgetProvider::class.java)) ?: IntArray(0)

    /** Draws the widgets with what is known now, and from then on each time the inbox changes. */
    @Synchronized
    fun refresh() {
        if (widgetIds().isEmpty()) return
        // A widget just placed is drawn at once with what the others show.
        last?.let(::draw)
        if (watching?.isActive == true) return
        watching = scope.launch {
            combine(inbox.get().observe(), accounts.get().accounts) { snapshot, signedIn ->
                InboxWidgetState(signedIn.isNotEmpty(), snapshot.threads.filter { it.unread }.take(MAX_ROWS))
            }.distinctUntilChanged().collect { state ->
                last = state
                draw(state)
            }
        }
    }

    private fun draw(state: InboxWidgetState) {
        val ids = widgetIds()
        if (ids.isNotEmpty()) manager?.updateAppWidget(ids, inboxWidgetViews(context, state))
    }

    private companion object {
        // What fits a scroll or two: the widget is a glance, the app holds the rest.
        const val MAX_ROWS = 25
    }
}

/** The widget as Android draws it for [state]. */
fun inboxWidgetViews(context: Context, state: InboxWidgetState): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_inbox)
    val count = state.unread.size
    views.setTextViewText(R.id.widget_count, if (count == 0) "" else context.resources.getQuantityString(R.plurals.widget_unread, count, count))
    views.setViewVisibility(R.id.widget_list, if (count == 0) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.widget_empty, if (count == 0) View.VISIBLE else View.GONE)
    views.setTextViewText(R.id.widget_empty, context.getString(if (state.signedIn) R.string.inbox_caught_up_title else R.string.widget_signed_out))
    // The heading, and the words shown when nothing is unread, open the app where it starts.
    val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    views.setOnClickPendingIntent(R.id.widget_header, openApp)
    views.setOnClickPendingIntent(R.id.widget_empty, openApp)

    val rows = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
    state.unread.forEach { thread ->
        val row = RemoteViews(context.packageName, R.layout.widget_inbox_row)
        row.setTextViewText(R.id.widget_row_repo, rowHeading(thread))
        row.setTextViewText(R.id.widget_row_title, thread.title)
        row.setOnClickFillInIntent(R.id.widget_row, openThread(thread))
        rows.addItem(thread.key.hashCode().toLong(), row)
    }
    views.setRemoteAdapter(R.id.widget_list, rows.build())
    // Each row fills in where it leads: one template for the list is all Android allows.
    val open = Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    views.setPendingIntentTemplate(R.id.widget_list, PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    return views
}

/** The repository a thread is in, with its forge when that isn't GitHub (as notifications name it). */
internal fun rowHeading(thread: NotificationThread): String =
    if (thread.repo.forge == ForgeInstance.GitHub) thread.repo.fullName else "${thread.repo.fullName} · ${thread.repo.forge.displayName}"

/** What a row adds to the list's template: its page, opened at what is new, like its notification does. */
internal fun openThread(thread: NotificationThread): Intent =
    Intent().setData(inboxLink(thread).toUri())
        .putExtra(EXTRA_UNREAD, thread.unread)
        .apply { thread.lastReadAt?.let { putExtra(EXTRA_LAST_READ_AT, it.toEpochMilli()) } }
