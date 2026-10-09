package fr.arthurbrugiere.forgeline.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeInboxRepository
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import fr.arthurbrugiere.forgeline.notifications.EXTRA_LAST_READ_AT
import fr.arthurbrugiere.forgeline.notifications.EXTRA_UNREAD
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InboxWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val crash = notificationThread("42", repo = "octo/tools", title = "Crash on start")
    private val review = notificationThread("7", repo = "alice/notes", title = "Add a dark theme", type = SubjectType.PULL_REQUEST)

    // Under a widget host, as on a home screen: Android only fills a widget's list there.
    private fun drawn(state: InboxWidgetState): View = inboxWidgetViews(context, state).apply(context, android.appwidget.AppWidgetHostView(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun what_is_unread_is_counted_and_listed() {
        val widget = drawn(InboxWidgetState(signedIn = true, unread = listOf(crash, review)))

        assertThat(widget.text(R.id.widget_title)).isEqualTo("Inbox")
        assertThat(widget.text(R.id.widget_count)).isEqualTo("2 unread")
        assertThat(widget.findViewById<View>(R.id.widget_list).visibility).isEqualTo(View.VISIBLE)
        assertThat(widget.findViewById<View>(R.id.widget_empty).visibility).isEqualTo(View.GONE)
        val list = widget.findViewById<ListView>(R.id.widget_list)
        assertThat(list.adapter.count).isEqualTo(2)
        val first = list.adapter.getView(0, null, list)
        assertThat(first.text(R.id.widget_row_repo)).isEqualTo("octo/tools")
        assertThat(first.text(R.id.widget_row_title)).isEqualTo("Crash on start")
        assertThat(list.adapter.getView(1, null, list).text(R.id.widget_row_title)).isEqualTo("Add a dark theme")
    }

    @Test
    fun nothing_unread_says_so_in_place_of_the_list() {
        val widget = drawn(InboxWidgetState(signedIn = true, unread = emptyList()))

        assertThat(widget.text(R.id.widget_count)).isEmpty()
        assertThat(widget.findViewById<View>(R.id.widget_list).visibility).isEqualTo(View.GONE)
        assertThat(widget.findViewById<View>(R.id.widget_empty).visibility).isEqualTo(View.VISIBLE)
        assertThat(widget.text(R.id.widget_empty)).isEqualTo("You're all caught up")
    }

    @Test
    fun signed_out_it_invites_to_sign_in() {
        val widget = drawn(InboxWidgetState(signedIn = false, unread = emptyList()))

        assertThat(widget.text(R.id.widget_empty)).isEqualTo("Sign in to see your notifications")
    }

    @Test
    @Config(qualifiers = "fr")
    fun it_reads_in_french_on_a_french_phone() {
        val widget = drawn(InboxWidgetState(signedIn = true, unread = listOf(crash)))

        assertThat(widget.text(R.id.widget_title)).isEqualTo("Réception")
        assertThat(widget.text(R.id.widget_count)).isEqualTo("1 non lue")
    }

    @Test
    fun a_row_leads_to_its_conversation_opened_at_what_is_new() {
        val read = Instant.parse("2026-09-26T09:30:30Z")

        val issue = openThread(crash.copy(lastReadAt = read))
        val pull = openThread(review)

        assertThat(issue.dataString).isEqualTo("https://github.com/octo/tools/issues/42")
        assertThat(issue.getBooleanExtra(EXTRA_UNREAD, false)).isTrue()
        assertThat(issue.getLongExtra(EXTRA_LAST_READ_AT, -1)).isEqualTo(read.toEpochMilli())
        assertThat(pull.dataString).isEqualTo("https://github.com/alice/notes/pull/7")
        // Not known when it was last read: nothing is said of it.
        assertThat(pull.hasExtra(EXTRA_LAST_READ_AT)).isFalse()
    }

    @Test
    fun a_row_names_its_forge_when_it_is_not_github() {
        val elsewhere = crash.copy(repo = crash.repo.copy(forge = ForgeInstance.Codeberg))

        assertThat(rowHeading(crash)).isEqualTo("octo/tools")
        assertThat(rowHeading(elsewhere)).isEqualTo("octo/tools · Codeberg")
    }

    // The updater, with a widget on the home screen or without.

    private val inbox = FakeInboxRepository()
    private val accounts = FakeAccountRepository()
    private val manager = AppWidgetManager.getInstance(context)

    private fun TestScope.updater() = InboxWidgetUpdater(context, { inbox }, { accounts }, TestScope(UnconfinedTestDispatcher(testScheduler)))

    /** Puts a widget on the home screen without Android asking its provider to draw it. */
    private fun placeWidget(): Int = shadowOf(manager).createWidgets(NoProvider::class.java, R.layout.widget_inbox, 1).single().also { id ->
        shadowOf(manager).bindAppWidgetId(id, android.content.ComponentName(context, InboxWidgetProvider::class.java))
    }

    class NoProvider : android.appwidget.AppWidgetProvider()

    @Test
    fun with_no_widget_placed_the_inbox_is_not_even_watched() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        inbox.set(crash)

        updater().refresh()

        assertThat(inbox.snapshot.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun a_widget_placed_follows_the_inbox_as_it_changes() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        inbox.set(crash, review.copy(unread = false))
        val id = placeWidget()

        updater().refresh()

        assertThat(shadowOf(manager).getViewFor(id).text(R.id.widget_count)).isEqualTo("1 unread")
        // Something new arrives, then everything is read.
        inbox.set(crash, review)
        assertThat(shadowOf(manager).getViewFor(id).text(R.id.widget_count)).isEqualTo("2 unread")
        inbox.set(crash.copy(unread = false), review.copy(unread = false))
        assertThat(shadowOf(manager).getViewFor(id).text(R.id.widget_empty)).isEqualTo("You're all caught up")
        assertThat(shadowOf(manager).getViewFor(id).findViewById<View>(R.id.widget_list).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun asking_twice_watches_the_inbox_once() = runTest {
        placeWidget()
        val updater = updater()

        updater.refresh()
        updater.refresh()

        assertThat(inbox.snapshot.subscriptionCount.value).isEqualTo(1)
    }

    @Test
    fun signing_out_turns_the_widget_into_an_invitation() = runTest {
        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        inbox.set(crash)
        val id = placeWidget()
        updater().refresh()

        accounts.signOut(account.id)
        inbox.set()

        assertThat(shadowOf(manager).getViewFor(id).text(R.id.widget_empty)).isEqualTo("Sign in to see your notifications")
    }

    @Test
    fun a_row_on_each_forge_leads_back_to_the_conversation_it_names() {
        val lab = ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.GITLAB, "lab.example.org")
        fr.arthurbrugiere.forgeline.core.model.KnownForges.remember(lab)
        try {
            fun opened(forge: ForgeInstance, owner: String, pull: Boolean) =
                fr.arthurbrugiere.forgeline.navigation.linkRoute(openThread((if (pull) review else crash).let { it.copy(repo = it.repo.copy(owner = owner, forge = forge)) }).dataString!!)

            assertThat(opened(ForgeInstance.Codeberg, "alice", pull = true)).isEqualTo(fr.arthurbrugiere.forgeline.navigation.IssueRoute("codeberg.org", "alice", "notes", 7))
            // GitLab numbers merge requests apart from issues: the row says which it is.
            assertThat(opened(ForgeInstance.GitLab, "group/sub", pull = true))
                .isEqualTo(fr.arthurbrugiere.forgeline.navigation.IssueRoute("gitlab.com", "group/sub", "notes", 7, isPullRequest = true))
            assertThat(opened(ForgeInstance.GitLab, "group/sub", pull = false))
                .isEqualTo(fr.arthurbrugiere.forgeline.navigation.IssueRoute("gitlab.com", "group/sub", "tools", 42, isPullRequest = false))
            // A self-hosted server signed in to: its rows open in the app too.
            assertThat(opened(lab, "team", pull = true)).isEqualTo(fr.arthurbrugiere.forgeline.navigation.IssueRoute("lab.example.org", "team", "notes", 7, isPullRequest = true))
        } finally {
            fr.arthurbrugiere.forgeline.core.model.KnownForges.clear()
        }
    }
}
