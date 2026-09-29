package fr.arthurbrugiere.forgeline.core.data.reading

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Where reading a list stopped: the item read furthest ([itemKey]) and how far that is ([position]: a rank down
 * Trending, a time up the Feed). Only moves further; one mark per list ([list]).
 */
@Entity(tableName = "reading_marks")
data class ReadingMarkEntity(
    @PrimaryKey val list: String,
    val itemKey: String,
    val position: Long,
    val markedAtMillis: Long,
)

@Dao
interface ReadingMarkDao {
    @Query("SELECT * FROM reading_marks WHERE list = :list")
    suspend fun get(list: String): ReadingMarkEntity?

    @Upsert
    suspend fun upsert(mark: ReadingMarkEntity)
}

/** Moves [list]'s mark to [itemKey] if that is further than the mark, or the mark is older than [maxAgeMillis]. */
suspend fun ReadingMarkDao.advance(list: String, itemKey: String, position: Long, nowMillis: Long, maxAgeMillis: Long = Long.MAX_VALUE) {
    val current = get(list)?.takeIf { nowMillis - it.markedAtMillis < maxAgeMillis }
    if (current == null || position > current.position) upsert(ReadingMarkEntity(list, itemKey, position, nowMillis))
}
