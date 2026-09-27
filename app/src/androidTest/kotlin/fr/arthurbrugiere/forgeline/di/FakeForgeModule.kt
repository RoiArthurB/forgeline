package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.TrendingApi
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeAuthApi
import fr.arthurbrugiere.forgeline.core.testing.FakeStarApi
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingApi
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import javax.inject.Singleton

/** App-level tests never touch the network: forge clients are fakes with a fixed ranking. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ForgeModule::class])
object FakeForgeModule {
    @Provides
    @Singleton
    fun provideTrendingApi(): TrendingApi = FakeTrendingApi().apply {
        results[TrendingPeriod.DAILY] = ForgeResult.Success(
            listOf(trendingRepo("paperclipai/paperclip", stars = 85_955, periodStars = 2_109), trendingRepo("vectorize-io/hindsight")),
        )
        results[TrendingPeriod.WEEKLY] = ForgeResult.Success(listOf(trendingRepo("NVIDIA/Model-Optimizer")))
    }

    @Provides
    @Singleton
    fun provideStarApi(): StarApi = FakeStarApi()

    @Provides
    @Singleton
    fun provideRepoApi(): RepoApi = FakeRepoApi().apply {
        val paperclip = RepoId("paperclipai", "paperclip")
        details[paperclip] = repoDetails("paperclipai/paperclip", defaultBranch = "master", stars = 85_955)
        readmes[paperclip] = Readme("README.md", "# Paperclip\n\nOpen-source orchestration for teams of AI agents.")
        issues = listOf(issueSummary(14127, "Heartbeat recovery escalates too early"))
        pulls = listOf(issueSummary(14129, "Keep install flags on retry", isPullRequest = true))
        directories[paperclip to ""] = listOf(RepoFile("package.json", "package.json", RepoFileType.FILE, 30))
        files[paperclip to "package.json"] = "{\n  \"name\": \"paperclip\"\n}\n"
    }

    @Provides
    @Singleton
    fun provideForgeAuthApi(): ForgeAuthApi = FakeForgeAuthApi(supportsDeviceFlow = false)
}
