package fr.arthurbrugiere.forgeline.core.data.trending

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingApi
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class DefaultTrendingRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val api = FakeTrendingApi()
    private var now = Instant.parse("2026-09-26T08:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }
    private val repository = DefaultTrendingRepository(database.trendingDao(), api, clock)

    private val paperclip = trendingRepo("paperclipai/paperclip").copy(
        builtBy = listOf(ForgeUser("cryppadotta", null, "https://avatars.example/1")),
        languageColor = "#3178c6",
    )
    private val hindsight = trendingRepo("vectorize-io/hindsight", description = null)

    @After
    fun closeDatabase() = database.close()

    @Test
    fun nothing_is_cached_at_first() = runTest {
        val snapshot = repository.observe(TrendingPeriod.DAILY).first()

        assertThat(snapshot.repos).isEmpty()
        assertThat(snapshot.fetchedAtMillis).isNull()
    }

    @Test
    fun refresh_caches_the_repos_in_rank_order_with_every_field() = runTest {
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip, hindsight))

        assertThat(repository.refresh(TrendingPeriod.DAILY)).isEqualTo(RefreshResult.Refreshed)

        val snapshot = repository.observe(TrendingPeriod.DAILY).first()
        assertThat(snapshot.repos).containsExactly(paperclip, hindsight).inOrder()
        assertThat(snapshot.fetchedAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun periods_are_cached_separately() = runTest {
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        api.results[TrendingPeriod.WEEKLY] = ForgeResult.Success(listOf(hindsight))

        repository.refresh(TrendingPeriod.DAILY)
        repository.refresh(TrendingPeriod.WEEKLY)

        assertThat(repository.observe(TrendingPeriod.DAILY).first().repos).containsExactly(paperclip)
        assertThat(repository.observe(TrendingPeriod.WEEKLY).first().repos).containsExactly(hindsight)
    }

    @Test
    fun a_recent_cache_is_not_refetched_unless_forced() = runTest {
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        repository.refresh(TrendingPeriod.DAILY)
        now = now.plusSeconds(30.minutes.inWholeSeconds)

        assertThat(repository.refresh(TrendingPeriod.DAILY)).isEqualTo(RefreshResult.Fresh)
        assertThat(repository.refresh(TrendingPeriod.DAILY, force = true)).isEqualTo(RefreshResult.Refreshed)
        assertThat(api.calls).hasSize(2)
    }

    @Test
    fun a_cache_older_than_an_hour_is_refetched() = runTest {
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        repository.refresh(TrendingPeriod.DAILY)
        now = now.plusSeconds(61.minutes.inWholeSeconds)

        assertThat(repository.refresh(TrendingPeriod.DAILY)).isEqualTo(RefreshResult.Refreshed)
    }

    @Test
    fun a_new_ranking_fully_replaces_the_old_one() = runTest {
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip, hindsight))
        repository.refresh(TrendingPeriod.DAILY)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(hindsight))

        repository.refresh(TrendingPeriod.DAILY, force = true)

        assertThat(repository.observe(TrendingPeriod.DAILY).first().repos).containsExactly(hindsight)
    }

    @Test
    fun a_failed_refresh_keeps_the_cache() = runTest {
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        repository.refresh(TrendingPeriod.DAILY)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Failure(ForgeError.Network)

        assertThat(repository.refresh(TrendingPeriod.DAILY, force = true)).isEqualTo(RefreshResult.Failed(ForgeError.Network))
        assertThat(repository.observe(TrendingPeriod.DAILY).first().repos).containsExactly(paperclip)
    }

    @Test
    fun an_empty_ranking_never_wipes_the_cache() = runTest {
        // An empty page most likely means GitHub changed its markup, not that nothing trends.
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        repository.refresh(TrendingPeriod.DAILY)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(emptyList())

        val result = repository.refresh(TrendingPeriod.DAILY, force = true)

        assertThat(result).isInstanceOf(RefreshResult.Failed::class.java)
        assertThat(repository.observe(TrendingPeriod.DAILY).first().repos).containsExactly(paperclip)
    }

    @Test
    fun the_read_mark_only_moves_down_survives_a_refresh_and_expires_after_a_day() = runTest {
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isNull()

        repository.markReadThrough(TrendingPeriod.DAILY, 6)
        repository.markReadThrough(TrendingPeriod.DAILY, 3)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        repository.refresh(TrendingPeriod.DAILY, force = true)
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isEqualTo(6)
        assertThat(repository.readThrough(TrendingPeriod.WEEKLY)).isNull()

        now = now.plusSeconds(21 * 3600)
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isNull()
        repository.markReadThrough(TrendingPeriod.DAILY, 1)
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isEqualTo(1)
    }
}
