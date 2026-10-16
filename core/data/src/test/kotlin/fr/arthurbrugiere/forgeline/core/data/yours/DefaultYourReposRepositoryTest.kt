package fr.arthurbrugiere.forgeline.core.data.yours

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.ForgelineDatabase
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.RecordingDispatcher
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
class DefaultYourReposRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForgelineDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val gitHub = FakeUserApi()
    private val codeberg = FakeUserApi()
    private val clients = FakeForgeClients(users = gitHub).also { it.put(ForgeInstance.Codeberg, FakeForgeClients(users = codeberg)) }
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-10-16T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultYourReposRepository(database.yourReposDao(), clients, accounts, clock, RecordingDispatcher())

    private fun repo(full: String, forge: ForgeInstance = ForgeInstance.GitHub, private: Boolean = false, pushed: String = "2026-10-16T09:00:00Z"): RepoSummary {
        val (owner, name) = full.split('/')
        return RepoSummary(RepoId(owner, name, forge), null, null, 0, 0, false, null, isPrivate = private, pushedAt = Instant.parse(pushed))
    }

    @After
    fun close() = database.close()

    @Test
    fun the_list_is_read_once_kept_and_read_again_when_an_hour_old_or_asked() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        gitHub.reachable = listOf(repo("me/forgeline"), repo("ACROSS-Lab/secret", private = true))
        gitHub.organizations = listOf(UserSummary("ACROSS-Lab", "https://avatars.example/across", true))

        repository.refresh()
        repository.refresh()
        assertThat(gitHub.reachableAsked.get()).isEqualTo(1)

        val yours = repository.observe().first()!!
        assertThat(yours.repos.map { it.id.fullName }).containsExactly("me/forgeline", "ACROSS-Lab/secret")
        assertThat(yours.repos.last().isPrivate).isTrue()
        assertThat(yours.owners.map { it.login }).containsExactly("me", "ACROSS-Lab").inOrder()
        assertThat(yours.owners.last().avatarUrl).isEqualTo("https://avatars.example/across")

        now = now.plusSeconds(61 * 60)
        repository.refresh()
        repository.refresh(force = true)
        assertThat(gitHub.reachableAsked.get()).isEqualTo(3)
    }

    @Test
    fun every_account_s_repositories_are_one_list() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb")
        gitHub.reachable = listOf(repo("me/forgeline", pushed = "2026-10-16T09:00:00Z"))
        codeberg.reachable = listOf(repo("me/dotfiles", ForgeInstance.Codeberg, pushed = "2026-10-16T09:30:00Z"))

        repository.refresh()

        val yours = repository.observe().first()!!
        assertThat(yours.repos.map { it.id.forge to it.id.name }).containsExactly(ForgeInstance.Codeberg to "dotfiles", ForgeInstance.GitHub to "forgeline").inOrder()
    }

    @Test
    fun one_forge_failing_keeps_the_others_and_is_reported_only_when_all_fail() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb")
        codeberg.failure = ForgeError.Network

        assertThat(repository.refresh()).isEqualTo(ForgeResult.Success(Unit))
        gitHub.failure = ForgeError.Network
        assertThat(repository.refresh(force = true)).isEqualTo(ForgeResult.Failure(ForgeError.Network))
    }

    @Test
    fun an_account_signed_out_takes_its_repositories_with_it() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "tok")
        gitHub.reachable = listOf(repo("ACROSS-Lab/secret", private = true))
        repository.refresh()

        accounts.signOut(accounts.accounts.first().single().id)

        // Not even shown while the rows wait to be swept: the list is only ever of who is signed in.
        assertThat(repository.observe().first()).isNull()
        database.yourReposDao().keepOnly(emptyList())
        assertThat(database.yourReposDao().get(Account.idFor(ForgeInstance.GitHub, "me"))).isNull()
    }
}

private typealias Account = fr.arthurbrugiere.forgeline.core.model.Account
