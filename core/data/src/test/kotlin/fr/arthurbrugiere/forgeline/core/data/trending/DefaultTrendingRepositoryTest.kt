package fr.arthurbrugiere.forgeline.core.data.trending

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
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
import fr.arthurbrugiere.forgeline.core.model.RepoId
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
    private val clients = FakeForgeClients(trending = api)
    private val accounts = FakeAccountRepository()
    private val repository = DefaultTrendingRepository(database.trendingDao(), clients, accounts, database.readingMarkDao(), clock)
    private val codebergApi = FakeTrendingApi(ForgeInstance.Codeberg).also { clients.put(ForgeInstance.Codeberg, FakeForgeClients(trending = it)) }

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
    fun the_read_mark_names_a_repo_only_moves_down_survives_a_refresh_and_expires_after_a_day() = runTest {
        val seventh = RepoId("acme", "seventh")
        val fourth = RepoId("acme", "fourth")
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isNull()

        repository.markReadThrough(TrendingPeriod.DAILY, seventh, 6)
        repository.markReadThrough(TrendingPeriod.DAILY, fourth, 3)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        repository.refresh(TrendingPeriod.DAILY, force = true)
        // A repo, not a rank: the refreshed list may have moved it anywhere.
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isEqualTo(seventh)
        assertThat(repository.readThrough(TrendingPeriod.WEEKLY)).isNull()

        now = now.plusSeconds(21 * 3600)
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isNull()
        repository.markReadThrough(TrendingPeriod.DAILY, fourth, 1)
        assertThat(repository.readThrough(TrendingPeriod.DAILY)).isEqualTo(fourth)
    }

    private suspend fun signInToCodeberg() = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "token")

    @Test
    fun only_github_without_a_codeberg_account() = runTest {
        codebergApi.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(trendingRepo("ziglang/zig", forge = ForgeInstance.Codeberg)))
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))

        repository.refresh(TrendingPeriod.DAILY)

        assertThat(codebergApi.calls).isEmpty()
        val snapshot = repository.observe(TrendingPeriod.DAILY).first()
        assertThat(snapshot.repos).containsExactly(paperclip)
        assertThat(snapshot.forges).containsExactly(ForgeInstance.GitHub)
    }

    @Test
    fun a_codeberg_account_mixes_codeberg_in_by_share_of_stars() = runTest {
        signInToCodeberg()
        val gh1 = trendingRepo("a/gh1", periodStars = 2400)
        val gh2 = trendingRepo("a/gh2", periodStars = 1200)
        val gh3 = trendingRepo("a/gh3", periodStars = 8400)
        val cb1 = trendingRepo("b/cb1", periodStars = 30, forge = ForgeInstance.Codeberg)
        val cb2 = trendingRepo("b/cb2", periodStars = 12, forge = ForgeInstance.Codeberg)
        val cb3 = trendingRepo("b/cb3", periodStars = 108, forge = ForgeInstance.Codeberg)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(gh1, gh2, gh3))
        codebergApi.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(cb1, cb2, cb3))

        assertThat(repository.refresh(TrendingPeriod.DAILY)).isEqualTo(RefreshResult.Refreshed)

        val snapshot = repository.observe(TrendingPeriod.DAILY).first()
        // Shares: gh1 0.2, gh2 0.1, gh3 0.7; cb1 0.2, cb2 0.08, cb3 0.72. GitHub wins the tie, and each forge keeps its
        // own order: cb3's big share can't pass cb2.
        assertThat(snapshot.repos).containsExactly(gh1, cb1, gh2, gh3, cb2, cb3).inOrder()
        assertThat(snapshot.forges).containsExactly(ForgeInstance.GitHub, ForgeInstance.Codeberg).inOrder()
    }

    @Test
    fun one_forge_failing_keeps_the_other_and_the_page_doesnt_fail() = runTest {
        signInToCodeberg()
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        codebergApi.results[TrendingPeriod.DAILY] = ForgeResult.Failure(ForgeError.Http(404, null))

        assertThat(repository.refresh(TrendingPeriod.DAILY)).isEqualTo(RefreshResult.Refreshed)
        assertThat(repository.observe(TrendingPeriod.DAILY).first().repos).containsExactly(paperclip)

        api.results[TrendingPeriod.DAILY] = ForgeResult.Failure(ForgeError.Network)
        assertThat(repository.refresh(TrendingPeriod.DAILY, force = true)).isInstanceOf(RefreshResult.Failed::class.java)
    }

    @Test
    fun each_forges_rows_are_kept_apart() = runTest {
        signInToCodeberg()
        val zig = trendingRepo("ziglang/zig", forge = ForgeInstance.Codeberg)
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        codebergApi.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(zig))
        repository.refresh(TrendingPeriod.DAILY)
        now = now.plusSeconds(30.minutes.inWholeSeconds)

        // A same-named repository on the other forge doesn't overwrite GitHub's row.
        codebergApi.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(trendingRepo("paperclipai/paperclip", forge = ForgeInstance.Codeberg)))
        repository.refresh(TrendingPeriod.DAILY, force = true)

        val snapshot = repository.observe(TrendingPeriod.DAILY).first()
        assertThat(snapshot.repos.map { it.id.key }).containsExactly("github.com/paperclipai/paperclip", "codeberg.org/paperclipai/paperclip")
        assertThat(snapshot.fetchedAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun signing_out_of_codeberg_takes_its_rows_off_the_page() = runTest {
        val account = signInToCodeberg()
        api.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(paperclip))
        codebergApi.results[TrendingPeriod.DAILY] = ForgeResult.Success(listOf(trendingRepo("ziglang/zig", forge = ForgeInstance.Codeberg)))
        repository.refresh(TrendingPeriod.DAILY)

        accounts.signOut(account.id)

        assertThat(repository.observe(TrendingPeriod.DAILY).first().repos).containsExactly(paperclip)
    }
}
