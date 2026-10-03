package fr.arthurbrugiere.forgeline.core.data.database

import fr.arthurbrugiere.forgeline.core.data.trending.TrendingMeasurementEntity
import androidx.room.Database
import androidx.room.RoomDatabase
import fr.arthurbrugiere.forgeline.core.data.feed.FeedDao
import fr.arthurbrugiere.forgeline.core.data.feed.FeedEventEntity
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewDao
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewEntity
import fr.arthurbrugiere.forgeline.core.data.feed.FeedSyncEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxDao
import fr.arthurbrugiere.forgeline.core.data.inbox.DoneEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxSyncEntity
import fr.arthurbrugiere.forgeline.core.data.issue.ConversationDao
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkEntity
import fr.arthurbrugiere.forgeline.core.data.issue.ConversationEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.SubjectStateEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.NotificationEntity
import fr.arthurbrugiere.forgeline.core.data.repo.RepoCacheEntity
import fr.arthurbrugiere.forgeline.core.data.repo.RepoDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingFetchEntity
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingRepoEntity

/**
 * Local cache: the UI renders from here first, the network only refreshes it. Rebuilt from nothing at every schema
 * change, so nothing the reader did belongs here: that is [UserStateDatabase].
 *
 * ReadingMarkEntity, DoneEntity and TrendingMeasurementEntity moved there. Their tables stay declared here, unused,
 * so that this version doesn't rebuild the cache before [UserStateDatabase] has taken them over: drop them from the
 * list at the next schema change.
 */
@Database(
    entities = [
        TrendingRepoEntity::class, TrendingFetchEntity::class, TrendingMeasurementEntity::class, RepoCacheEntity::class, NotificationEntity::class, InboxSyncEntity::class,
        FeedEventEntity::class, FeedSyncEntity::class, ReadingMarkEntity::class, FeedPreviewEntity::class, SubjectStateEntity::class,
        ConversationEntity::class,
        DoneEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class ForgelineDatabase : RoomDatabase() {
    abstract fun trendingDao(): TrendingDao

    abstract fun repoDao(): RepoDao

    abstract fun inboxDao(): InboxDao

    abstract fun feedDao(): FeedDao

    abstract fun feedPreviewDao(): FeedPreviewDao

    abstract fun conversationDao(): ConversationDao
}
