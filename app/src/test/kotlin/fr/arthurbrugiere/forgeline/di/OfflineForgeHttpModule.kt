package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.arthurbrugiere.forgeline.forge.forgejo.forgejoHttpClient
import fr.arthurbrugiere.forgeline.forge.gitlab.gitlabHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import javax.inject.Singleton

/**
 * App-level tests never reach Codeberg, gitlab.com or any other server outside GitHub's fakes: a search signed out
 * asks those too, and what they answered that day would decide what a test sees. Every one of them is down here.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ForgeHttpModule::class])
object OfflineForgeHttpModule {
    @Provides
    @Singleton
    @Forgejo
    fun provideForgejoHttpClient(): HttpClient = forgejoHttpClient(down())

    @Provides
    @Singleton
    @GitLab
    fun provideGitLabHttpClient(): HttpClient = gitlabHttpClient(down())

    private fun down() = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
}
