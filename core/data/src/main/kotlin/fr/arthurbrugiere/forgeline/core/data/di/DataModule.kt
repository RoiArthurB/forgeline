package fr.arthurbrugiere.forgeline.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.DataStoreAccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.KeystoreTokenCipher
import fr.arthurbrugiere.forgeline.core.data.account.TokenCipher
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.data.repo.DefaultRepoRepository
import fr.arthurbrugiere.forgeline.core.data.user.DefaultUserRepository
import fr.arthurbrugiere.forgeline.core.data.user.UserRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoDao
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.data.settings.DataStoreUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.data.star.DefaultStarRepository
import fr.arthurbrugiere.forgeline.core.data.star.StarRepository
import fr.arthurbrugiere.forgeline.core.data.trending.DefaultTrendingRepository
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindUserSettingsRepository(impl: DataStoreUserSettingsRepository): UserSettingsRepository

    @Binds
    abstract fun bindAccountRepository(impl: DataStoreAccountRepository): AccountRepository

    @Binds
    abstract fun bindTokenCipher(impl: KeystoreTokenCipher): TokenCipher

    @Binds
    abstract fun bindTrendingRepository(impl: DefaultTrendingRepository): TrendingRepository

    @Binds
    abstract fun bindStarRepository(impl: DefaultStarRepository): StarRepository

    @Binds
    abstract fun bindRepoRepository(impl: DefaultRepoRepository): RepoRepository

    @Binds
    abstract fun bindIssueRepository(impl: DefaultIssueRepository): IssueRepository

    @Binds
    abstract fun bindUserRepository(impl: DefaultUserRepository): UserRepository

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): ForgelineDatabase =
            Room.databaseBuilder(context, ForgelineDatabase::class.java, "forgeline.db")
                // Everything in it is a cache of forge data: safe to rebuild.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        fun provideTrendingDao(database: ForgelineDatabase): TrendingDao = database.trendingDao()

        @Provides
        fun provideRepoDao(database: ForgelineDatabase): RepoDao = database.repoDao()

        @Provides
        fun provideClock(): Clock = Clock.systemUTC()

        @Provides
        @Singleton
        @SettingsDataStore
        fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            context.settingsDataStore

        /** Never backed up (see the app's data_extraction_rules): tokens are bound to this device's Keystore. */
        @Provides
        @Singleton
        @AccountsDataStore
        fun provideAccountsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            context.accountsDataStore
    }
}

// One instance per file per process, as DataStore requires: the delegates are process-wide,
// unlike @Singleton, which is per Hilt component (tests create one per test).
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("user_settings")
private val Context.accountsDataStore: DataStore<Preferences> by preferencesDataStore("accounts")
