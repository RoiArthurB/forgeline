package fr.arthurbrugiere.forgeline.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import fr.arthurbrugiere.forgeline.core.forge.ForgeAuthApi
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
    fun provideForgeAuthApi(): ForgeAuthApi = FakeForgeAuthApi(supportsDeviceFlow = false)
}
