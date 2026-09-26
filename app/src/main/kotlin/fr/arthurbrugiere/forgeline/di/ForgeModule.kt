package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubAuthApi
import fr.arthurbrugiere.forgeline.forge.github.gitHubHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ForgeModule {
    @Provides
    @Singleton
    fun provideGitHubHttpClient(): HttpClient = gitHubHttpClient(OkHttp.create())

    @Provides
    @Singleton
    fun provideForgeAuthApi(httpClient: HttpClient): ForgeAuthApi =
        GitHubAuthApi(httpClient, clientId = BuildConfig.GITHUB_CLIENT_ID)
}
