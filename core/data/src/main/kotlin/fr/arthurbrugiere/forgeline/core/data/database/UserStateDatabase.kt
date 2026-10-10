package fr.arthurbrugiere.forgeline.core.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.arthurbrugiere.forgeline.core.data.inbox.BaselineDao
import fr.arthurbrugiere.forgeline.core.data.inbox.BaselineEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.DoneDao
import fr.arthurbrugiere.forgeline.core.data.inbox.DoneEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.KeptUnreadDao
import fr.arthurbrugiere.forgeline.core.data.inbox.KeptUnreadEntity
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkDao
import fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkEntity
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingMeasurementDao
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingMeasurementEntity

/**
 * What the reader did, which no forge can give back: where reading stopped, the threads marked done where the forge
 * can't, the Trending history measured on the phone. Unlike [ForgelineDatabase] it is never rebuilt from nothing:
 * every change to its schema needs a migration.
 */
@Database(
    entities = [ReadingMarkEntity::class, DoneEntity::class, TrendingMeasurementEntity::class, KeptUnreadEntity::class, BaselineEntity::class],
    version = 3,
    exportSchema = true,
    // 2: the threads kept unread, a table of their own. 3: the accounts whose Inbox has followed the forge's site.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class UserStateDatabase : RoomDatabase() {
    abstract fun readingMarkDao(): ReadingMarkDao

    abstract fun doneDao(): DoneDao

    abstract fun keptUnreadDao(): KeptUnreadDao

    abstract fun baselineDao(): BaselineDao

    abstract fun trendingMeasurementDao(): TrendingMeasurementDao
}

const val CACHE_DATABASE = "forgeline.db"
const val USER_STATE_DATABASE = "forgeline-state.db"

/** Opens the reader's state. Created for the first time, it takes over what the cache database held until 0.3.0. */
fun userStateDatabase(context: Context, configure: RoomDatabase.Builder<UserStateDatabase>.() -> Unit = {}): UserStateDatabase =
    Room.databaseBuilder(context, UserStateDatabase::class.java, USER_STATE_DATABASE)
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = carryOver(context, db)
            },
        )
        .apply(configure)
        .build()

/** The tables that moved here, with their columns: the same in both databases. */
private val MOVED = mapOf(
    "reading_marks" to "list, itemKey, position, markedAtMillis",
    "inbox_done" to "accountId, threadId, updatedAtMillis",
    "trending_measurements" to "host, state, measuredAtMillis",
)

private fun carryOver(context: Context, state: SupportSQLiteDatabase) {
    val file = context.getDatabasePath(CACHE_DATABASE)
    if (!file.exists()) return
    // Losing what was there is no worse than before: a cache database that can't be read is not worth a crash.
    try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { cache ->
            MOVED.forEach { (table, columns) ->
                try {
                    cache.rawQuery("SELECT $columns FROM $table", null).use { rows ->
                        val places = columns.split(", ").joinToString(", ") { "?" }
                        while (rows.moveToNext()) {
                            val values = Array<Any?>(rows.columnCount) { i ->
                                when (rows.getType(i)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> rows.getLong(i)
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    else -> rows.getString(i)
                                }
                            }
                            state.execSQL("INSERT OR IGNORE INTO $table ($columns) VALUES ($places)", values)
                        }
                    }
                } catch (e: SQLiteException) {
                    // The table is gone from the cache database: nothing to carry over.
                }
            }
        }
    } catch (e: SQLiteException) {
        // Not a database that opens.
    }
}
