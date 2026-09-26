package fr.arthurbrugiere.forgeline.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
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
import fr.arthurbrugiere.forgeline.core.data.settings.DataStoreUserSettingsRepository
import fr.arthurbrugiere.forgeline.core.data.settings.UserSettingsRepository
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

    companion object {
        @Provides
        @Singleton
        @SettingsDataStore
        fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("user_settings") }

        /** Never backed up (see the app's data_extraction_rules): tokens are bound to this device's Keystore. */
        @Provides
        @Singleton
        @AccountsDataStore
        fun provideAccountsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("accounts") }
    }
}
