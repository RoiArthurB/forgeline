package fr.arthurbrugiere.forgeline.core.data.di

import javax.inject.Qualifier

/** The app-wide coroutine scope for work that must finish even when the screen that started it is gone. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackgroundScope
