package fr.arthurbrugiere.forgeline.trending

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Measures the Trending of each forge the settings measure on the phone, one day more of history each run. */
class TrendingMeasureWorker(
    context: Context,
    params: WorkerParameters,
    private val trending: TrendingRepository,
    private val settings: UserSettingsRepository,
    private val accounts: AccountRepository,
) : CoroutineWorker(context, params) {

    // WorkManager's default factory uses this constructor.
    @Suppress("unused")
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        EntryPointAccessors.fromApplication<Dependencies>(context).trending(),
        EntryPointAccessors.fromApplication<Dependencies>(context).settings(),
        EntryPointAccessors.fromApplication<Dependencies>(context).accounts(),
    )

    override suspend fun doWork(): Result {
        val hosts = settings.settings.first().measuredTrending
        // Only forges an account is signed in to: signing out stops measuring there.
        val forges = accounts.accounts.first().map { it.forge }.distinct().filter { it.host in hosts }
        val results = forges.map { trending.measure(it) }
        // A server out of reach is tried again later today; any other failure waits for tomorrow.
        return if (results.any { it is RefreshResult.Failed && it.error == ForgeError.Network }) Result.retry() else Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun trending(): TrendingRepository

        fun settings(): UserSettingsRepository

        fun accounts(): AccountRepository
    }
}

/**
 * Keeps the daily measurement scheduled while any forge is measured on the phone, and measures a forge at once when
 * it's turned on, so its history starts today.
 */
@Singleton
class TrendingMeasureScheduler @Inject constructor(
    // Lazy: WorkManager must initialize on the background collector, not in Application.onCreate.
    private val workManager: Lazy<WorkManager>,
    private val settings: UserSettingsRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var previous: Set<String>? = null
            settings.settings.map { it.measuredTrending }.distinctUntilChanged().collect { hosts ->
                if (hosts.isEmpty()) {
                    workManager.get().cancelUniqueWork(DAILY)
                } else {
                    workManager.get().enqueueUniquePeriodicWork(
                        DAILY,
                        ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<TrendingMeasureWorker>(1, TimeUnit.DAYS).setConstraints(network).build(),
                    )
                    // A forge just turned on (not one already on at launch) is measured now.
                    if (previous != null && (hosts - previous.orEmpty()).isNotEmpty()) {
                        workManager.get().enqueueUniqueWork(
                            NOW,
                            ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequestBuilder<TrendingMeasureWorker>().setConstraints(network).build(),
                        )
                    }
                }
                previous = hosts
            }
        }
    }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        const val DAILY = "trending-measure"
        const val NOW = "trending-measure-now"
    }
}
