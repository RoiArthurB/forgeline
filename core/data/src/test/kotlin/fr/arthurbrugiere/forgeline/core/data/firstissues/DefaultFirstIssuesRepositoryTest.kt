package fr.arthurbrugiere.forgeline.core.data.firstissues

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.data.trending.TrendingSnapshot
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FirstIssueSource
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeSearchApi
import fr.arthurbrugiere.forgeline.core.testing.FakeTrendingRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.RecordingDispatcher
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
import fr.arthurbrugiere.forgeline.core.testing.trendingRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class DefaultFirstIssuesRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val githubSearch = FakeSearchApi()
    private val codebergSearch = FakeSearchApi()
    private val githubUsers = FakeUserApi()
    private val clients = FakeForgeClients(search = githubSearch, users = githubUsers).also { it.put(ForgeInstance.Codeberg, FakeForgeClients(search = codebergSearch)) }
    private val accounts = FakeAccountRepository()
    private val trending = FakeTrendingRepository()
    private var now = Instant.parse("2026-10-28T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultFirstIssuesRepository(database.firstIssuesDao(), clients, accounts, trending, clock, RecordingDispatcher())

    @After
    fun close() = database.close()

    private fun found(repo: RepoId, number: Int, day: Int) = IssueSearchResult(
        repo,
        issueSummary(number, "Issue $number").copy(createdAt = Instant.parse("2026-10-%02dT10:00:00Z".format(day)), labels = listOf(Label("good first issue", "7057ff"))),
    )

    private val starredRepo = RepoId("me-likes", "tool")
    private val trendingRepo = RepoId("hot", "project")
    private val codebergRepo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)

    private fun trendingToday() {
        trending.snapshots.getValue(TrendingPeriod.DAILY).value = TrendingSnapshot(
            listOf(trendingRepo("hot/project"), trendingRepo("me-likes/tool"), trendingRepo("forgejo/forgejo", forge = ForgeInstance.Codeberg)),
            1L,
        )
    }

    @Test
    fun stars_and_today_s_trending_are_looked_in_and_kept_newest_first() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t-me")
        githubUsers.starred["me"] = listOf(repoSummary("me-likes/tool"))
        trendingToday()
        githubSearch.firstIssues[starredRepo] = listOf(found(starredRepo, 1, 20))
        githubSearch.firstIssues[trendingRepo] = listOf(found(trendingRepo, 2, 25))
        codebergSearch.firstIssues[codebergRepo] = listOf(found(codebergRepo, 3, 22))

        assertThat(repository.refresh()).isEqualTo(ForgeResult.Success(Unit))

        val kept = repository.observe().first()!!
        assertThat(kept.issues.map { it.repo.fullName to it.issue.number }).containsExactly("hot/project" to 2, "forgejo/forgejo" to 3, "me-likes/tool" to 1).inOrder()
        // Starred and trending both: it counts as starred.
        assertThat(kept.issues.single { it.repo == starredRepo }.source).isEqualTo(FirstIssueSource.STARRED)
        assertThat(kept.issues.single { it.repo == trendingRepo }.source).isEqualTo(FirstIssueSource.TRENDING)
        assertThat(kept.issues.first().issue.labels.single().name).isEqualTo("good first issue")
        // GitHub is asked once for all its repositories, with the account's token; Codeberg signed out.
        assertThat(githubSearch.calls.single()).startsWith("first:")
        assertThat(githubSearch.tokens).containsExactly("t-me")
        assertThat(codebergSearch.tokens).containsExactly(null)
    }

    @Test
    fun what_was_found_lately_is_not_looked_for_again_unless_forced() = runTest {
        trendingToday()
        repository.refresh()
        now = now.plusSeconds(60 * 60)

        repository.refresh()
        assertThat(githubSearch.calls).hasSize(1)

        repository.refresh(force = true)
        assertThat(githubSearch.calls).hasSize(2)
    }

    @Test
    fun one_forge_failing_keeps_the_others_and_all_failing_keeps_what_was_there() = runTest {
        trendingToday()
        githubSearch.failure = ForgeError.Network
        codebergSearch.firstIssues[codebergRepo] = listOf(found(codebergRepo, 3, 22))

        assertThat(repository.refresh()).isEqualTo(ForgeResult.Success(Unit))
        assertThat(repository.observe().first()!!.issues.map { it.repo }).containsExactly(codebergRepo)

        codebergSearch.failure = ForgeError.Network
        assertThat(repository.refresh(force = true)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.observe().first()!!.issues.map { it.repo }).containsExactly(codebergRepo)
    }

    @Test
    fun nothing_was_looked_for_before_the_first_refresh() = runTest {
        assertThat(repository.observe().first()).isNull()
    }
}
