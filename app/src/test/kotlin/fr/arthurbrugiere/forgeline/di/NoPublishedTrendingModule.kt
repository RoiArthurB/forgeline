package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn

/** No published Trending list in app-level tests: they never touch the network, and show GitHub's fake ranking alone. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PublishedTrendingModule::class])
object NoPublishedTrendingModule {
    @Provides
    @CodebergTrendingUrl
    fun provideCodebergTrendingUrl(): String = ""

    @Provides
    @GitLabTrendingUrl
    fun provideGitLabTrendingUrl(): String = ""
}
