package fr.arthurbrugiere.forgeline.core.data.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DraftsDataStore

/** Where lists are built from what the database holds (mapping, sorting, merging): never the main thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Computation
