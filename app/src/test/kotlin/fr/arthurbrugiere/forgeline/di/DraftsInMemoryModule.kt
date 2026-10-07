package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.arthurbrugiere.forgeline.core.data.di.DraftModule
import fr.arthurbrugiere.forgeline.core.data.draft.DraftStore
import fr.arthurbrugiere.forgeline.core.data.draft.InMemoryDraftStore
import javax.inject.Singleton

/**
 * Drafts kept for the length of one test. On disk they are one file for the whole run: a comment or an issue one test
 * left half written would be waiting in the next test's form.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DraftModule::class])
object DraftsInMemoryModule {
    @Provides
    @Singleton
    fun provideDraftStore(): DraftStore = InMemoryDraftStore()
}
