package fr.arthurbrugiere.forgeline.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.notifications.InboxNotifier
import fr.arthurbrugiere.forgeline.notifications.SystemInboxNotifier

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {
    @Binds
    abstract fun bindInboxNotifier(notifier: SystemInboxNotifier): InboxNotifier

    companion object {
        @Provides
        fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
    }
}
