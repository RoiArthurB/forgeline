package fr.arthurbrugiere.forgeline.core.data.inbox

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import androidx.room.Index
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(
    tableName = "notifications",
    primaryKeys = ["accountId", "id"],
    indices = [
        Index(value = ["updatedAtMillis"], orders = [Index.Order.DESC]),
        Index(value = ["accountId", "updatedAtMillis"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class NotificationEntity(
    val accountId: String,
    val id: String,
    val host: String,
    val owner: String,
    val name: String,
    val title: String,
    val type: String,
    val number: Int?,
    val reason: String,
    val unread: Boolean,
    val updatedAtMillis: Long,
    val ownerAvatarUrl: String?,
    val lastReadAtMillis: Long? = null,
)

/**
 * Where an issue or pull request stood when last asked. Kept apart from notifications, which each sync replaces,
 * and asked again once its thread moves on or [checkedAtMillis] gets old.
 */
@Entity(
    tableName = "subject_states",
    primaryKeys = ["host", "owner", "name", "number", "isPullRequest"],
    indices = [
        Index(value = ["checkedAtMillis"]),
    ],
)
data class SubjectStateEntity(
    val host: String,
    val owner: String,
    val name: String,
    val number: Int,
    val state: String,
    /** The thread's activity time when this was asked, so newer activity asks again. */
    val threadUpdatedAtMillis: Long,
    val checkedAtMillis: Long,
    /** Part of which conversation it is where merge requests are numbered apart (GitLab); false elsewhere. */
    val isPullRequest: Boolean = false,
)

/**
 * A thread marked done, here or (as far as can be told) on the forge's site: hidden while it stays read, and shown
 * again once new activity makes it unread, which is how GitHub's done behaves. Forgejo has no done at all, and GitHub
 * lists the threads done among the read ones without saying which they are. [updatedAtMillis] is when it was done
 * and decides nothing: the forge moves a thread's date when it is marked read.
 */
@Entity(tableName = "inbox_done", primaryKeys = ["accountId", "threadId"])
data class DoneEntity(val accountId: String, val threadId: String, val updatedAtMillis: Long)

/**
 * A thread marked unread here, after it was read. Few forges can be told (GitHub can only mark one read), so the
 * Inbox remembers: the thread shows unread for as long as the forge lists it read, until it is read or done here.
 */
@Entity(tableName = "inbox_kept_unread", primaryKeys = ["accountId", "threadId"])
data class KeptUnreadEntity(val accountId: String, val threadId: String)

@Entity(tableName = "inbox_sync")
data class InboxSyncEntity(
    @PrimaryKey val accountId: String,
    val lastModified: String?,
    val pollIntervalSeconds: Int?,
    val syncedAtMillis: Long,
    /** Newest activity already surfaced as a system notification; null until the first baseline. */
    val notifiedUpToMillis: Long?,
)

@Dao
interface InboxDao {
    @Query("SELECT * FROM notifications ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    suspend fun all(accountId: String): List<NotificationEntity>

    @Query("SELECT * FROM notifications WHERE accountId = :accountId AND id = :id")
    suspend fun get(accountId: String, id: String): NotificationEntity?

    @Query("DELETE FROM notifications WHERE accountId = :accountId")
    suspend fun clear(accountId: String)

    @Query("DELETE FROM notifications WHERE accountId = :accountId AND id = :id")
    suspend fun delete(accountId: String, id: String)

    @Query("UPDATE notifications SET unread = :unread WHERE accountId = :accountId AND id = :id")
    suspend fun setUnread(accountId: String, id: String, unread: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entities: List<NotificationEntity>)

    @Transaction
    suspend fun replace(accountId: String, entities: List<NotificationEntity>) {
        clear(accountId)
        insert(entities)
    }

    @Query("SELECT * FROM subject_states")
    fun observeStates(): Flow<List<SubjectStateEntity>>

    @Query("SELECT * FROM subject_states")
    suspend fun states(): List<SubjectStateEntity>

    /** Forgets the states last asked before [before]: their conversations left the inbox long ago. */
    @Query("DELETE FROM subject_states WHERE checkedAtMillis < :before")
    suspend fun pruneStates(before: Long)

    @Query("DELETE FROM subject_states WHERE host = :host")
    suspend fun clearStates(host: String)

    @Query("DELETE FROM notifications WHERE accountId NOT IN (:accountIds)")
    suspend fun keepThreadsOf(accountIds: List<String>)

    @Query("DELETE FROM inbox_sync WHERE accountId NOT IN (:accountIds)")
    suspend fun keepSyncsOf(accountIds: List<String>)

    /** Deletes every row of accounts other than [accountIds]: those that signed out. */
    @Transaction
    suspend fun keepOnly(accountIds: List<String>) {
        keepThreadsOf(accountIds)
        keepSyncsOf(accountIds)
    }

    @Upsert
    suspend fun upsertStates(entities: List<SubjectStateEntity>)

    @Query("SELECT * FROM inbox_sync")
    fun observeSyncs(): Flow<List<InboxSyncEntity>>

    @Query("SELECT * FROM inbox_sync WHERE accountId = :accountId")
    suspend fun sync(accountId: String): InboxSyncEntity?

    @Upsert
    suspend fun upsertSync(entity: InboxSyncEntity)
}

/** Kept in the reader's own database ([fr.arthurbrugiere.forgeline.core.data.database.UserStateDatabase]), not the cache. */
@Dao
interface DoneDao {
    @Query("SELECT * FROM inbox_done")
    fun observe(): Flow<List<DoneEntity>>

    @Query("SELECT * FROM inbox_done WHERE accountId = :accountId")
    suspend fun of(accountId: String): List<DoneEntity>

    @Upsert
    suspend fun upsert(entity: DoneEntity)

    /** Forgets what was done on threads the forge no longer lists. */
    @Query("DELETE FROM inbox_done WHERE accountId = :accountId AND threadId NOT IN (:threadIds)")
    suspend fun prune(accountId: String, threadIds: List<String>)

    /** Deletes what was done by accounts other than [accountIds]: those that signed out. */
    @Query("DELETE FROM inbox_done WHERE accountId NOT IN (:accountIds)")
    suspend fun keepOnly(accountIds: List<String>)
}

/** Kept in the reader's own database, like [DoneDao]'s. */
@Dao
interface KeptUnreadDao {
    @Query("SELECT threadId FROM inbox_kept_unread WHERE accountId = :accountId")
    suspend fun of(accountId: String): List<String>

    @Upsert
    suspend fun upsert(entity: KeptUnreadEntity)

    @Query("DELETE FROM inbox_kept_unread WHERE accountId = :accountId AND threadId = :threadId")
    suspend fun delete(accountId: String, threadId: String)

    /** Forgets the threads the forge calls unread itself, or no longer lists. */
    @Query("DELETE FROM inbox_kept_unread WHERE accountId = :accountId AND threadId NOT IN (:threadIds)")
    suspend fun prune(accountId: String, threadIds: List<String>)

    /** Deletes what accounts other than [accountIds] kept unread: those that signed out. */
    @Query("DELETE FROM inbox_kept_unread WHERE accountId NOT IN (:accountIds)")
    suspend fun keepOnly(accountIds: List<String>)
}

internal fun NotificationThread.toEntity(accountId: String) = NotificationEntity(
    accountId, id, repo.forge.host, repo.owner, repo.name, title, type.name, number, reason.name, unread, updatedAt.toEpochMilli(),
    ownerAvatarUrl, lastReadAt?.toEpochMilli(),
)

internal fun NotificationEntity.toModel() = NotificationThread(
    id = id,
    repo = RepoId(owner, name, ForgeInstance.of(host)),
    title = title,
    type = SubjectType.entries.firstOrNull { it.name == type } ?: SubjectType.OTHER,
    number = number,
    reason = NotificationReason.entries.firstOrNull { it.name == reason } ?: NotificationReason.OTHER,
    unread = unread,
    updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    ownerAvatarUrl = ownerAvatarUrl,
    accountId = accountId,
    lastReadAt = lastReadAtMillis?.let(Instant::ofEpochMilli),
)
