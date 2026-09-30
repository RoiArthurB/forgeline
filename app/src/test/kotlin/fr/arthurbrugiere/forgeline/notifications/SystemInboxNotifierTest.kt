package fr.arthurbrugiere.forgeline.notifications

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SystemInboxNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifier = SystemInboxNotifier(context)

    @Test
    fun reasons_map_to_channels_by_urgency() {
        assertThat(InboxChannel.forReason(NotificationReason.MENTION)).isEqualTo(InboxChannel.DIRECT)
        assertThat(InboxChannel.forReason(NotificationReason.REVIEW_REQUESTED)).isEqualTo(InboxChannel.DIRECT)
        assertThat(InboxChannel.forReason(NotificationReason.ASSIGN)).isEqualTo(InboxChannel.DIRECT)
        assertThat(InboxChannel.forReason(NotificationReason.COMMENT)).isEqualTo(InboxChannel.PARTICIPATING)
        assertThat(InboxChannel.forReason(NotificationReason.SUBSCRIBED)).isEqualTo(InboxChannel.WATCHING)
        assertThat(InboxChannel.forReason(NotificationReason.CI_ACTIVITY)).isEqualTo(InboxChannel.CI)
        assertThat(InboxChannel.forReason(NotificationReason.SECURITY_ALERT)).isEqualTo(InboxChannel.SECURITY)
    }

    @Test
    fun threads_link_to_their_page_so_the_app_can_open_them() {
        assertThat(inboxLink(notificationThread("42", repo = "acme/rocket"))).isEqualTo("https://github.com/acme/rocket/issues/42")
        assertThat(inboxLink(notificationThread("43", repo = "acme/rocket", type = SubjectType.PULL_REQUEST)))
            .isEqualTo("https://github.com/acme/rocket/pull/43")
        assertThat(inboxLink(notificationThread("9", repo = "octo/tools", type = SubjectType.RELEASE, number = null)))
            .isEqualTo("https://github.com/octo/tools")
    }

    @Test
    fun posts_one_notification_per_thread_on_its_channel() {
        notifier.show(
            listOf(
                notificationThread("42", repo = "acme/rocket", title = "Launch fails", reason = NotificationReason.MENTION),
                notificationThread("7", repo = "octo/tools", title = "CI run", reason = NotificationReason.CI_ACTIVITY),
            ),
        )

        val posted = shadowOf(manager).allNotifications
        assertThat(posted.map { it.channelId }).containsAtLeast(InboxChannel.DIRECT.id, InboxChannel.CI.id)
        val mention = posted.single { it.channelId == InboxChannel.DIRECT.id }
        assertThat(mention.extras.getString("android.title")).isEqualTo("acme/rocket")
        assertThat(mention.extras.getCharSequence("android.text").toString()).isEqualTo("Launch fails")
        val tap = shadowOf(mention.contentIntent).savedIntent
        assertThat(tap.dataString).isEqualTo("https://github.com/acme/rocket/issues/42")
        assertThat(tap.component?.className).isEqualTo("fr.arthurbrugiere.forgeline.MainActivity")
    }

    @Test
    fun every_channel_is_registered() {
        notifier.show(emptyList())

        assertThat(manager.notificationChannels.map { it.id }).containsAtLeastElementsIn(InboxChannel.entries.map { it.id })
    }

    @Test
    fun nothing_is_posted_when_notifications_are_blocked() {
        shadowOf(manager).setNotificationsEnabled(false)

        notifier.show(listOf(notificationThread("42")))

        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    fun a_codeberg_thread_names_its_forge_and_links_to_codeberg() {
        val codeberg = notificationThread("7", repo = "forgejo/forgejo", title = "Fix the retries", type = SubjectType.PULL_REQUEST)
            .let { it.copy(repo = it.repo.copy(forge = ForgeInstance.Codeberg), accountId = "forgejo:codeberg.org:me") }
        val github = notificationThread("7", repo = "forgejo/forgejo", title = "Same id on GitHub").copy(accountId = "github:github.com:me")

        notifier.show(listOf(codeberg, github))

        // The same thread id on two forges is two notifications.
        val posted = shadowOf(manager).allNotifications
        assertThat(posted).hasSize(2)
        val onCodeberg = posted.single { it.extras.getCharSequence("android.text").toString() == "Fix the retries" }
        assertThat(onCodeberg.extras.getCharSequence("android.subText").toString()).isEqualTo("Codeberg")
        assertThat(shadowOf(onCodeberg.contentIntent).savedIntent.dataString).isEqualTo("https://codeberg.org/forgejo/forgejo/pulls/7")
        assertThat(posted.single { it != onCodeberg }.extras.getCharSequence("android.subText")).isNull()
    }
}
