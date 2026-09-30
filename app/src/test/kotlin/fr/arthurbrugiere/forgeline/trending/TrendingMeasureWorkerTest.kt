package fr.arthurbrugiere.forgeline.trending

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
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
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
class TrendingMeasureWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val trending = FakeTrendingRepository()
    private val settings = FakeUserSettingsRepository()
    private val accounts = FakeAccountRepository()
    private val home = ForgeInstance(ForgeType.FORGEJO, "git.example.org")
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<TrendingMeasureWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        TrendingMeasureWorker(appContext, workerParameters, trending, settings, accounts)
                },
            )
            .build()
            .doWork()
    }

    @Test
    fun measures_the_forges_turned_on_that_an_account_is_signed_in_to() = runBlocking<Unit> {
        accounts.signIn(home, ForgeUser("me", null, null), "t")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "c")
        settings.setTrendingMeasured(home.host, true)
        // Turned on once, but signed out since: not measured.
        settings.setTrendingMeasured("gone.example.org", true)

        assertThat(run()).isEqualTo(ListenableWorker.Result.success())

        assertThat(trending.measured).containsExactly(home)
    }

    @Test
    fun a_server_out_of_reach_is_tried_again_later() = runBlocking<Unit> {
        accounts.signIn(home, ForgeUser("me", null, null), "t")
        settings.setTrendingMeasured(home.host, true)
        trending.measureResult = RefreshResult.Failed(ForgeError.Network)

        assertThat(run()).isEqualTo(ListenableWorker.Result.retry())
    }

    private fun work(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get().filter { it.state != WorkInfo.State.CANCELLED }

    @Test
    fun measuring_is_scheduled_daily_while_a_forge_is_on_and_starts_at_once_when_one_is_turned_on() = runTest(StandardTestDispatcher()) {
        TrendingMeasureScheduler({ workManager }, settings).start(backgroundScope)
        runCurrent()
        assertThat(work(TrendingMeasureScheduler.DAILY)).isEmpty()

        settings.setTrendingMeasured(home.host, true)
        runCurrent()

        val daily = work(TrendingMeasureScheduler.DAILY).single()
        assertThat(daily.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.DAYS.toMillis(1))
        assertThat(daily.constraints.requiredNetworkType).isEqualTo(androidx.work.NetworkType.CONNECTED)
        assertThat(workManager.getWorkInfosForUniqueWork(TrendingMeasureScheduler.NOW).get()).isNotEmpty()

        settings.setTrendingMeasured(home.host, false)
        runCurrent()
        assertThat(work(TrendingMeasureScheduler.DAILY)).isEmpty()
    }
}
