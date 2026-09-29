package fr.arthurbrugiere.forgeline.core.data.inbox

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(tableName = "notifications", primaryKeys = ["accountId", "id"])
data class NotificationEntity(
    val accountId: String,
    val id: String,
    val owner: String,
    val name: String,
    val title: String,
    val type: String,
    val number: Int?,
    val reason: String,
    val unread: Boolean,
    val updatedAtMillis: Long,
    val ownerAvatarUrl: String?,
)

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
    @Query("SELECT * FROM notifications WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    fun observe(accountId: String): Flow<List<NotificationEntity>>

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

    @Query("SELECT * FROM inbox_sync WHERE accountId = :accountId")
    fun observeSync(accountId: String): Flow<InboxSyncEntity?>

    @Query("SELECT * FROM inbox_sync WHERE accountId = :accountId")
    suspend fun sync(accountId: String): InboxSyncEntity?

    @Upsert
    suspend fun upsertSync(entity: InboxSyncEntity)
}

internal fun NotificationThread.toEntity(accountId: String) = NotificationEntity(
    accountId, id, repo.owner, repo.name, title, type.name, number, reason.name, unread, updatedAt.toEpochMilli(),
    ownerAvatarUrl,
)

internal fun NotificationEntity.toModel() = NotificationThread(
    id = id,
    repo = RepoId(owner, name),
    title = title,
    type = SubjectType.entries.firstOrNull { it.name == type } ?: SubjectType.OTHER,
    number = number,
    reason = NotificationReason.entries.firstOrNull { it.name == reason } ?: NotificationReason.OTHER,
    unread = unread,
    updatedAt = Instant.ofEpochMilli(updatedAtMillis),
    ownerAvatarUrl = ownerAvatarUrl,
)
