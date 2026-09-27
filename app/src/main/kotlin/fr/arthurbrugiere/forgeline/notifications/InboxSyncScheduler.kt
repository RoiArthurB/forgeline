package fr.arthurbrugiere.forgeline.notifications

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.Lazy
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the periodic Inbox check in line with the chosen interval and the signed-in account. */
@Singleton
class InboxSyncScheduler @Inject constructor(
    // Lazy: WorkManager (and its database) must initialize on the background collector, not
    // during Application.onCreate, where the scheduler is injected on the main thread.
    private val workManager: Lazy<WorkManager>,
    private val settings: UserSettingsRepository,
    private val accounts: AccountRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(settings.settings, accounts.activeAccount) { settings, account ->
                settings.inboxCheckInterval.minutes.takeIf { account != null }
            }
                .distinctUntilChanged()
                .collect { minutes -> if (minutes == null) cancel() else schedule(minutes) }
        }
    }

    private fun schedule(minutes: Long) {
        val request = PeriodicWorkRequestBuilder<InboxSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.get().enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancel() {
        workManager.get().cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "inbox-sync"
    }
}
