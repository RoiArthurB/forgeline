package fr.arthurbrugiere.forgeline.core.data.issue

import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.testing.Rendezvous
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class DefaultIssueRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val api = FakeIssueApi()
    private val accounts = FakeAccountRepository()
    private var now = 1_000L
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

    @After
    fun closeDatabase() = database.close()

    @Test
    fun a_conversation_is_kept_on_disk_for_the_next_launch() = runTest {
        val review = TimelineItem.Review(2, ForgeUser("rev", null, null), ReviewState.APPROVED, null, Instant.parse("2026-09-26T09:00:00Z"))
        val labeled = TimelineItem.Labeled(true, Label("bug", "d73a4a"), ForgeUser("maint", null, null), Instant.parse("2026-09-26T09:30:00Z"))
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi"), review, labeled), nextPage = 2)
        repository.issue(ref)
        repository.timeline(ref, 1)

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.cached(ref)).isNull()
        val stored = relaunched.stored(ref)!!
        assertThat(stored.issue).isEqualTo(issueDetails(ref))
        assertThat(stored.firstPage).isEqualTo(TimelinePage(listOf(comment(1, "Hi"), review, labeled), nextPage = 2))
        assertThat(relaunched.cached(ref)).isEqualTo(stored)
    }

    @Test
    fun only_the_most_recently_viewed_conversations_stay_on_disk() = runTest {
        repeat(DefaultIssueRepository.STORED_CONVERSATIONS + 1) { number ->
            val other = IssueRef(RepoId("octo", "repo"), number + 100)
            api.issues[other] = issueDetails(other)
            now += 1
            repository.issue(other)
        }

        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)

        assertThat(relaunched.stored(IssueRef(RepoId("octo", "repo"), 100))).isNull()
        assertThat(relaunched.stored(IssueRef(RepoId("octo", "repo"), 101))).isNotNull()
    }
    private val ref = IssueRef(RepoId("octo", "repo"), 7)

    @Test
    fun a_viewed_conversation_is_remembered_for_an_instant_reopen() = runTest {
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), null)

        assertThat(repository.cached(ref)).isNull()
        repository.issue(ref)
        repository.timeline(ref, 1)

        val cached = repository.cached(ref)!!
        assertThat(cached.issue).isEqualTo(issueDetails(ref))
        assertThat(cached.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun failures_are_returned_and_do_not_erase_the_cache() = runTest {
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        api.failure = ForgeError.Network

        assertThat(repository.issue(ref)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.cached(ref)?.issue).isEqualTo(issueDetails(ref))
    }

    @Test
    fun calls_are_anonymous_signed_out_and_authenticated_signed_in() = runTest {
        api.issues[ref] = issueDetails(ref)
        repository.issue(ref)
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")
        repository.issue(ref)

        assertThat(api.tokens).containsExactly(null, "ghp_token").inOrder()
    }

    @Test
    fun the_cache_is_bounded() = runTest {
        repeat(DefaultIssueRepository.CACHE_SIZE + 5) { number ->
            val each = IssueRef(ref.repo, number)
            api.issues[each] = issueDetails(each)
            repository.issue(each)
        }

        assertThat(repository.cached(IssueRef(ref.repo, 0))).isNull()
        assertThat(repository.cached(IssueRef(ref.repo, DefaultIssueRepository.CACHE_SIZE + 4))).isNotNull()
    }

    @Test
    fun a_conversation_loaded_ahead_is_kept_until_it_has_newer_activity() = runTest {
        api.issues[ref] = issueDetails(ref)
        now = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli()

        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isTrue()
        assertThat(DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock).stored(ref)?.issue).isEqualTo(issueDetails(ref))

        // Nothing happened since it was kept: no request.
        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:30:00Z"))).isFalse()
        // New activity after it was kept: loaded again.
        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T10:30:00Z"))).isTrue()
        assertThat(api.calls).containsExactly(
            "issue:octo/repo#7", "timeline:octo/repo#7@1", "issue:octo/repo#7", "timeline:octo/repo#7@1",
        ).inOrder()
    }

    @Test
    fun a_conversation_the_forge_cannot_serve_is_not_asked_again_until_it_moves() = runTest {
        // Regression: a deleted or private conversation was never kept, so every sync asked for it again.
        now = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli()

        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isTrue()
        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isFalse()
        assertThat(repository.stored(ref)).isNull()
        // Asked once (its timeline alongside), then left alone.
        assertThat(api.calls.count { it == "issue:octo/repo#7" }).isEqualTo(1)
    }

    @Test
    fun a_network_failure_is_tried_again_next_time() = runTest {
        api.failure = ForgeError.Network

        repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))
        repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))

        // The issue and its timeline are asked side by side, each time.
        assertThat(api.calls.count { it == "issue:octo/repo#7" }).isEqualTo(2)
    }

    @Test
    fun a_conversation_loaded_ahead_asks_its_issue_and_timeline_together() = runTest {
        // One round trip to a far forge instead of two.
        api.issues[ref] = issueDetails(ref)
        val together = Rendezvous(2)
        val meeting = object : IssueApi by api {
            override suspend fun issue(token: String?, ref: IssueRef) = together.arrive("issue").let { api.issue(token, ref) }
            override suspend fun timeline(token: String?, ref: IssueRef, page: Int) = together.arrive("timeline").let { api.timeline(token, ref, page) }
        }
        val repository = DefaultIssueRepository(FakeForgeClients(issues = meeting), accounts, database.conversationDao(), clock)

        assertThat(repository.prefetch(ref, Instant.parse("2026-09-29T09:00:00Z"))).isTrue()
        assertThat(repository.stored(ref)?.issue).isNotNull()
    }

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")

    @Test
    fun commenting_needs_an_account_on_the_conversation_s_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.comment(ref, "Hello")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.posted).isEmpty()
    }

    @Test
    fun a_comment_is_posted_with_the_account_of_the_conversation_s_forge() = runTest {
        signIn()

        val result = repository.comment(ref, "Hello")

        assertThat((result as ForgeResult.Success).value.body).isEqualTo("Hello")
        assertThat(api.posted).containsExactly("${ref.repo.fullName}#${ref.number}: Hello")
        assertThat(api.tokens.last()).isEqualTo("tok")
    }

    @Test
    fun a_posted_comment_joins_the_conversation_kept_when_all_of_it_was_loaded() = runTest {
        signIn()
        api.issues[ref] = issueDetails(ref)
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), nextPage = null)
        repository.issue(ref)
        repository.timeline(ref, 1)

        val posted = (repository.comment(ref, "Hello") as ForgeResult.Success).value

        // Reopening shows it at once, in this session and the next.
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), posted).inOrder()
        assertThat(repository.cached(ref)?.issue?.comments).isEqualTo(issueDetails(ref).comments + 1)
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"), posted).inOrder()
    }

    @Test
    fun a_posted_comment_is_not_added_to_a_conversation_kept_only_in_part() = runTest {
        // It belongs after pages that aren't loaded: adding it to the first page would put it in the wrong place.
        signIn()
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), nextPage = 2)
        repository.timeline(ref, 1)

        repository.comment(ref, "Hello")

        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun a_refused_comment_changes_nothing() = runTest {
        signIn()
        api.pages[ref to 1] = TimelinePage(listOf(comment(1, "Hi")), nextPage = null)
        repository.timeline(ref, 1)
        api.commentFailure = ForgeError.Http(403, "locked")

        assertThat(repository.comment(ref, "Hello")).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "locked")))
        assertThat(repository.cached(ref)?.firstPage?.items).containsExactly(comment(1, "Hi"))
    }

    @Test
    fun opening_an_issue_needs_an_account_on_the_repository_s_forge() = runTest {
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "codeberg-tok")

        assertThat(repository.create(ref.repo, "Crash", "Steps")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.opened).isEmpty()
    }

    @Test
    fun an_issue_is_opened_with_the_account_of_the_repository_s_forge() = runTest {
        signIn()

        val created = (repository.create(ref.repo, "Crash", "Steps") as ForgeResult.Success).value

        assertThat(created.title).isEqualTo("Crash")
        assertThat(api.opened).containsExactly("${ref.repo.fullName}: Crash / Steps")
        assertThat(api.tokens.last()).isEqualTo("tok")
    }

    @Test
    fun an_opened_issue_is_kept_whole_so_it_opens_without_the_forge() = runTest {
        signIn()

        val created = (repository.create(ref.repo, "Crash", "Steps") as ForgeResult.Success).value

        assertThat(repository.cached(created.ref)).isEqualTo(CachedConversation(created, TimelinePage(emptyList(), nextPage = null)))
        val relaunched = DefaultIssueRepository(FakeForgeClients(issues = api), accounts, database.conversationDao(), clock)
        assertThat(relaunched.stored(created.ref)?.issue).isEqualTo(created)
    }

    @Test
    fun an_opened_issue_is_announced_and_a_refused_one_is_not() = runTest {
        signIn()
        val announced = mutableListOf<IssueRef>()
        val listening = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { repository.created.collect { announced += it } }

        api.createFailure = ForgeError.Http(410, "Issues are disabled for this repo")
        assertThat(repository.create(ref.repo, "Crash", "Steps")).isEqualTo(ForgeResult.Failure(ForgeError.Http(410, "Issues are disabled for this repo")))
        assertThat(announced).isEmpty()

        api.createFailure = null
        val created = (repository.create(ref.repo, "Crash", "Steps") as ForgeResult.Success).value
        assertThat(announced).containsExactly(created.ref)
        listening.cancel()
    }
}
