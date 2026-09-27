package fr.arthurbrugiere.forgeline.core.data.inbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeNotificationsApi
import fr.arthurbrugiere.forgeline.core.testing.notificationThread
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
    private val repository = DefaultInboxRepository(database.inboxDao(), api, accounts, clock)

    private suspend fun signIn(login: String = "me") = accounts.signIn(ForgeInstance.GitHub, ForgeUser(login, null, null), "t-$login")

    @After
    fun closeDatabase() = database.close()

    @Test
    fun signed_out_the_inbox_is_empty_and_never_syncs() = runTest {
        assertThat(repository.sync(force = true)).isEqualTo(SyncResult.SignedOut)
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

        assertThat(repository.sync(force = true)).isInstanceOf(SyncResult.Updated::class.java)

        val snapshot = repository.observe().first()
        assertThat(snapshot.threads.map { it.id }).containsExactly("2", "1").inOrder()
        assertThat(snapshot.syncedAtMillis).isEqualTo(now.toEpochMilli())
    }

    @Test
    fun syncs_are_conditional_and_respect_the_poll_interval() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true)

        now = now.plusSeconds(30)
        assertThat(repository.sync()).isEqualTo(SyncResult.NotModified)
        assertThat(api.calls).containsExactly("threads")

        now = now.plusSeconds(60)
        api.notModified = true
        assertThat(repository.sync()).isEqualTo(SyncResult.NotModified)
        assertThat(api.ifModifiedSince.last()).isEqualTo("modified-1")
        assertThat(repository.observe().first().threads).hasSize(1)
    }

    @Test
    fun a_fresh_sync_replaces_threads_done_elsewhere() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"), notificationThread("2"))
        repository.sync(force = true)
        api.threads = listOf(notificationThread("2"))

        repository.sync(force = true)

        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("2")
    }

    @Test
    fun a_failed_sync_keeps_the_inbox() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true)
        api.failure = ForgeError.Network

        assertThat(repository.sync(force = true)).isEqualTo(SyncResult.Failed(ForgeError.Network))
        assertThat(repository.observe().first().threads).hasSize(1)
    }

    @Test
    fun mark_read_is_instant_and_rolled_back_on_failure() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", unread = true))
        repository.sync(force = true)

        assertThat(repository.markRead("1")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(repository.observe().first().threads.single().unread).isFalse()

        api.threads = listOf(notificationThread("1", unread = true))
        repository.sync(force = true)
        api.failure = ForgeError.Network
        assertThat(repository.markRead("1")).isEqualTo(ForgeResult.Failure(ForgeError.Network))
        assertThat(repository.observe().first().threads.single().unread).isTrue()
    }

    @Test
    fun mark_done_removes_the_thread_and_restores_it_on_failure() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"), notificationThread("2"))
        repository.sync(force = true)

        repository.markDone("1")
        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("2")

        api.failure = ForgeError.Network
        repository.markDone("2")
        assertThat(repository.observe().first().threads.map { it.id }).containsExactly("2")
    }

    @Test
    fun unsubscribing_also_clears_the_thread() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true)

        repository.unsubscribe("1")

        assertThat(api.calls).containsAtLeast("unsubscribe:1", "done:1").inOrder()
        assertThat(repository.observe().first().threads).isEmpty()
    }

    @Test
    fun the_first_sync_is_a_baseline_so_old_threads_never_notify() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"))

        repository.sync(force = true)

        assertThat(repository.takeThreadsToNotify()).isEmpty()
    }

    @Test
    fun only_new_unread_activity_is_notified_and_only_once() = runTest {
        signIn()
        api.threads = listOf(notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"))
        repository.sync(force = true)
        repository.takeThreadsToNotify()
        api.threads = listOf(
            notificationThread("1", updatedAt = "2026-09-27T08:00:00Z"),
            notificationThread("2", updatedAt = "2026-09-27T09:30:00Z"),
            notificationThread("3", updatedAt = "2026-09-27T09:40:00Z", unread = false),
        )
        repository.sync(force = true)

        assertThat(repository.takeThreadsToNotify().map { it.id }).containsExactly("2")
        assertThat(repository.takeThreadsToNotify()).isEmpty()
    }

    @Test
    fun each_account_has_its_own_inbox() = runTest {
        signIn("alice")
        api.threads = listOf(notificationThread("1"))
        repository.sync(force = true)

        signIn("bob")

        assertThat(repository.observe().first().threads).isEmpty()
    }
}
