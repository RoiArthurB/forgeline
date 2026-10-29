package fr.arthurbrugiere.forgeline.core.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression: reading marks, threads marked done and the Trending history measured on the phone lived in the cache
 * database, which is rebuilt from nothing at every schema change. They now have a database of their own.
 */
@RunWith(RobolectricTestRunner::class)
class UserStateDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * The cache database as versions up to 0.3.0 left it, holding what the reader did: those tables, written as they
     * were then (the cache database no longer declares them).
     */
    private fun oldCacheDatabase(vararg tables: String = arrayOf("reading_marks", "inbox_done", "trending_measurements")): android.database.sqlite.SQLiteDatabase =
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(CACHE_DATABASE).apply { parentFile!!.mkdirs() }, null).apply {
            tables.forEach { execSQL(OLD_TABLES.getValue(it)) }
        }

    private fun cacheDatabaseWithState() {
        oldCacheDatabase().use { cache ->
            cache.apply {
                execSQL("INSERT INTO reading_marks (list, itemKey, position, markedAtMillis) VALUES ('feed', 'e42', 1000, 2000)")
                execSQL("INSERT INTO inbox_done (accountId, threadId, updatedAtMillis) VALUES ('forgejo:codeberg.org:me', '7', 3000)")
                execSQL("INSERT INTO trending_measurements (host, state, measuredAtMillis) VALUES ('codeberg.org', '{\"days\":3}', 4000)")
            }
        }
    }

    @Test
    fun what_the_reader_did_is_carried_over_from_the_cache_database() = runTest {
        cacheDatabaseWithState()

        val state = userStateDatabase(context) { allowMainThreadQueries() }

        assertThat(state.readingMarkDao().get("feed")?.itemKey).isEqualTo("e42")
        assertThat(state.doneDao().observe().first().single().threadId).isEqualTo("7")
        assertThat(state.trendingMeasurementDao().measurement("codeberg.org")?.state).isEqualTo("{\"days\":3}")
        state.close()
    }

    @Test
    fun it_is_carried_over_once_not_again_over_newer_state() = runTest {
        cacheDatabaseWithState()
        userStateDatabase(context) { allowMainThreadQueries() }.apply {
            readingMarkDao().upsert(fr.arthurbrugiere.forgeline.core.data.reading.ReadingMarkEntity("feed", "e99", 5000, 6000))
            close()
        }

        val reopened = userStateDatabase(context) { allowMainThreadQueries() }

        assertThat(reopened.readingMarkDao().get("feed")?.itemKey).isEqualTo("e99")
        reopened.close()
    }

    @Test
    fun the_first_version_is_brought_up_to_date_with_what_it_held() = runTest {
        // This database is never rebuilt: a version that can't be migrated to would lose what the reader did.
        val schema = org.json.JSONObject(java.io.File(SCHEMAS, "1.json").readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(USER_STATE_DATABASE).apply { parentFile!!.mkdirs() }, null).use { old ->
            val tables = schema.getJSONArray("entities")
            for (i in 0 until tables.length()) {
                val table = tables.getJSONObject(i)
                old.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", table.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO inbox_done (accountId, threadId, updatedAtMillis) VALUES ('github:github.com:me', '7', 3000)")
            old.version = 1
        }

        val state = userStateDatabase(context) { allowMainThreadQueries() }

        assertThat(state.doneDao().observe().first().single().threadId).isEqualTo("7")
        state.keptUnreadDao().upsert(fr.arthurbrugiere.forgeline.core.data.inbox.KeptUnreadEntity("github:github.com:me", "8"))
        assertThat(state.keptUnreadDao().of("github:github.com:me")).containsExactly("8")
        // And the table the version after added.
        assertThat(state.baselineDao().count("github:github.com:me")).isEqualTo(0)
        // And the collections, which came at version 4.
        assertThat(state.collectionDao().observeAll().first()).isEmpty()
        state.collectionDao().upsert(fr.arthurbrugiere.forgeline.core.data.collections.CollectionEntity("c1", "Tools", 1))
        assertThat(state.collectionDao().observeAll().first().single().name).isEqualTo("Tools")
        // And the Inbox actions not yet sent, at version 5.
        state.heldActionDao().upsert(listOf(fr.arthurbrugiere.forgeline.core.data.inbox.HeldActionEntity("github:github.com:me", "9", "DONE", 1)))
        assertThat(state.heldActionDao().all().single().threadId).isEqualTo("9")
        state.close()
    }

    @Test
    fun a_fresh_install_starts_empty() = runTest {
        val state = userStateDatabase(context) { allowMainThreadQueries() }

        assertThat(state.readingMarkDao().get("feed")).isNull()
        assertThat(state.doneDao().observe().first()).isEmpty()
        state.close()
    }

    @Test
    fun handles_partial_or_missing_tables_in_cache_database_without_failing() = runTest {
        // A cache database holding reading_marks alone: inbox_done and trending_measurements are missing.
        oldCacheDatabase("reading_marks").use { cache ->
            cache.execSQL("INSERT INTO reading_marks (list, itemKey, position, markedAtMillis) VALUES ('feed', 'e100', 300, 400)")
        }

        val state = userStateDatabase(context) { allowMainThreadQueries() }

        // reading_marks should be carried over, and missing inbox_done shouldn't cause a crash
        assertThat(state.readingMarkDao().get("feed")?.itemKey).isEqualTo("e100")
        assertThat(state.doneDao().observe().first()).isEmpty()
        state.close()
    }

    private companion object {
        /** Room's exported schemas, one file per version, next to the module's sources. */
        const val SCHEMAS = "schemas/fr.arthurbrugiere.forgeline.core.data.database.UserStateDatabase"

        /** The three tables as the cache database held them until 0.3.0 (its schema 21, the last to declare them). */
        val OLD_TABLES = mapOf(
            "reading_marks" to "CREATE TABLE IF NOT EXISTS `reading_marks` (`list` TEXT NOT NULL, `itemKey` TEXT NOT NULL, `position` INTEGER NOT NULL, `markedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`list`))",
            "inbox_done" to "CREATE TABLE IF NOT EXISTS `inbox_done` (`accountId` TEXT NOT NULL, `threadId` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `threadId`))",
            "trending_measurements" to "CREATE TABLE IF NOT EXISTS `trending_measurements` (`host` TEXT NOT NULL, `state` TEXT NOT NULL, `measuredAtMillis` INTEGER NOT NULL, PRIMARY KEY(`host`))",
        )
    }
}
