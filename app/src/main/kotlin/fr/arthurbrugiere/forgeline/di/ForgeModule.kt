package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.BuildConfig
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubRepoSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubActionsApi
import fr.arthurbrugiere.forgeline.forge.github.GitHubPullRequestApi
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
import okhttp3.Cache
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ForgeModule {
    @Provides
    @Singleton
    @GitHub
    fun provideGitHubHttpClient(cache: Cache): HttpClient = gitHubHttpClient(forgeEngine(cache))

    @Provides
    @Singleton
    fun provideSearchApi(@GitHub httpClient: HttpClient): SearchApi = GitHubSearchApi(httpClient)

    @Provides
    @Singleton
    fun provideFeedApi(@GitHub httpClient: HttpClient): FeedApi = GitHubFeedApi(httpClient)

    @Provides
    @Singleton
    fun provideForgeAuthApi(@GitHub httpClient: HttpClient): ForgeAuthApi =
        GitHubAuthApi(httpClient, clientId = BuildConfig.GITHUB_CLIENT_ID)

    @Provides
    @Singleton
    fun provideTrendingApi(@GitHub httpClient: HttpClient): TrendingApi = GitHubTrendingApi(httpClient)

    @Provides
    @Singleton
    fun provideStarApi(@GitHub httpClient: HttpClient): StarApi = GitHubStarApi(httpClient)

    @Provides
    @Singleton
    fun provideRepoApi(@GitHub httpClient: HttpClient): RepoApi = GitHubRepoApi(httpClient)

    @Provides
    @Singleton
    fun provideActionsApi(@GitHub httpClient: HttpClient): ActionsApi = GitHubActionsApi(httpClient)

    @Provides
    @Singleton
    fun provideRepoSettingsApi(@GitHub httpClient: HttpClient): RepoSettingsApi = GitHubRepoSettingsApi(httpClient)

    @Provides
    @Singleton
    fun providePullRequestApi(@GitHub httpClient: HttpClient): PullRequestApi = GitHubPullRequestApi(httpClient)

    @Provides
    @Singleton
    fun provideIssueApi(@GitHub httpClient: HttpClient): IssueApi = GitHubIssueApi(httpClient)

    @Provides
    @Singleton
    fun provideUserApi(@GitHub httpClient: HttpClient): UserApi = GitHubUserApi(httpClient)

    @Provides
    @Singleton
    fun provideNotificationsApi(@GitHub httpClient: HttpClient): NotificationsApi = GitHubNotificationsApi(httpClient)
}
