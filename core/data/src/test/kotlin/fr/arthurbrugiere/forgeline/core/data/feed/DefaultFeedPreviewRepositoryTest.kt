package fr.arthurbrugiere.forgeline.core.data.feed

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

    private fun repository() = DefaultFeedPreviewRepository(database.feedPreviewDao(), repoApi, issueApi, accounts, clock)

    private val rocket = RepoId("acme", "rocket")
    private val pull = IssueRef(rocket, 43)

    @After
    fun close() = database.close()

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
    fun fresh_previews_are_not_fetched_again_and_failures_are_not_retried_in_the_same_run() = runTest {
        repoApi.details[rocket] = repoDetails("acme/rocket")
        val repository = repository()

        repository.ensure(setOf(rocket, RepoId("gone", "repo")), emptySet())
        repository.ensure(setOf(rocket, RepoId("gone", "repo")), emptySet())
        // A new run a day later refreshes the stale preview.
        now = now.plusSeconds(25 * 3600)
        repository().ensure(setOf(rocket), emptySet())

        assertThat(repoApi.calls).containsExactly("repo:acme/rocket", "repo:gone/repo", "repo:acme/rocket")
    }
}
