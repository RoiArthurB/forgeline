package fr.arthurbrugiere.forgeline.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import fr.arthurbrugiere.forgeline.core.data.repo.RepoCacheEntity
import fr.arthurbrugiere.forgeline.core.data.repo.RepoDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingFetchEntity
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepoEntity

/** Local cache: the UI renders from here first, the network only refreshes it. */
@Database(
    entities = [TrendingRepoEntity::class, TrendingFetchEntity::class, RepoCacheEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ForgelineDatabase : RoomDatabase() {
    abstract fun trendingDao(): TrendingDao

    abstract fun repoDao(): RepoDao
}
