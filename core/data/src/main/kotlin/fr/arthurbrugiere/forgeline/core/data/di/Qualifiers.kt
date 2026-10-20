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

/** The folder finished jobs' logs are kept in once read whole, so each is downloaded once. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class JobLogCache

/** Where files are read and written: never the main thread. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DiskIo
