package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubAuthApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubFeedApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubIssueApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubNotificationsApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubUserApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubRepoApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubSearchApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubStarApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubTrendingApi
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
    fun provideSearchApi(httpClient: HttpClient): SearchApi = GitHubSearchApi(httpClient)

    @Provides
    @Singleton
    fun provideFeedApi(httpClient: HttpClient): FeedApi = GitHubFeedApi(httpClient)

    @Provides
    @Singleton
    fun provideForgeAuthApi(httpClient: HttpClient): ForgeAuthApi =
        GitHubAuthApi(httpClient, clientId = BuildConfig.GITHUB_CLIENT_ID)

    @Provides
    @Singleton
    fun provideTrendingApi(httpClient: HttpClient): TrendingApi = GitHubTrendingApi(httpClient)

    @Provides
    @Singleton
    fun provideStarApi(httpClient: HttpClient): StarApi = GitHubStarApi(httpClient)

    @Provides
    @Singleton
    fun provideRepoApi(httpClient: HttpClient): RepoApi = GitHubRepoApi(httpClient)

    @Provides
    @Singleton
    fun provideIssueApi(httpClient: HttpClient): IssueApi = GitHubIssueApi(httpClient)

    @Provides
    @Singleton
    fun provideUserApi(httpClient: HttpClient): UserApi = GitHubUserApi(httpClient)

    @Provides
    @Singleton
    fun provideNotificationsApi(httpClient: HttpClient): NotificationsApi = GitHubNotificationsApi(httpClient)
}
