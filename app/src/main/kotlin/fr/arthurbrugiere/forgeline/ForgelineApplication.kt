package fr.arthurbrugiere.forgeline

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import fr.arthurbrugiere.forgeline.notifications.InboxSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class ForgelineApplication : Application(), Configuration.Provider {
    // WorkManager starts on first use instead of at launch (its initializer is removed in the manifest).
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    @Inject
    lateinit var inboxSyncScheduler: InboxSyncScheduler

    override fun onCreate() {
        super.onCreate()
        // Off the main thread: reading settings and accounts must never delay the first frame.
        inboxSyncScheduler.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
