package fr.arthurbrugiere.forgeline.core.data.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.data.database.UserStateDatabase
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.RecordingDispatcher
import fr.arthurbrugiere.forgeline.core.testing.FakeFeedApi
import fr.arthurbrugiere.forgeline.core.testing.feedEvent
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
class DefaultFeedRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val state = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserStateDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val api = FakeFeedApi()
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-09-27T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val clients = FakeForgeClients(feed = api)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val computation = RecordingDispatcher()
    private val repository = DefaultFeedRepository(database.feedDao(), clients, accounts, state.readingMarkDao(), clock, background, computation)

    private suspend fun signIn(login: String = "me") = accounts.signIn(ForgeInstance.GitHub, ForgeUser(login, null, null), "t-$login")

    @After
    fun closeDatabase() {
        // Background work must stop before the database closes under it.
        runBlocking { background.coroutineContext[Job]!!.cancelAndJoin() }
        database.close()
        state.close()
    }

    @Test
    fun the_timeline_is_built_off_the_thread_that_reads_it() = runTest {
        // Regression: events were sorted and merged on the collector's thread, the main thread in the app.
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")

        repository.observe().first()

        assertThat(computation.uses.get()).isGreaterThan(0)
    }

    @Test
    fun signed_out_the_feed_is_empty_and_never_loads() = runTest {
        assertThat(repository.refresh(force = true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.observe().first().events).isEmpty()
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun every_kind_of_activity_survives_the_cache() = runTest {
        signIn()
        val actions = listOf(
            FeedAction.Starred,
            FeedAction.Forked(RepoId("carol", "tools")),
            FeedAction.CreatedRepo("A fresh idea"),
            FeedAction.CreatedRepo(null),
            FeedAction.MadePublic,
            FeedAction.Released("v2.0.0", "Tools 2.0", prerelease = true),
            FeedAction.Released("v1", null, prerelease = false),
            FeedAction.Issue(IssueAction.REOPENED, 7, "Crash"),
            FeedAction.PullRequest(PullRequestAction.MERGED, 43),
            FeedAction.Commented(44, "Title", isPullRequest = false),
            FeedAction.Commented(45, null, isPullRequest = true),
            FeedAction.Reviewed(44, ReviewState.CHANGES_REQUESTED),
            FeedAction.Pushed("main"),
            FeedAction.Branch("v1.2.0", isTag = true, deleted = true),
            FeedAction.AddedMember("bob"),
        )
        // Newest first, one minute apart.
        api.pages[1] = actions.mapIndexed { i, action ->
            feedEvent("${100 - i}", action = action, createdAt = Instant.parse("2026-09-27T09:00:00Z").minusSeconds(60L * i).toString())
        }

        repository.refresh(force = true)

        assertThat(repository.observe().first().events).isEqualTo(api.pages[1])
    }

    @Test
    fun refreshing_replaces_the_cache_and_is_conditional_within_the_poll_interval() = runTest {
        signIn()
        api.pages[1] = listOf(feedEvent("1"))
        repository.refresh(force = true)

        now = now.plusSeconds(30)
        repository.refresh(force = false)
        assertThat(api.calls).containsExactly("me@1")

        now = now.plusSeconds(60)
        repository.refresh(force = false)
        assertThat(api.calls.last()).isEqualTo("me@1 since ${api.lastModified}")
        assertThat(repository.observe().first().events.map { it.id }).containsExactly("1")

        api.pages[1] = listOf(feedEvent("2"))
        api.lastModified = "later"
        now = now.plusSeconds(120)
        repository.refresh(force = false)
        assertThat(repository.observe().first().events.map { it.id }).containsExactly("2")
        assertThat(repository.observe().first().syncedAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun older_pages_are_appended_until_there_are_no_more() = runTest {
        signIn()
        api.pages[1] = listOf(feedEvent("3", createdAt = "2026-09-27T09:00:00Z"))
        api.pages[2] = listOf(feedEvent("2", createdAt = "2026-09-27T08:00:00Z"))
        repository.refresh(force = true)
        assertThat(repository.observe().first().hasMore).isTrue()

        assertThat(repository.loadMore()).isEqualTo(ForgeResult.Success(Unit))

        val snapshot = repository.observe().first()
        assertThat(snapshot.events.map { it.id }).containsExactly("3", "2").inOrder()
        assertThat(snapshot.hasMore).isFalse()
        assertThat(api.calls).containsExactly("me@1", "me@2").inOrder()
    }

    @Test
    fun a_forge_that_turns_the_token_down_ends_that_accounts_sign_in() = runTest {
        val account = signIn()
        api.failure = ForgeError.Unauthorized

        assertThat(repository.refresh(force = true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))

        assertThat(accounts.ended.value).containsExactly(account.id)
    }

    @Test
    fun a_forge_that_cannot_be_reached_ends_nothing() = runTest {
        signIn()
        api.failure = ForgeError.Network

        repository.refresh(force = true)

        assertThat(accounts.ended.value).isEmpty()
    }

    @Test
    fun a_failed_refresh_keeps_the_cache() = runTest {
        signIn()
        api.pages[1] = listOf(feedEvent("1"))
        repository.refresh(force = true)

        api.failure = ForgeError.Network
        assertThat(repository.refresh(force = true)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.observe().first().events.map { it.id }).containsExactly("1")
    }

    @Test
    fun a_signed_out_account_leaves_the_feed() = runTest {
        signIn("me")
        api.pages[1] = listOf(feedEvent("1"))
        repository.refresh(force = true)

        accounts.signOut(Account.idFor(ForgeInstance.GitHub, "me"))

        assertThat(repository.observe().first().events).isEmpty()
        assertThat(repository.observe().first().syncedAtMillis).isNull()
    }

    @Test
    fun every_account_feeds_one_timeline_in_order() = runTest {
        val codeberg = FakeFeedApi().apply { pages[1] = listOf(feedEvent("c1", repo = "forgejo/forgejo", createdAt = "2026-09-27T09:30:00Z")) }
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(feed = codeberg))
        signIn("me")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        api.pages[1] = listOf(feedEvent("g2", createdAt = "2026-09-27T10:00:00Z"), feedEvent("g1", createdAt = "2026-09-27T09:00:00Z"))

        repository.refresh(force = true)

        val events = repository.observe().first().events
        assertThat(events.map { it.id }).containsExactly("g2", "c1", "g1").inOrder()
        assertThat(events[1].repo.forge).isEqualTo(ForgeInstance.Codeberg)
    }

    @Test
    fun rows_never_move_when_an_older_page_arrives() = runTest {
        // GitHub has loaded back to 09:00 with more pages left; Codeberg's page reaches back to 07:00. Codeberg's
        // 08:00 event must wait below the horizon: showing it now would put it above GitHub rows still to come.
        val codeberg = FakeFeedApi().apply {
            pages[1] = listOf(feedEvent("c2", repo = "forgejo/forgejo", createdAt = "2026-09-27T09:30:00Z"), feedEvent("c1", repo = "forgejo/forgejo", createdAt = "2026-09-27T08:00:00Z"))
        }
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(feed = codeberg))
        signIn("me")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        api.pages[1] = listOf(feedEvent("g2", createdAt = "2026-09-27T10:00:00Z"), feedEvent("g1", createdAt = "2026-09-27T09:00:00Z"))
        api.pages[2] = listOf(feedEvent("g0", createdAt = "2026-09-27T08:30:00Z"))
        repository.refresh(force = true)

        assertThat(repository.observe().first().events.map { it.id }).containsExactly("g2", "c2", "g1").inOrder()

        repository.loadMore()

        assertThat(repository.observe().first().events.map { it.id }).containsExactly("g2", "c2", "g1", "g0", "c1").inOrder()
    }

    @Test
    fun the_read_mark_only_moves_to_newer_activity() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("alice", null, null), "t-alice")
        assertThat(repository.readUpTo()).isNull()

        repository.markRead("e2", Instant.parse("2026-09-27T09:00:00Z"))
        repository.markRead("e1", Instant.parse("2026-09-27T08:00:00Z"))

        assertThat(repository.readUpTo()).isEqualTo(Instant.parse("2026-09-27T09:00:00Z"))
    }

    @Test
    fun accounts_refresh_at_the_same_time_not_one_after_another() = runTest {
        // Regression: one lock for every account made each wait for the one before.
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "g")
        val codeberg = FakeFeedApi()
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(feed = codeberg))
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "c")
        val slow = CompletableDeferred<Unit>()
        api.gate = slow

        val refresh = async { repository.refresh(force = true) }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (codeberg.calls.isEmpty()) delay(10) } }
        slow.complete(Unit)

        assertThat(refresh.await()).isEqualTo(ForgeResult.Success(Unit))
    }

    @Test
    fun loading_more_never_waits_for_another_accounts_refresh() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "g")
        api.pages[1] = listOf(feedEvent("1", createdAt = "2026-09-27T09:00:00Z"))
        api.pages[2] = listOf(feedEvent("2", createdAt = "2026-09-26T09:00:00Z"))
        repository.refresh(force = true)
        val codeberg = FakeFeedApi()
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(feed = codeberg))
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "c")
        val slow = CompletableDeferred<Unit>()
        codeberg.gate = slow

        val refresh = async { repository.refresh(force = true) }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (codeberg.calls.isEmpty()) delay(10) } }
        // Codeberg is still answering; GitHub's next page comes all the same.
        assertThat(withContext(Dispatchers.Default) { withTimeout(5_000) { repository.loadMore() } }).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.calls).contains("me@2")

        slow.complete(Unit)
        refresh.await()
    }

    @Test
    fun the_first_forge_to_answer_is_reported_while_a_slow_one_is_still_loading() = runTest {
        // Regression: a pull-to-refresh waited for every forge, so a slow one (Codeberg's feed takes 3 to 8 s from far
        // away) held the indicator although GitHub's news had already arrived.
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "g")
        api.pages[1] = listOf(feedEvent("1", createdAt = "2026-09-27T09:00:00Z"))
        val codeberg = FakeFeedApi()
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(feed = codeberg))
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "c")
        val slow = CompletableDeferred<Unit>()
        codeberg.gate = slow
        val fresh = CompletableDeferred<Unit>()

        val refresh = async { repository.refresh(force = true) { fresh.complete(Unit) } }
        withContext(Dispatchers.Default) { withTimeout(5_000) { fresh.await() } }

        // GitHub's events are saved and reported; Codeberg hasn't answered and the refresh is still running.
        assertThat(refresh.isCompleted).isFalse()
        assertThat(repository.observe().first().events.map { it.id }).containsExactly("1")
        slow.complete(Unit)
        assertThat(refresh.await()).isEqualTo(ForgeResult.Success(Unit))
    }

    @Test
    fun nothing_fresh_is_reported_when_every_forge_fails() = runTest {
        signIn()
        api.failure = ForgeError.Network
        var fresh = 0

        repository.refresh(force = true) { fresh++ }

        assertThat(fresh).isEqualTo(0)
    }

    private fun release(repo: String, tag: String, at: String, id: String = "starred-release:$repo:$tag") =
        feedEvent(id, actor = "maintainer", repo = repo, createdAt = at, action = FeedAction.Released(tag, null, prerelease = false))

    @Test
    fun starred_repositories_join_the_feed_and_survive_a_refresh() = runTest {
        // The owner's case: Immich (starred, not watched) released, and it showed on github.com but not here.
        signIn()
        api.pages[1] = listOf(feedEvent("1", createdAt = "2026-09-27T09:00:00Z"))
        api.starred = listOf(
            release("immich-app/immich", "v3.3.0", "2026-09-27T09:30:00Z"),
            feedEvent("announcement:immich-app/immich:880", actor = "alextran", repo = "immich-app/immich", createdAt = "2026-09-27T08:00:00Z", action = FeedAction.Announced(880, "Immich turns three")),
        )
        repository.refresh(force = true)
        repository.syncStarred()

        // A refresh replaces the account's own events: the starred ones must stay.
        repository.refresh(force = true)

        val events = repository.observe().first().events
        assertThat(events.map { it.repo.fullName to it.action }).containsExactly(
            "immich-app/immich" to FeedAction.Released("v3.3.0", null, prerelease = false),
            "acme/rocket" to FeedAction.Starred,
            "immich-app/immich" to FeedAction.Announced(880, "Immich turns three"),
        ).inOrder()
        // Asked for the last 30 days only.
        assertThat(api.starredCalls.single()).isEqualTo(now.minus(java.time.Duration.ofDays(30)))
    }

    @Test
    fun a_release_seen_through_a_watched_repository_and_a_starred_one_shows_once() = runTest {
        signIn()
        api.pages[1] = listOf(feedEvent("77", actor = "maintainer", repo = "octo/tools", createdAt = "2026-09-27T09:00:02Z", action = FeedAction.Released("v2", "Tools 2", prerelease = false)))
        api.starred = listOf(release("octo/tools", "v2", "2026-09-27T09:00:00Z"))
        repository.refresh(force = true)
        repository.syncStarred()

        assertThat(repository.observe().first().events.map { it.action }).containsExactly(FeedAction.Released("v2", "Tools 2", prerelease = false))
    }

    @Test
    fun starred_activity_older_than_the_loaded_feed_waits_for_its_page() = runTest {
        // Rows never appear below what's loaded: an old starred release shows once paging reaches its time.
        signIn()
        api.pages[1] = listOf(feedEvent("2", createdAt = "2026-09-27T09:00:00Z"))
        api.pages[2] = listOf(feedEvent("1", createdAt = "2026-09-20T09:00:00Z"))
        api.starred = listOf(release("old/news", "v1", "2026-09-22T09:00:00Z"))
        repository.refresh(force = true)
        repository.syncStarred()

        assertThat(repository.observe().first().events.map { it.repo.fullName }).containsExactly("acme/rocket")

        repository.loadMore()
        assertThat(repository.observe().first().events.map { it.repo.fullName }).containsExactly("acme/rocket", "old/news", "acme/rocket").inOrder()
    }

    @Test
    fun starred_repositories_are_asked_at_most_every_half_hour() = runTest {
        signIn()
        repository.syncStarred()
        repository.syncStarred()
        assertThat(api.starredCalls).hasSize(1)

        // A pull-to-refresh may ask sooner, but not within five minutes: each ask costs many seconds of the forge's time.
        now = now.plusSeconds(4 * 60)
        repository.syncStarred(force = true)
        assertThat(api.starredCalls).hasSize(1)
        now = now.plusSeconds(2 * 60)
        repository.syncStarred(force = true)
        assertThat(api.starredCalls).hasSize(2)

        now = now.plusSeconds(31 * 60)
        repository.syncStarred()
        assertThat(api.starredCalls).hasSize(3)
    }

    @Test
    fun starred_repositories_that_cannot_be_read_keep_what_was_shown_and_are_asked_again() = runTest {
        signIn()
        api.starred = listOf(release("octo/tools", "v2", "2026-09-27T09:00:00Z"))
        repository.syncStarred()
        api.starred = null
        now = now.plusSeconds(31 * 60)

        repository.syncStarred()
        repository.syncStarred()

        assertThat(repository.observe().first().events.map { it.repo.fullName }).containsExactly("octo/tools")
        // A failure isn't a sync: the next visit asks again instead of waiting half an hour.
        assertThat(api.starredCalls).hasSize(3)
    }

    @Test
    fun a_refresh_never_waits_for_the_starred_repositories() = runTest {
        signIn()
        api.pages[1] = listOf(feedEvent("1", createdAt = "2026-09-27T09:00:00Z"))
        val slow = CompletableDeferred<Unit>()
        api.starredGate = slow

        val result = withContext(Dispatchers.Default) { withTimeout(5_000) { repository.refresh(force = true) } }

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        // It was started, in the background, and the refresh came back without it.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (api.starredCalls.isEmpty()) delay(10) } }
        slow.complete(Unit)
    }
}
