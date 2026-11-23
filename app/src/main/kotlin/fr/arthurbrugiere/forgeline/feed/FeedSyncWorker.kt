package fr.arthurbrugiere.forgeline.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewRepository
import fr.arthurbrugiere.forgeline.core.data.feed.FeedRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the Feed in the background, and what its first rows show (a repository's details, a release's picture and
 * notes), so the Feed opens on what is new instead of waiting for it.
 */
class FeedSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val feed: FeedRepository,
    private val previews: FeedPreviewRepository,
    private val settings: UserSettingsRepository,
) : CoroutineWorker(context, params) {

    // WorkManager's default factory uses this constructor.
    @Suppress("unused")
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        EntryPointAccessors.fromApplication<Dependencies>(context).feed(),
        EntryPointAccessors.fromApplication<Dependencies>(context).previews(),
        EntryPointAccessors.fromApplication<Dependencies>(context).settings(),
    )

    override suspend fun doWork(): Result {
        // Read only when due, and only for changes: what the Feed itself does when it opens.
        val result = feed.refresh(force = false)
        if (result is ForgeResult.Failure) return if (result.error is ForgeError.Network) Result.retry() else Result.success()
        val chosen = settings.settings.first()
        val first = feedItems(feed.observe().first().events, chosen.feedKinds, chosen.feedHideSettled).take(ROWS_AHEAD)
        val keys = first.flatMapTo(mutableSetOf()) { it.details }
        if (keys.isNotEmpty()) previews.ensure(keys)
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun feed(): FeedRepository

        fun previews(): FeedPreviewRepository

        fun settings(): UserSettingsRepository
    }

    companion object {
        /** About two screens of rows: what opening the Feed shows before scrolling far. */
        const val ROWS_AHEAD = 20
    }
}

/** Keeps the background Feed load in line with the chosen interval, while an account is signed in. */
@Singleton
class FeedSyncScheduler @Inject constructor(
    // Lazy: WorkManager must initialize on the background collector, not in Application.onCreate.
    private val workManager: Lazy<WorkManager>,
    private val settings: UserSettingsRepository,
    private val accounts: AccountRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(settings.settings, accounts.activeAccount) { settings, account -> settings.feedLoadInterval.minutes.takeIf { account != null } }
                .distinctUntilChanged()
                .collect { minutes -> if (minutes == null) cancel() else schedule(minutes) }
        }
    }

    private fun schedule(minutes: Long) {
        val request = PeriodicWorkRequestBuilder<FeedSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.get().enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancel() {
        workManager.get().cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "feed-sync"
    }
}
