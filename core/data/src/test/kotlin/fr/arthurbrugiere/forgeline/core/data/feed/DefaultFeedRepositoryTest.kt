package fr.arthurbrugiere.forgeline.core.data.feed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
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
    private val api = FakeFeedApi()
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-09-27T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultFeedRepository(database.feedDao(), api, accounts, clock)

    private suspend fun signIn(login: String = "me") = accounts.signIn(ForgeInstance.GitHub, ForgeUser(login, null, null), "t-$login")

    @After
    fun closeDatabase() = database.close()

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
    fun a_failed_refresh_keeps_the_cache() = runTest {
        signIn()
        api.pages[1] = listOf(feedEvent("1"))
        repository.refresh(force = true)

        api.failure = ForgeError.Network
        assertThat(repository.refresh(force = true)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.observe().first().events.map { it.id }).containsExactly("1")
    }

    @Test
    fun each_account_has_its_own_feed() = runTest {
        signIn("me")
        api.pages[1] = listOf(feedEvent("1"))
        repository.refresh(force = true)

        signIn("other")
        assertThat(repository.observe().first().events).isEmpty()
        assertThat(repository.observe().first().syncedAtMillis).isNull()
    }
}
