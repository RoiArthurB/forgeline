package fr.arthurbrugiere.forgeline.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.InboxCheckInterval
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserSettingsRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class InboxSyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeUserSettingsRepository()
    private val accounts = FakeAccountRepository()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun work(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(InboxSyncScheduler.WORK_NAME).get()
        .filter { it.state != WorkInfo.State.CANCELLED }

    // runCurrent, not advanceUntilIdle: the latter skips work that only backgroundScope has left.
    private fun test(block: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher()) {
        InboxSyncScheduler(workManager, settings, accounts).start(backgroundScope)
        block()
    }

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")

    @Test
    fun nothing_is_scheduled_while_signed_out() = test {
        runCurrent()

        assertThat(work()).isEmpty()
    }

    @Test
    fun signed_in_the_inbox_is_checked_at_the_chosen_interval_on_network() = test {
        signIn()
        runCurrent()

        val info = work().single()
        assertThat(info.periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(1))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(androidx.work.NetworkType.CONNECTED)

        settings.setInboxCheckInterval(InboxCheckInterval.MIN_15)
        runCurrent()
        assertThat(work().single().periodicityInfo?.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
    }

    @Test
    fun turning_checks_off_or_signing_out_cancels_them() = test {
        signIn()
        runCurrent()

        settings.setInboxCheckInterval(InboxCheckInterval.OFF)
        runCurrent()
        assertThat(work()).isEmpty()

        settings.setInboxCheckInterval(InboxCheckInterval.HOUR_3)
        runCurrent()
        assertThat(work()).hasSize(1)

        accounts.signOut("github:github.com:me")
        runCurrent()
        assertThat(work()).isEmpty()
    }
}
