package fr.arthurbrugiere.forgeline.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.MainActivity
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.navigation.IssueRoute
import fr.arthurbrugiere.forgeline.navigation.linkRoute
import fr.arthurbrugiere.forgeline.notifications.EXTRA_UNREAD
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The widget as Android meets it: declared in the manifest, drawn by its provider, and leading into the app. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class InboxWidgetPlacedTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()

    @Test
    fun android_can_offer_the_widget() {
        val receivers = context.packageManager.queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.packageName), 0)

        assertThat(receivers.map { it.activityInfo.name }).contains(InboxWidgetProvider::class.java.name)
    }

    @Test
    fun a_widget_placed_while_signed_out_is_drawn_as_an_invitation() {
        val manager = AppWidgetManager.getInstance(context)

        // Android asks the provider to draw it, as when it is dropped on the home screen.
        val id = shadowOf(manager).createWidget(InboxWidgetProvider::class.java, R.layout.widget_inbox)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertThat(manager.getAppWidgetIds(ComponentName(context, InboxWidgetProvider::class.java)).toList()).contains(id)
        val widget = shadowOf(manager).getViewFor(id)
        val deadline = System.currentTimeMillis() + 5_000
        while (widget.findViewById<TextView>(R.id.widget_empty).text.toString() != "Sign in to see your notifications" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
        }
        assertThat(shadowOf(manager).getViewFor(id).findViewById<TextView>(R.id.widget_empty).text.toString()).isEqualTo("Sign in to see your notifications")
        assertThat(shadowOf(manager).getViewFor(id).findViewById<View>(R.id.widget_list).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun a_row_s_tap_reaches_the_app_as_a_link_to_its_conversation() {
        val thread = notificationThread("42", repo = "octo/tools", title = "Crash on start")
        // What Android does with the list's template and the row tapped.
        val template = Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java)
        template.fillIn(openThread(thread), 0)

        assertThat(template.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(linkRoute(template.dataString!!, template.getBooleanExtra(EXTRA_UNREAD, false)))
            .isEqualTo(IssueRoute("github.com", "octo", "tools", 42, unread = true))
    }
}
