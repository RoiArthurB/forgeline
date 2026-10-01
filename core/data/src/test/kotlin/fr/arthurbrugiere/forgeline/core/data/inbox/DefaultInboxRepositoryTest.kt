package fr.arthurbrugiere.forgeline.core.data.inbox

import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.testing.Rendezvous
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.RecordingDispatcher
import fr.arthurbrugiere.forgeline.core.testing.FakeNotificationsApi
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import fr.arthurbrugiere.forgeline.core.model.NotificationReason
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.SubjectType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class DefaultInboxRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val api = FakeNotificationsApi()
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-09-27T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val issueApi = FakeIssueApi()
    private val clients = FakeForgeClients(notifications = api)
    private val conversations = DefaultIssueRepository(FakeForgeClients(issues = issueApi), accounts, database.conversationDao(), clock)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val computation = RecordingDispatcher()
    private val repository = DefaultInboxRepository(database.inboxDao(), clients, accounts, conversations, clock, background, computation)

    private val me = Account.idFor(ForgeInstance.GitHub, "me")

    private suspend fun signIn(login: String = "me") = accounts.signIn(ForgeInstance.GitHub, ForgeUser(login, null, null), "t-$login")

    @After
    fun closeDatabase() {
        // Background work still running would read a closed database: stop it first.
        runBlocking { background.coroutineContext.job.cancelAndJoin() }
        database.close()
    }

    @Test
    fun the_list_is_built_off_the_thread_that_reads_it() = runTest {
        // Regression: threads were mapped and filtered on the collector's thread, the main thread in the app.
        signIn()

        repository.observe().first()

        assertThat(computation.uses.get()).isGreaterThan(0)
    }

    @Test
    fun signed_out_the_inbox_is_empty_and_never_syncs() = runTest {
        assertThat(repository.sync(force = true, waitForFollowUps = true)).isEqualTo(SyncResult.SignedOut)
        assertThat(repository.observe().first().threads).isEmpty()
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun sync_caches_threads_newest_first() = runTest {
        signIn()
        api.threads = listOf(
            notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"),
            notificationThread("2", updatedAt = "2026-09-27T09:00:00Z"),
        )

        assertThat(repository.sync(force = true, waitForFollowUps = true)).isInstanceOf(SyncResult.Updated::class.java)

        val snapshot = repository.observe().first()
        assertThat(snapshot.threads.map { it.id }).containsExactly("2", "1").inOrder()
        assertThat(snapshot.syncedAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun syncs_are_conditional_and_respect_the_poll_interval() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true, waitForFollowUps = true)

        now = now.plusSeconds(30)
        assertThat(repository.sync(waitForFollowUps = true)).isEqualTo(SyncResult.NotModified)
        assertThat(api.calls.filter { it == "threads" }).containsExactly("threads")

        now = now.plusSeconds(60)
        api.notModified = true
        assertThat(repository.sync(waitForFollowUps = true)).isEqualTo(SyncResult.NotModified)
        assertThat(api.ifModifiedSince.last()).isEqualTo("modified-1")
        assertThat(repository.observe().first().threads).hasSize(1)
    }

    @Test
    fun threads_show_where_their_issue_or_pull_request_stands() = runTest {
        signIn()
        api.threads = listOf(
            notificationThread("1", type = SubjectType.PULL_REQUEST),
            notificationThread("2", type = SubjectType.ISSUE),
            notificationThread("r", type = SubjectType.RELEASE, number = null),
        )
        api.states[IssueRef(RepoId("acme", "rocket"), 1)] = SubjectState.MERGED
        api.states[IssueRef(RepoId("acme", "rocket"), 2)] = SubjectState.OPEN

        repository.sync(force = true, waitForFollowUps = true)

        val states = repository.observe().first().threads.associate { it.id to it.state }
        assertThat(states).containsExactly("1", SubjectState.MERGED, "2", SubjectState.OPEN, "r", null)
        // Releases have no state to ask for.
        assertThat(api.calls.last()).isEqualTo("states:acme/rocket#1,acme/rocket#2")
    }

    @Test
    fun states_are_asked_again_only_when_a_thread_moves_on_or_they_get_old() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", type = SubjectType.PULL_REQUEST), notificationThread("2", type = SubjectType.PULL_REQUEST))
        api.states[IssueRef(RepoId("acme", "rocket"), 1)] = SubjectState.OPEN
        repository.sync(force = true, waitForFollowUps = true)

        repository.sync(force = true, waitForFollowUps = true)
        assertThat(api.calls.count { it.startsWith("states:") }).isEqualTo(2)
        // #2 got no answer (gone), so it's asked again; #1 is known and nothing moved.
        assertThat(api.calls.last()).isEqualTo("states:acme/rocket#2")

        api.threads = listOf(notificationThread("1", type = SubjectType.PULL_REQUEST, updatedAt = "2026-09-27T09:30:00Z"))
        api.states[IssueRef(RepoId("acme", "rocket"), 1)] = SubjectState.MERGED
        repository.sync(force = true, waitForFollowUps = true)
        assertThat(repository.observe().first().threads.single().state).isEqualTo(SubjectState.MERGED)

        now = now.plusSeconds(3_601)
        repository.sync(force = true, waitForFollowUps = true)
        assertThat(api.calls.last()).isEqualTo("states:acme/rocket#1")
    }

    @Test
    fun a_fresh_sync_replaces_threads_done_elsewhere() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"), notificationThread("2"))
        repository.sync(force = true, waitForFollowUps = true)
        api.threads = listOf(notificationThread("2"))

        repository.sync(force = true, waitForFollowUps = true)

        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("2")
    }

    @Test
    fun a_failed_sync_keeps_the_inbox() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true, waitForFollowUps = true)
        api.failure = ForgeError.Network

        assertThat(repository.sync(force = true, waitForFollowUps = true)).isEqualTo(SyncResult.Failed(ForgeError.Network))
        assertThat(repository.observe().first().threads).hasSize(1)
    }

    @Test
    fun mark_read_is_instant_and_rolled_back_on_failure() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", unread = true))
        repository.sync(force = true, waitForFollowUps = true)

        assertThat(repository.markRead(me, "1")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(repository.observe().first().threads.single().unread).isFalse()

        api.threads = listOf(notificationThread("1", unread = true))
        repository.sync(force = true, waitForFollowUps = true)
        api.failure = ForgeError.Network
        assertThat(repository.markRead(me, "1")).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.observe().first().threads.single().unread).isTrue()
    }

    @Test
    fun mark_done_removes_the_thread_and_restores_it_on_failure() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"), notificationThread("2"))
        repository.sync(force = true, waitForFollowUps = true)

        repository.markDone(me, "1")
        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("2")

        api.failure = ForgeError.Network
        repository.markDone(me, "2")
        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("2")
    }

    @Test
    fun unsubscribing_also_clears_the_thread() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true, waitForFollowUps = true)

        repository.unsubscribe(me, "1")

        assertThat(api.calls).containsAtLeast("unsubscribe:1", "done:1").inOrder()
        assertThat(repository.observe().first().threads).isEmpty()
    }

    @Test
    fun the_first_sync_is_a_baseline_so_old_threads_never_notify() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"))

        repository.sync(force = true, waitForFollowUps = true)

        assertThat(repository.takeThreadsToNotify()).isEmpty()
    }

    @Test
    fun only_new_unread_activity_is_notified_and_only_once() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"))
        repository.sync(force = true, waitForFollowUps = true)
        repository.takeThreadsToNotify()
        api.threads = listOf(
            notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"),
            notificationThread("2", updatedAt = "2026-09-27T09:30:00Z"),
            notificationThread("3", updatedAt = "2026-09-27T09:40:00Z", unread = false),
        )
        repository.sync(force = true, waitForFollowUps = true)

        assertThat(repository.takeThreadsToNotify().map { it.id }).containsExactly("2")
        assertThat(repository.takeThreadsToNotify()).isEmpty()
    }

    @Test
    fun every_signed_in_account_shows_in_one_inbox() = runTest {
        // Each forge numbers its own threads: the same id on two forges is two threads.
        val codeberg = FakeNotificationsApi().apply { threads = listOf(notificationThread("1", repo = "forgejo/forgejo", updatedAt = "2026-09-27T09:30:00Z")) }
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(notifications = codeberg))
        signIn()
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        api.threads = listOf(notificationThread("1"))

        repository.sync(force = true, waitForFollowUps = true)

        val threads = repository.observe().first().threads
        assertThat(threads.map { it.repo.fullName }).containsExactly("forgejo/forgejo", "acme/rocket").inOrder()
        assertThat(threads.first().repo.forge).isEqualTo(ForgeInstance.Codeberg)
        assertThat(codeberg.calls).contains("threads")

        repository.markDone(Account.idFor(ForgeInstance.Codeberg, "me"), "1")

        assertThat(codeberg.calls).contains("done:1")
        assertThat(api.calls).doesNotContain("done:1")
        assertThat(repository.observe().first().threads.map { it.repo.fullName }).containsExactly("acme/rocket")
    }

    @Test
    fun one_forge_failing_leaves_the_others_updated() = runTest {
        val codeberg = FakeNotificationsApi().apply { failure = ForgeError.Network }
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(notifications = codeberg))
        signIn()
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        api.threads = listOf(notificationThread("1"))

        assertThat(repository.sync(force = true, waitForFollowUps = true)).isInstanceOf(SyncResult.Updated::class.java)
        assertThat(repository.observe().first().threads).hasSize(1)
    }

    @Test
    fun a_signed_out_account_leaves_the_inbox() = runTest {
        signIn("alice")
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true, waitForFollowUps = true)

        accounts.signOut(Account.idFor(ForgeInstance.GitHub, "alice"))

        assertThat(repository.observe().first().threads).isEmpty()
    }

    @Test
    fun conversations_waiting_on_you_are_loaded_ahead_after_a_sync() = runTest {
        signIn()
        api.threads = listOf(
            notificationThread("1", reason = NotificationReason.REVIEW_REQUESTED, type = SubjectType.PULL_REQUEST),
            notificationThread("2", reason = NotificationReason.MENTION, unread = false),
            notificationThread("3", reason = NotificationReason.SUBSCRIBED),
            notificationThread("r", reason = NotificationReason.MENTION, type = SubjectType.RELEASE, number = null),
        )
        val pull = IssueRef(RepoId("acme", "rocket"), 1)
        val read = IssueRef(RepoId("acme", "rocket"), 2)
        issueApi.issues[pull] = issueDetails(pull)
        issueApi.issues[read] = issueDetails(read)

        repository.sync(force = true, waitForFollowUps = true)

        // Waiting on you, read or not, with a conversation to load: #1 and #2. Not #3 (only subscribed) or the release.
        assertThat(issueApi.calls).containsExactly("issue:acme/rocket#1", "timeline:acme/rocket#1@1", "issue:acme/rocket#2", "timeline:acme/rocket#2@1")
        // Kept, and nothing new since: the next sync doesn't ask again.
        repository.sync(force = true, waitForFollowUps = true)
        assertThat(issueApi.calls).hasSize(4)
    }

    @Test
    fun a_read_conversation_waiting_on_you_is_loaded_ahead_too() = runTest {
        // Regression: only unread ones were loaded ahead, so reopening one you had already read waited on the network.
        signIn()
        api.threads = listOf(notificationThread("7", reason = NotificationReason.MENTION, unread = false))
        val ref = IssueRef(RepoId("acme", "rocket"), 7)
        issueApi.issues[ref] = issueDetails(ref)

        repository.sync(force = true, waitForFollowUps = true)

        assertThat(issueApi.calls).contains("issue:acme/rocket#7")
    }

    @Test
    fun unread_conversations_are_loaded_ahead_before_read_ones_when_there_are_too_many() = runTest {
        signIn()
        val cap = DefaultInboxRepository.PREFETCHED_CONVERSATIONS
        // Read ones are the newest; the one unread conversation is the oldest of all.
        api.threads = (1..cap + 3).map { notificationThread("$it", reason = NotificationReason.MENTION, unread = false, updatedAt = "2026-09-27T09:%02d:00Z".format(30 + it)) } +
            notificationThread("99", reason = NotificationReason.MENTION, unread = true, updatedAt = "2026-09-27T08:00:00Z")

        repository.sync(force = true, waitForFollowUps = true)

        assertThat(issueApi.calls.count { it.startsWith("issue:") }).isEqualTo(cap)
        assertThat(issueApi.calls).contains("issue:acme/rocket#99")
    }

    @Test
    fun only_a_few_conversations_are_loaded_ahead_per_sync() = runTest {
        signIn()
        api.threads = (1..DefaultInboxRepository.PREFETCHED_CONVERSATIONS + 5).map {
            notificationThread("$it", reason = NotificationReason.MENTION, updatedAt = "2026-09-27T0${it % 10}:00:00Z")
        }

        repository.sync(force = true, waitForFollowUps = true)

        assertThat(issueApi.calls.count { it.startsWith("issue:") }).isEqualTo(DefaultInboxRepository.PREFETCHED_CONVERSATIONS)
    }

    private suspend fun codebergAccount(api: FakeNotificationsApi): String {
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(notifications = api))
        return accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token").id
    }

    @Test
    fun done_on_a_forge_without_done_hides_the_thread_until_it_has_news() = runTest {
        val codeberg = FakeNotificationsApi(supportsDone = false).apply { threads = listOf(notificationThread("7", repo = "forgejo/forgejo")) }
        val me = codebergAccount(codeberg)
        repository.sync(force = true, waitForFollowUps = true)

        repository.markDone(me, "7")

        assertThat(codeberg.calls).contains("done:7")
        assertThat(repository.observe().first().threads).isEmpty()
        // Still listed by the forge, as read: still hidden.
        repository.sync(force = true, waitForFollowUps = true)
        assertThat(repository.observe().first().threads).isEmpty()

        // New activity brings it back, like GitHub's done.
        codeberg.threads = listOf(notificationThread("7", repo = "forgejo/forgejo", updatedAt = "2026-09-27T11:00:00Z"))
        repository.sync(force = true, waitForFollowUps = true)
        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("7")
    }

    @Test
    fun states_that_come_with_threads_are_kept_without_asking() = runTest {
        val codeberg = FakeNotificationsApi(supportsDone = false).apply {
            threads = listOf(notificationThread("7", repo = "forgejo/forgejo", type = SubjectType.PULL_REQUEST).copy(state = SubjectState.MERGED))
        }
        codebergAccount(codeberg)

        repository.sync(force = true, waitForFollowUps = true)

        assertThat(repository.observe().first().threads.single().state).isEqualTo(SubjectState.MERGED)
        assertThat(codeberg.calls.none { it.startsWith("states:") }).isTrue()
    }

    /** Waits on the real clock: the work runs on real threads, which the test's virtual time doesn't wait for. */
    private suspend fun <T> realTime(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    @Test
    fun accounts_sync_at_the_same_time_not_one_after_another() = runTest {
        // Regression: one lock for every account made each wait for the one before, a round trip to Europe each.
        signIn()
        val codebergApi = FakeNotificationsApi(supportsDone = false)
        clients.put(ForgeInstance.Codeberg, FakeForgeClients(notifications = codebergApi))
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "t-codeberg")
        val slow = CompletableDeferred<Unit>()
        api.gate = slow

        val sync = async { repository.sync(force = true, waitForFollowUps = true) }
        // GitHub is still answering; Codeberg is asked all the same. Both run on real threads, so wait for both to have
        // been asked: asserting GitHub's call the instant Codeberg's shows raced on a slow machine (CI, 2026-10-01).
        realTime { while (codebergApi.calls.isEmpty() || api.calls.isEmpty()) delay(10) }
        assertThat(api.calls).containsExactly("threads")
        slow.complete(Unit)

        assertThat(sync.await()).isInstanceOf(SyncResult.Updated::class.java)
    }

    @Test
    fun the_refresh_ends_with_the_threads_not_with_the_conversations_loaded_ahead() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", reason = NotificationReason.REVIEW_REQUESTED, type = SubjectType.PULL_REQUEST))
        val pull = IssueRef(RepoId("acme", "rocket"), 1)
        issueApi.issues[pull] = issueDetails(pull)
        val slow = CompletableDeferred<Unit>()
        issueApi.gate = slow

        // The conversation is still loading, yet the sync is over and the thread on screen.
        assertThat(realTime { repository.sync(force = true) }).isInstanceOf(SyncResult.Updated::class.java)
        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("1")

        slow.complete(Unit)
        realTime { while (conversations.stored(pull)?.issue == null) delay(10) }
    }

    @Test
    fun conversations_are_loaded_ahead_several_at_a_time() = runTest {
        // Regression: they were loaded one after another, a few round trips each to a far forge.
        signIn()
        api.threads = listOf(
            notificationThread("1", reason = NotificationReason.REVIEW_REQUESTED, type = SubjectType.PULL_REQUEST),
            notificationThread("2", reason = NotificationReason.REVIEW_REQUESTED, type = SubjectType.PULL_REQUEST).copy(number = 2),
        )
        val refs = listOf(IssueRef(RepoId("acme", "rocket"), 1), IssueRef(RepoId("acme", "rocket"), 2))
        refs.forEach { issueApi.issues[it] = issueDetails(it) }
        val together = Rendezvous(2)
        val meeting = object : IssueApi by issueApi {
            override suspend fun issue(token: String?, ref: IssueRef) = together.arrive("#${ref.number}").let { issueApi.issue(token, ref) }
        }
        val ahead = DefaultIssueRepository(FakeForgeClients(issues = meeting), accounts, database.conversationDao(), clock)
        val inbox = DefaultInboxRepository(database.inboxDao(), clients, accounts, ahead, clock, background, computation)

        realTime { inbox.sync(force = true, waitForFollowUps = true) }

        refs.forEach { assertThat(ahead.stored(it)?.issue).isNotNull() }
    }
}
