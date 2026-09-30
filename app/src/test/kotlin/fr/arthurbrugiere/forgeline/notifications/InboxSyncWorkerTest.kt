package fr.arthurbrugiere.forgeline.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.inbox.SyncResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.testing.FakeInboxRepository
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InboxSyncWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val inbox = FakeInboxRepository()
    private val shown = mutableListOf<List<NotificationThread>>()
    private val notifier = object : InboxNotifier {
        override fun show(threads: List<NotificationThread>) {
            shown += threads
        }
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<InboxSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        InboxSyncWorker(appContext, workerParameters, inbox, notifier)
                },
            )
            .build()
            .doWork()
    }

    @Test
    fun syncs_then_notifies_new_activity() {
        inbox.nextSync = SyncResult.Updated(emptyList())
        inbox.toNotify = listOf(notificationThread("42"))

        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(inbox.syncs).containsExactly(false)
        // The work ends with the process free to die: the conversations loaded ahead must be in by then.
        assertThat(inbox.waitedForFollowUps).containsExactly(true)
        assertThat(shown.single().map { it.id }).containsExactly("42")
    }

    @Test
    fun network_failures_are_retried_later() {
        inbox.nextSync = SyncResult.Failed(ForgeError.Network)

        assertThat(run()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(shown).isEmpty()
    }

    @Test
    fun other_failures_wait_for_the_next_period() {
        inbox.nextSync = SyncResult.Failed(ForgeError.Unauthorized)

        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(shown).isEmpty()
    }

    @Test
    fun signed_out_nothing_happens() {
        inbox.nextSync = SyncResult.SignedOut

        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(shown).isEmpty()
    }
}
