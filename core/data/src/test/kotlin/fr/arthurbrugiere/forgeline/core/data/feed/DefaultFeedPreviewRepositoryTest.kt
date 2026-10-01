package fr.arthurbrugiere.forgeline.core.data.feed

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.RecordingDispatcher
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
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
class DefaultFeedPreviewRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repoApi = FakeRepoApi()
    private val issueApi = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-09-29T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private val computation = RecordingDispatcher()

    private fun TestScope.repository(issues: IssueApi = issueApi) =
        DefaultFeedPreviewRepository(database.feedPreviewDao(), FakeForgeClients(repos = repoApi, issues = issues), accounts, clock, backgroundScope, computation)

    private val rocket = RepoId("acme", "rocket")
    private val pull = IssueRef(rocket, 43)

    @After
    fun close() = database.close()

    @Test
    fun previews_are_read_off_the_thread_that_asks_for_them() = runTest {
        // Regression: every stored preview was parsed on the collector's thread, the main thread in the app.
        repository().observe().first()

        assertThat(computation.uses.get()).isGreaterThan(0)
    }

    @Test
    fun repo_previews_and_pull_titles_are_fetched_with_the_token_and_cached() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        repoApi.details[rocket] = repoDetails("acme/rocket", stars = 12_400)
        issueApi.issues[pull] = issueDetails(pull, "Retry the fuel pump handshake")

        repository().ensure(setOf(rocket), setOf(pull))

        val previews = repository().observe().first()
        assertThat(previews.repos[rocket]).isEqualTo(RepoPreview("About acme/rocket", "Kotlin", 12_400))
        assertThat(previews.pullTitles[pull]).isEqualTo("Retry the fuel pump handshake")
        assertThat(repoApi.tokens + issueApi.tokens).containsExactly("tok", "tok")
    }

    @Test
    fun fresh_previews_are_not_fetched_again_and_failures_wait_before_another_try() = runTest {
        repoApi.details[rocket] = repoDetails("acme/rocket")
        val repository = repository()

        repository.ensure(setOf(rocket, RepoId("gone", "repo")), emptySet())
        repository.ensure(setOf(rocket, RepoId("gone", "repo")), emptySet())
        // A new run a day later refreshes the stale preview.
        now = now.plusSeconds(25 * 3600)
        repository().ensure(setOf(rocket), emptySet())

        assertThat(repoApi.calls).containsExactly("repo:acme/rocket", "repo:gone/repo", "repo:acme/rocket")
    }

    @Test
    fun a_failed_title_is_asked_again_a_minute_later_not_never() = runTest {
        // Regression: a failed fetch was remembered as asked until the app restarted, leaving the title blank.
        val repository = repository()
        issueApi.failure = ForgeError.Network
        repository.ensure(emptySet(), setOf(pull))
        issueApi.failure = null
        issueApi.issues[pull] = issueDetails(pull, "Retry the fuel pump handshake")

        repository.ensure(emptySet(), setOf(pull))
        assertThat(repository.observe().first().pullTitles).isEmpty()

        now = now.plusSeconds(61)
        repository.ensure(emptySet(), setOf(pull))
        assertThat(repository.observe().first().pullTitles[pull]).isEqualTo("Retry the fuel pump handshake")
    }

    @Test
    fun one_preview_failing_never_loses_the_others() = runTest {
        val other = IssueRef(rocket, 44)
        val throwing = object : IssueApi by issueApi {
            override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> =
                if (ref == other) throw IllegalStateException("unexpected answer") else ForgeResult.Success("Retry the fuel pump handshake")
        }

        repository(throwing).ensure(emptySet(), setOf(pull, other))

        assertThat(repository().observe().first().pullTitles).containsExactly(pull, "Retry the fuel pump handshake")
    }

    @Test
    fun a_fetch_finishes_even_when_the_feed_is_left_meanwhile() = runTest {
        // Regression: fetches ran in the screen's scope, and leaving the Feed cut them off for good.
        issueApi.issues[pull] = issueDetails(pull, "Retry the fuel pump handshake")
        val slow = CompletableDeferred<Unit>()
        issueApi.gate = slow
        val repository = repository()

        val screen = launch { repository.ensure(emptySet(), setOf(pull)) }
        // The title is being asked when the Feed is left.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (issueApi.calls.isEmpty()) delay(10) } }
        screen.cancel()
        slow.complete(Unit)

        // Saved on the database's own thread: wait on the real clock.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (repository.observe().first().pullTitles[pull] == null) delay(10) } }
        assertThat(repository.observe().first().pullTitles[pull]).isEqualTo("Retry the fuel pump handshake")
    }

    @Test
    fun a_title_takes_one_request_not_the_whole_pull_request() = runTest {
        var titles = 0
        val counting = object : IssueApi by issueApi {
            override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = ForgeResult.Success("A title").also { titles++ }
        }

        repository(counting).ensure(emptySet(), setOf(pull))

        assertThat(titles).isEqualTo(1)
        assertThat(issueApi.calls).isEmpty()
    }
}
