package fr.arthurbrugiere.forgeline.core.data.database

import android.content.Context
import androidx.room.Room
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

    /** The cache database as versions up to 0.3.0 left it, holding what the reader did. */
    private fun cacheDatabaseWithState() {
        val cache = Room.databaseBuilder(context, ForgelineDatabase::class.java, CACHE_DATABASE).allowMainThreadQueries().build()
        cache.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO reading_marks (list, itemKey, position, markedAtMillis) VALUES ('feed', 'e42', 1000, 2000)")
            execSQL("INSERT INTO inbox_done (accountId, threadId, updatedAtMillis) VALUES ('forgejo:codeberg.org:me', '7', 3000)")
            execSQL("INSERT INTO trending_measurements (host, state, measuredAtMillis) VALUES ('codeberg.org', '{\"days\":3}', 4000)")
        }
        cache.close()
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
    fun a_fresh_install_starts_empty() = runTest {
        val state = userStateDatabase(context) { allowMainThreadQueries() }

        assertThat(state.readingMarkDao().get("feed")).isNull()
        assertThat(state.doneDao().observe().first()).isEmpty()
        state.close()
    }

    @Test
    fun handles_partial_or_missing_tables_in_cache_database_without_failing() = runTest {
        // Create cache database with only reading_marks table (inbox_done and trending_measurements missing)
        val cache = Room.databaseBuilder(context, ForgelineDatabase::class.java, CACHE_DATABASE).allowMainThreadQueries().build()
        cache.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO reading_marks (list, itemKey, position, markedAtMillis) VALUES ('feed', 'e100', 300, 400)")
            // Intentionally drop the other table
            execSQL("DROP TABLE IF EXISTS inbox_done")
        }
        cache.close()

        val state = userStateDatabase(context) { allowMainThreadQueries() }

        // reading_marks should be carried over, and missing inbox_done shouldn't cause a crash
        assertThat(state.readingMarkDao().get("feed")?.itemKey).isEqualTo("e100")
        assertThat(state.doneDao().observe().first()).isEmpty()
        state.close()
    }
}
