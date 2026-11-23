package fr.arthurbrugiere.forgeline.feed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FeedSyncWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val feed = FakeFeedRepository()
    private val previews = FakeFeedPreviewRepository()
    private val settings = FakeUserSettingsRepository()
    private val accounts = FakeAccountRepository()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<FeedSyncWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        FeedSyncWorker(appContext, workerParameters, feed, previews, settings)
                },
            )
            .build()
            .doWork()
    }

    @Test
    fun loads_the_feed_when_due_and_what_its_first_rows_show() {
        feed.set(
            feedEvent("2", repo = "acme/rocket", action = FeedAction.Released("v2.0.0", null, prerelease = false)),
            feedEvent("1", repo = "octo/tools", createdAt = "2026-09-27T08:00:00Z"),
        )

        assertThat(run()).isEqualTo(ListenableWorker.Result.success())

        // Not forced: only what is due is asked, and only for changes.
        assertThat(feed.refreshes).containsExactly(false)
        assertThat(previews.requested).containsExactly(
            FeedDetailKey.Release(ReleaseRef(RepoId("acme", "rocket"), "v2.0.0")),
            FeedDetailKey.Repo(RepoId("octo", "tools")),
        )
    }

    @Test
    fun only_so_many_rows_are_filled_ahead() {
        feed.set(*Array(FeedSyncWorker.ROWS_AHEAD + 5) { feedEvent("$it", repo = "owner/repo$it", createdAt = "2026-09-27T09:%02d:00Z".format(59 - it)) })

        run()

        assertThat(previews.requested).hasSize(FeedSyncWorker.ROWS_AHEAD)
    }

    @Test
    fun a_network_failure_is_tried_again_and_others_wait_for_the_next_time() {
        feed.failure = ForgeError.Network
        assertThat(run()).isEqualTo(ListenableWorker.Result.retry())

        feed.failure = ForgeError.Unauthorized
        assertThat(run()).isEqualTo(ListenableWorker.Result.success())
        assertThat(previews.requested).isEmpty()
    }

    private fun work(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(FeedSyncScheduler.WORK_NAME).get().filter { it.state != WorkInfo.State.CANCELLED }

    @Test
    fun signed_in_the_feed_is_loaded_at_the_chosen_interval_until_turned_off_or_signed_out() = runTest(StandardTestDispatcher()) {
        FeedSyncScheduler({ workManager }, settings, accounts).start(backgroundScope)
        runCurrent()
        assertThat(work()).isEmpty()

        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")
        runCurrent()
        val info = work().single()
        assertThat(info.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(1))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(androidx.work.NetworkType.CONNECTED)

        settings.update { it.copy(feedLoadInterval = InboxCheckInterval.MIN_30) }
        runCurrent()
        assertThat(work().single().periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(30))

        settings.update { it.copy(feedLoadInterval = InboxCheckInterval.OFF) }
        runCurrent()
        assertThat(work()).isEmpty()

        settings.update { it.copy(feedLoadInterval = InboxCheckInterval.HOUR_3) }
        runCurrent()
        accounts.signOut("github:github.com:me")
        runCurrent()
        assertThat(work()).isEmpty()
    }
}
