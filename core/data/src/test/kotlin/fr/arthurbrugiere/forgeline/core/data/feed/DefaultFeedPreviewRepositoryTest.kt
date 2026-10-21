package fr.arthurbrugiere.forgeline.core.data.feed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.forge.FeedDetail
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedApi
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoApi
import fr.arthurbrugiere.forgeline.core.testing.RecordingDispatcher
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.testing.repoDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    private val feedApi = FakeFeedApi()

    private fun TestScope.repository(issues: IssueApi = issueApi) =
        DefaultFeedPreviewRepository(database.feedPreviewDao(), FakeForgeClients(repos = repoApi, issues = issues, feed = feedApi), accounts, clock, backgroundScope, computation)

    private val rocket = RepoId("acme", "rocket")
    private val pull = IssueRef(rocket, 43)

    private fun repoKey(id: RepoId) = FeedDetailKey.Repo(id)
    private fun talk(ref: IssueRef) = FeedDetailKey.Conversation(ref)
    private fun releaseKey(tag: String, repo: RepoId = rocket) = FeedDetailKey.Release(ReleaseRef(repo, tag))

    private fun release(tag: String, body: String? = null, prerelease: Boolean = false, reactions: Map<Reaction, Int> = emptyMap()) =
        Release(tag, null, body, Instant.parse("2026-09-28T10:00:00Z"), prerelease, null, reactions = reactions)

    @After
    fun close() = database.close()

    @Test
    fun previews_are_read_off_the_thread_that_asks_for_them() = runTest {
        // Regression: every stored preview was parsed on the collector's thread, the main thread in the app.
        repository().observe().first()

        assertThat(computation.uses.get()).isGreaterThan(0)
    }

    @Test
    fun repo_previews_and_conversations_are_fetched_with_the_token_and_cached() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        repoApi.details[rocket] = repoDetails("acme/rocket", stars = 12_400).copy(pushedAt = Instant.parse("2026-09-29T08:00:00Z"))
        issueApi.issues[pull] = issueDetails(pull, "Retry the fuel pump handshake").copy(labels = listOf(Label("bug", "d73a4a")), comments = 6)

        repository().ensure(setOf(repoKey(rocket), talk(pull)))

        val previews = repository().observe().first()
        assertThat(previews.repos[rocket]).isEqualTo(RepoPreview("About acme/rocket", "Kotlin", 12_400, Instant.parse("2026-09-29T08:00:00Z")))
        assertThat(previews.conversations[pull]).isEqualTo(ConversationPreview("Retry the fuel pump handshake", listOf(Label("bug", "d73a4a")), 6))
        assertThat(repoApi.tokens + issueApi.tokens).containsExactly("tok", "tok")
    }

    @Test
    fun a_forge_that_answers_rows_together_is_asked_once_for_all_of_them() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        val shipped = ReleasePreview("Faster everything.", "v1.9.0", "https://example.org/card.png", "https://example.org/logo.png", mapOf(Reaction.HOORAY to 12))
        feedApi.details = mapOf(
            repoKey(rocket) to FeedDetail.Repo(RepoPreview("Rockets", "Rust", 9)),
            releaseKey("v2.0.0") to FeedDetail.Release(shipped),
            talk(pull) to FeedDetail.Conversation(ConversationPreview("Retry", additions = 212, deletions = 40, changedFiles = 7)),
        )

        repository().ensure(setOf(repoKey(rocket), releaseKey("v2.0.0"), talk(pull)))

        assertThat(feedApi.detailCalls).hasSize(1)
        assertThat(repoApi.calls + issueApi.calls).isEmpty()
        val previews = repository().observe().first()
        assertThat(previews.releases[ReleaseRef(rocket, "v2.0.0")]).isEqualTo(shipped)
        assertThat(previews.conversations[pull]?.additions).isEqualTo(212)
        assertThat(previews.repos[rocket]?.language).isEqualTo("Rust")
    }

    @Test
    fun a_release_is_summed_up_from_its_notes_where_the_forge_answers_one_row_at_a_time() = runTest {
        repoApi.details[rocket] = repoDetails("acme/rocket").copy(ownerAvatarUrl = "https://codeberg.org/avatars/acme")
        repoApi.releases = listOf(
            release("v2.0.0", "Faster everything, everywhere, all at once.\n\n![The new map](https://codeberg.org/acme/rocket/map.png)", reactions = mapOf(Reaction.HEART to 3, Reaction.EYES to 0)),
            release("v2.0.0-rc.1", prerelease = true),
            release("v1.9.0"),
        )

        repository().ensure(setOf(releaseKey("v2.0.0")))

        assertThat(repository().observe().first().releases[ReleaseRef(rocket, "v2.0.0")]).isEqualTo(
            ReleasePreview(
                summary = "Faster everything, everywhere, all at once.",
                // The release candidate in between says nothing of the step: the last stable release does.
                previousTag = "v1.9.0",
                imageUrl = "https://codeberg.org/acme/rocket/map.png",
                logoUrl = "https://codeberg.org/avatars/acme",
                reactions = mapOf(Reaction.HEART to 3),
            ),
        )
    }

    @Test
    fun a_size_the_forge_did_not_count_is_unknown_not_zero() = runTest {
        // GitLab gives a merge request without counting its lines: "+0 −0" would be a false claim.
        issueApi.issues[pull] = issueDetails(pull, "Retry").copy(pullRequest = PullRequestInfo(false, true, "main", "retry", 0, 0, 0, 2))

        repository().ensure(setOf(talk(pull)))

        val preview = repository().observe().first().conversations[pull]!!
        assertThat(listOf(preview.additions, preview.deletions, preview.changedFiles)).containsExactly(null, null, null)
    }

    @Test
    fun a_forge_that_refuses_the_rows_together_leaves_them_to_be_asked_again_later() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "tok")
        feedApi.details = mapOf(talk(pull) to FeedDetail.Conversation(ConversationPreview("Retry")))
        feedApi.detailsFailure = ForgeError.Network
        val repository = repository()

        repository.ensure(setOf(talk(pull)))
        feedApi.detailsFailure = null
        repository.ensure(setOf(talk(pull)))
        assertThat(feedApi.detailCalls).hasSize(1)

        now = now.plusSeconds(61)
        repository.ensure(setOf(talk(pull)))
        assertThat(repository.observe().first().conversations[pull]?.title).isEqualTo("Retry")
    }

    @Test
    fun fresh_previews_are_not_fetched_again_and_failures_wait_before_another_try() = runTest {
        repoApi.details[rocket] = repoDetails("acme/rocket")
        val repository = repository()

        repository.ensure(setOf(repoKey(rocket), repoKey(RepoId("gone", "repo"))))
        repository.ensure(setOf(repoKey(rocket), repoKey(RepoId("gone", "repo"))))
        // A new run a day later refreshes the stale preview.
        now = now.plusSeconds(25 * 3600)
        repository().ensure(setOf(repoKey(rocket)))

        assertThat(repoApi.calls).containsExactly("repo:acme/rocket", "repo:gone/repo", "repo:acme/rocket")
    }

    @Test
    fun a_failed_title_is_asked_again_a_minute_later_not_never() = runTest {
        // Regression: a failed fetch was remembered as asked until the app restarted, leaving the title blank.
        val repository = repository()
        issueApi.failure = ForgeError.Network
        repository.ensure(setOf(talk(pull)))
        issueApi.failure = null
        issueApi.issues[pull] = issueDetails(pull, "Retry the fuel pump handshake")

        repository.ensure(setOf(talk(pull)))
        assertThat(repository.observe().first().conversations).isEmpty()

        now = now.plusSeconds(61)
        repository.ensure(setOf(talk(pull)))
        assertThat(repository.observe().first().conversations[pull]?.title).isEqualTo("Retry the fuel pump handshake")
    }

    @Test
    fun one_preview_failing_never_loses_the_others() = runTest {
        val other = IssueRef(rocket, 44)
        val throwing = object : IssueApi by issueApi {
            override suspend fun issue(token: String?, ref: IssueRef) =
                if (ref == other) throw IllegalStateException("unexpected answer") else ForgeResult.Success(issueDetails(ref, "Retry the fuel pump handshake"))
        }

        repository(throwing).ensure(setOf(talk(pull), talk(other)))

        assertThat(repository().observe().first().conversations.keys).containsExactly(pull)
    }

    @Test
    fun a_row_written_in_a_shape_this_version_can_t_read_is_skipped() = runTest {
        database.feedPreviewDao().upsert(listOf(FeedPreviewEntity("talk:github.com/acme/rocket#43", "{not json", now.toEpochMilli())))
        issueApi.issues[IssueRef(rocket, 44)] = issueDetails(IssueRef(rocket, 44), "Fine")

        repository().ensure(setOf(talk(IssueRef(rocket, 44))))

        assertThat(repository().observe().first().conversations.keys).containsExactly(IssueRef(rocket, 44))
    }

    @Test
    fun a_fetch_finishes_even_when_the_feed_is_left_meanwhile() = runTest {
        // Regression: fetches ran in the screen's scope, and leaving the Feed cut them off for good.
        issueApi.issues[pull] = issueDetails(pull, "Retry the fuel pump handshake")
        val slow = CompletableDeferred<Unit>()
        issueApi.gate = slow
        val repository = repository()

        val screen = launch { repository.ensure(setOf(talk(pull))) }
        // The title is being asked when the Feed is left.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (issueApi.calls.isEmpty()) delay(10) } }
        screen.cancel()
        slow.complete(Unit)

        // Saved on the database's own thread: wait on the real clock.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (repository.observe().first().conversations[pull] == null) delay(10) } }
        assertThat(repository.observe().first().conversations[pull]?.title).isEqualTo("Retry the fuel pump handshake")
    }

    @Test
    fun previews_are_cached_and_matched_regardless_of_repo_casing() = runTest {
        val uppercaseRepo = RepoId("RoiArthurB", "forgeline")
        val uppercasePull = IssueRef(uppercaseRepo, 4)
        repoApi.details[uppercaseRepo] = repoDetails("RoiArthurB/forgeline", stars = 100)
        issueApi.issues[uppercasePull] = issueDetails(uppercasePull, "Display PR titles correctly")

        val repository = repository()
        repository.ensure(setOf(repoKey(uppercaseRepo), talk(uppercasePull)))

        val previews = repository.observe().first()
        assertThat(previews.conversations[uppercasePull]?.title).isEqualTo("Display PR titles correctly")
        assertThat(previews.repos[uppercaseRepo]?.stars).isEqualTo(100)
    }

    @Test
    fun a_tag_with_slashes_and_at_signs_is_kept_whole() = runTest {
        // Monorepos tag per package: "@acme/engine@2.0.0".
        repoApi.releases = listOf(release("@acme/engine@2.0.0", "The engine starts in half the time it used to."))

        repository().ensure(setOf(releaseKey("@acme/engine@2.0.0")))

        assertThat(repository().observe().first().releases.keys).containsExactly(ReleaseRef(rocket, "@acme/engine@2.0.0"))
    }
}
