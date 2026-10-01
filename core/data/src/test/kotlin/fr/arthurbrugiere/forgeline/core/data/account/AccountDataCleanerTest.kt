package fr.arthurbrugiere.forgeline.core.data.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.data.database.UserStateDatabase
import fr.arthurbrugiere.forgeline.core.data.feed.FeedEventEntity
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewEntity
import fr.arthurbrugiere.forgeline.core.data.feed.FeedSyncEntity
import fr.arthurbrugiere.forgeline.core.data.feed.STARRED_PREFIX
import fr.arthurbrugiere.forgeline.core.data.inbox.DoneEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxSyncEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.NotificationEntity
import fr.arthurbrugiere.forgeline.core.data.inbox.SubjectStateEntity
import fr.arthurbrugiere.forgeline.core.data.issue.DefaultIssueRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoCacheEntity
import fr.arthurbrugiere.forgeline.core.data.user.DefaultUserRepository
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeIssueApi
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock

/**
 * Regression: signing out only removed the account. Its notifications, its Feed and the conversations it had read
 * (private repositories' included) stayed on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class AccountDataCleanerTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val state = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserStateDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val accounts = FakeAccountRepository()
    private val issueApi = FakeIssueApi()
    private val userApi = FakeUserApi().apply { users["alice"] = userProfile("alice") }
    private val clients = FakeForgeClients(issues = issueApi, users = userApi)
    private val conversations = DefaultIssueRepository(clients, accounts, database.conversationDao(), Clock.systemUTC())
    private val users = DefaultUserRepository(clients, accounts)
    private val cleaner = AccountDataCleaner(
        accounts, database.inboxDao(), state.doneDao(), database.feedDao(), database.repoDao(), database.feedPreviewDao(), conversations, users,
    )

    private val forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg)

    @After
    fun closeDatabase() {
        database.close()
        state.close()
    }

    private fun issue(forge: ForgeInstance) = IssueRef(RepoId("acme", "secret", forge), 7)

    /** Everything the app keeps about [account] and its forge. */
    private suspend fun fill(account: Account) {
        val host = account.forge.host
        database.inboxDao().insert(listOf(NotificationEntity(account.id, "1", host, "acme", "secret", "Private plans", "ISSUE", 7, "MENTION", true, 1_000, null)))
        database.inboxDao().upsertSync(InboxSyncEntity(account.id, null, 60, 1_000, 1_000))
        state.doneDao().upsert(DoneEntity(account.id, "1", 500))
        database.inboxDao().upsertStates(listOf(SubjectStateEntity(host, "acme", "secret", 7, "OPEN", 1_000, 1_000)))
        listOf(account.id, STARRED_PREFIX + account.id).forEach { key ->
            database.feedDao().insert(listOf(FeedEventEntity(key, "e1", "alice", null, host, "acme", "secret", 1_000, "starred", null, null, null, false)))
            database.feedDao().upsertSync(FeedSyncEntity(key, null, 60, 1_000, null))
        }
        database.repoDao().upsert(RepoCacheEntity("$host/acme/secret", "{}", "README.md", "# Secret", 1_000))
        database.feedPreviewDao().upsert(
            listOf(
                FeedPreviewEntity("repo:$host/acme/secret", "Private", null, 1, null, 1_000),
                FeedPreviewEntity("pull:$host/acme/secret#7", null, null, null, "Private plans", 1_000),
            ),
        )
        issueApi.issues[issue(account.forge)] = issueDetails(issue(account.forge))
        conversations.issue(issue(account.forge))
        users.user(account.forge, "alice")
    }

    private suspend fun signInEverywhere(): List<Account> =
        forges.map { forge -> accounts.signIn(forge, ForgeUser("me", null, null), "token").also { fill(it) } }

    private suspend fun signOut(account: Account) {
        accounts.signOut(account.id)
        cleaner.forget(account)
    }

    @Test
    fun signing_out_deletes_what_was_kept_for_the_account_and_its_forge() = runTest {
        val (_, codeberg) = signInEverywhere()

        signOut(codeberg)

        assertThat(database.inboxDao().all(codeberg.id)).isEmpty()
        assertThat(database.inboxDao().sync(codeberg.id)).isNull()
        assertThat(state.doneDao().observe().first().map { it.accountId }).doesNotContain(codeberg.id)
        assertThat(database.inboxDao().states().map { it.host }).doesNotContain("codeberg.org")
        assertThat(database.feedDao().observeAll().first().map { it.accountId }).containsNoneOf(codeberg.id, STARRED_PREFIX + codeberg.id)
        assertThat(database.feedDao().sync(codeberg.id)).isNull()
        assertThat(database.feedDao().sync(STARRED_PREFIX + codeberg.id)).isNull()
        assertThat(database.repoDao().fetchedAt("codeberg.org/acme/secret")).isNull()
        assertThat(database.feedPreviewDao().freshness().map { it.key }).containsExactly("repo:github.com/acme/secret", "pull:github.com/acme/secret#7")
        assertThat(conversations.stored(issue(ForgeInstance.Codeberg))).isNull()
        assertThat(users.cachedUser(ForgeInstance.Codeberg, "alice")).isNull()
    }

    @Test
    fun signing_out_of_one_forge_keeps_the_others() = runTest {
        val (github, codeberg) = signInEverywhere()

        signOut(codeberg)

        assertThat(database.inboxDao().all(github.id)).hasSize(1)
        assertThat(database.inboxDao().sync(github.id)).isNotNull()
        assertThat(state.doneDao().observe().first().map { it.accountId }).containsExactly(github.id)
        assertThat(database.inboxDao().states().map { it.host }).containsExactly("github.com")
        assertThat(database.feedDao().observeAll().first().map { it.accountId }).containsExactly(github.id, STARRED_PREFIX + github.id)
        assertThat(database.repoDao().fetchedAt("github.com/acme/secret")).isNotNull()
        assertThat(conversations.stored(issue(ForgeInstance.GitHub))).isNotNull()
        assertThat(users.cachedUser(ForgeInstance.GitHub, "alice")).isNotNull()
    }

    @Test
    fun a_sweep_deletes_what_is_left_of_accounts_no_longer_signed_in() = runTest {
        // Left behind by a version that didn't clean up, or by a sync that finished after the sign-out.
        val (github, codeberg) = signInEverywhere()
        accounts.signOut(codeberg.id)

        cleaner.sweep()

        assertThat(database.inboxDao().all(codeberg.id)).isEmpty()
        assertThat(database.feedDao().observeAll().first().map { it.accountId }).containsExactly(github.id, STARRED_PREFIX + github.id)
        assertThat(database.inboxDao().all(github.id)).hasSize(1)
    }

    @Test
    fun a_sweep_with_everyone_signed_out_deletes_every_account_s_rows() = runTest {
        val all = signInEverywhere()
        all.forEach { accounts.signOut(it.id) }

        cleaner.sweep()

        assertThat(database.inboxDao().observeAll().first()).isEmpty()
        assertThat(database.feedDao().observeAll().first()).isEmpty()
    }
}
