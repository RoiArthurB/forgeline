package fr.arthurbrugiere.forgeline

import android.app.Application
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import fr.arthurbrugiere.forgeline.core.data.account.AccountDataCleaner
import fr.arthurbrugiere.forgeline.notifications.InboxSyncScheduler
import fr.arthurbrugiere.forgeline.trending.TrendingMeasureScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ForgelineApplication : Application(), Configuration.Provider {
    // WorkManager starts on first use instead of at launch (its initializer is removed in the manifest).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    @Inject
    lateinit var inboxSyncScheduler: InboxSyncScheduler

    @Inject
    lateinit var trendingMeasureScheduler: TrendingMeasureScheduler

    // Lazy: it opens the database, which must not happen on the main thread at launch.
    @Inject
    lateinit var accountDataCleaner: Lazy<AccountDataCleaner>

    override fun onCreate() {
        super.onCreate()
        // Off the main thread: reading settings and accounts must never delay the first frame.
        val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        inboxSyncScheduler.start(background)
        trendingMeasureScheduler.start(background)
        // What a version that deleted nothing at sign-out left behind, or a sync that outlived a sign-out.
        background.launch { accountDataCleaner.get().sweep() }
    }
}
