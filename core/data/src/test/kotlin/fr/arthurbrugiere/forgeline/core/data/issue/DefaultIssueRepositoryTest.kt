package fr.arthurbrugiere.forgeline.core.data.issue

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
    private val repository = DefaultIssueRepository(api, accounts, database.conversationDao(), clock)

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

        val relaunched = DefaultIssueRepository(api, accounts, database.conversationDao(), clock)

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

        val relaunched = DefaultIssueRepository(api, accounts, database.conversationDao(), clock)

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
}
