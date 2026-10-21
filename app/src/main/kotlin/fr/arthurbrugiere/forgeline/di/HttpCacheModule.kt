package fr.arthurbrugiere.forgeline.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.arthurbrugiere.forgeline.core.data.account.ResponseCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import java.io.File
import javax.inject.Singleton

/** The forges' answers kept on the phone, one cache for every forge's client: a folder can hold only one. */
@Module
@InstallIn(SingletonComponent::class)
object HttpCacheModule {
    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext context: Context): Cache = Cache(File(context.cacheDir, "forge-http"), HTTP_CACHE_BYTES)

    @Provides
    fun provideResponseCache(cache: Cache): ResponseCache = ResponseCache { withContext(Dispatchers.IO) { cache.evictAll() } }
}
