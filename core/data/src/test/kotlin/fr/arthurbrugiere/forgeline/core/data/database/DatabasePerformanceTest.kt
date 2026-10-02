package fr.arthurbrugiere.forgeline.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.feed.FeedEventEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.NotificationEntity
import fr.arthurbrugiere.forgeline.core.testing.PerformanceRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Performance test suite to record execution timings and query plans on database operations.
 *
 * Verifies query execution times and checks whether SQLite uses indices or falls back to
 * unindexed table scans and temporary B-tree disk sorts.
 */
@RunWith(RobolectricTestRunner::class)
class DatabasePerformanceTest {
    private lateinit var database: ForgelineDatabase
    private val recorder = PerformanceRecorder(warmupIterations = 3, iterations = 15)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ForgelineDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun populateNotifications(count: Int = 1000) {
        val entities = (0 until count).map { i ->
            NotificationEntity(
                accountId = if (i % 2 == 0) "github:octocat" else "codeberg:tester",
                id = "n_$i",
                host = if (i % 2 == 0) "github.com" else "codeberg.org",
                owner = "forgeline",
                name = "app",
                title = "Notification number $i for testing query performance",
                type = "Issue",
                number = i,
                reason = "subscribed",
                unread = i % 3 == 0,
                updatedAtMillis = 1_700_000_000_000L + (i * 1000L),
                ownerAvatarUrl = null,
            )
        }
        database.inboxDao().insert(entities)
    }

    private suspend fun populateFeedEvents(count: Int = 1000) {
        val entities = (0 until count).map { i ->
            FeedEventEntity(
                accountId = if (i % 2 == 0) "github:octocat" else "codeberg:tester",
                id = "fe_$i",
                actorLogin = "user_$i",
                actorAvatarUrl = null,
                host = "github.com",
                owner = "octo",
                name = "repo_$i",
                createdAtMillis = 1_700_000_000_000L + (i * 1000L),
                action = "star",
                number = null,
                text = null,
                detail = null,
                flag = false,
            )
        }
        database.feedDao().insert(entities)
    }

    @Test
    fun records_inbox_query_plan_and_execution_timing() = runTest {
        populateNotifications(1000)
        val db = database.openHelper.readableDatabase

        // 1. Inspect execution plan for observeAll
        val observeAllSql = "SELECT * FROM notifications ORDER BY updatedAtMillis DESC"
        val observeAllPlan = recorder.explainQueryPlan(db, observeAllSql)
        println("EXPLAIN QUERY PLAN ($observeAllSql):\n${observeAllPlan.summary()}")
        assertThat(observeAllPlan.usesIndex).isTrue()
        assertThat(observeAllPlan.usesTempBTree).isFalse()

        // 2. Measure execution time across iterations
        val result = recorder.measureSuspend("InboxDao.observeAll(1000 rows)") {
            val list = database.inboxDao().observeAll().first()
            assertThat(list).hasSize(1000)
        }
        println(result.formatSummary())

        // 3. Inspect execution plan for account-specific query
        val accountSql = "SELECT * FROM notifications WHERE accountId = ? ORDER BY updatedAtMillis DESC"
        val accountPlan = recorder.explainQueryPlan(db, accountSql, arrayOf("github:octocat"))
        println("EXPLAIN QUERY PLAN ($accountSql):\n${accountPlan.summary()}")
        assertThat(accountPlan.usesIndex).isTrue()
        assertThat(accountPlan.usesTempBTree).isFalse()

        val accountResult = recorder.measureSuspend("InboxDao.all(accountId)") {
            val list = database.inboxDao().all("github:octocat")
            assertThat(list).hasSize(500)
        }
        println(accountResult.formatSummary())
    }

    @Test
    fun records_feed_query_plan_and_execution_timing() = runTest {
        populateFeedEvents(1000)
        val db = database.openHelper.readableDatabase

        // 1. Inspect execution plan for observeAll
        val observeAllSql = "SELECT * FROM feed_events ORDER BY createdAtMillis DESC, accountId, id DESC"
        val observeAllPlan = recorder.explainQueryPlan(db, observeAllSql)
        println("EXPLAIN QUERY PLAN ($observeAllSql):\n${observeAllPlan.summary()}")
        assertThat(observeAllPlan.usesIndex).isTrue()
        assertThat(observeAllPlan.usesTempBTree).isFalse()

        val observeResult = recorder.measureSuspend("FeedDao.observeAll(1000 rows)") {
            val list = database.feedDao().observeAll().first()
            assertThat(list).hasSize(1000)
        }
        println(observeResult.formatSummary())

        // 2. Inspect execution plan for oldest()
        val oldestSql = "SELECT MIN(createdAtMillis) FROM feed_events WHERE accountId = ?"
        val oldestPlan = recorder.explainQueryPlan(db, oldestSql, arrayOf("github:octocat"))
        println("EXPLAIN QUERY PLAN ($oldestSql):\n${oldestPlan.summary()}")
        assertThat(oldestPlan.usesIndex).isTrue()
        assertThat(oldestPlan.usesTempBTree).isFalse()

        val oldestResult = recorder.measureSuspend("FeedDao.oldest(accountId)") {
            val oldest = database.feedDao().oldest("github:octocat")
            assertThat(oldest).isNotNull()
        }
        println(oldestResult.formatSummary())
    }
}
