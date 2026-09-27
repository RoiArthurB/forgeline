package fr.arthurbrugiere.forgeline.notifications

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
    private val workManager: WorkManager,
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
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "inbox-sync"
    }
}
