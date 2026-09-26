package fr.arthurbrugiere.forgeline.core.data.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountsDataStore
