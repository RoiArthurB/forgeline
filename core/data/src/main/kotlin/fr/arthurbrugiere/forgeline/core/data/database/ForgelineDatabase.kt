package fr.arthurbrugiere.forgeline.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import fr.arthurbrugiere.forgeline.core.data.feed.FeedDao
import fr.arthurbrugiere.forgeline.core.data.feed.FeedEventEntity
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewDao
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewEntity
import fr.arthurbrugiere.forgeline.core.data.feed.FeedSyncEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxDao
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxSyncEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.SubjectStateEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.NotificationEntity
import fr.arthurbrugiere.forgeline.core.data.repo.RepoCacheEntity
import fr.arthurbrugiere.forgeline.core.data.repo.RepoDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingFetchEntity
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingMarkEntity
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepoEntity

/** Local cache: the UI renders from here first, the network only refreshes it. */
@Database(
    entities = [
        TrendingRepoEntity::class, TrendingFetchEntity::class, RepoCacheEntity::class, NotificationEntity::class, InboxSyncEntity::class,
        FeedEventEntity::class, FeedSyncEntity::class, TrendingMarkEntity::class, FeedPreviewEntity::class, SubjectStateEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class ForgelineDatabase : RoomDatabase() {
    abstract fun trendingDao(): TrendingDao

    abstract fun repoDao(): RepoDao

    abstract fun inboxDao(): InboxDao

    abstract fun feedDao(): FeedDao

    abstract fun feedPreviewDao(): FeedPreviewDao
}
