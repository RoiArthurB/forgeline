package fr.arthurbrugiere.forgeline.core.data.collections

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.data.database.UserStateDatabase
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.StarList
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.repoSummary
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
class DefaultCollectionsRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserStateDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val users = FakeUserApi()
    private val accounts = FakeAccountRepository()
    private var now = Instant.parse("2026-10-28T10:00:00Z")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val repository = DefaultCollectionsRepository(database.collectionDao(), FakeForgeClients(users = users), accounts, clock)

    @After
    fun close() = database.close()

    @Test
    fun a_collection_holds_repositories_of_any_forge_the_latest_added_first() = runTest {
        val id = repository.create("  Matrix tools ")
        repository.add(id, repoSummary("matrix-org/synapse"))
        now = now.plusSeconds(60)
        repository.add(id, repoSummary("forgejo/forgejo", forge = ForgeInstance.Codeberg))

        assertThat(repository.observeAll().first().single().let { it.name to it.repoCount }).isEqualTo("Matrix tools" to 2)
        assertThat(repository.observeRepos(id).first().map { it.id.key }).containsExactly("codeberg.org/forgejo/forgejo", "github.com/matrix-org/synapse").inOrder()
        assertThat(repository.observeHolding(repoSummary("forgejo/forgejo", forge = ForgeInstance.Codeberg).id).first()).containsExactly(id)
        // The same name on another forge is another repository.
        assertThat(repository.observeHolding(repoSummary("forgejo/forgejo").id).first()).isEmpty()
    }

    @Test
    fun removing_renaming_and_deleting() = runTest {
        val id = repository.create("Tools")
        val other = repository.create("Other")
        repository.add(id, repoSummary("a/one"))
        repository.add(other, repoSummary("a/one"))

        repository.remove(id, repoSummary("a/one").id)
        repository.rename(other, "Others")
        assertThat(repository.observeHolding(repoSummary("a/one").id).first()).containsExactly(other)
        assertThat(repository.observeName(other).first()).isEqualTo("Others")

        repository.delete(other)
        assertThat(repository.observeAll().first().map { it.id }).containsExactly(id)
        assertThat(repository.observeHolding(repoSummary("a/one").id).first()).isEmpty()
        assertThat(repository.observeName(other).first()).isNull()
    }

    @Test
    fun github_lists_come_over_and_importing_again_only_adds_what_they_gained() = runTest {
        val account = accounts.signIn(ForgeInstance.GitHub, ForgeUser("Me", null, null), "t")
        users.lists = listOf(
            StarList("UL_1", "⛓️ Matrix tools", null, listOf(repoSummary("matrix-org/synapse"), repoSummary("maunium/stickerpicker"))),
            StarList("UL_2", "🖥 Server stuff", null, listOf(repoSummary("caddyserver/caddy"))),
        )

        assertThat(repository.import(account)).isEqualTo(ForgeResult.Success(ImportReport(lists = 2, added = 3)))
        val matrix = repository.observeAll().first().first()
        assertThat(matrix.name).isEqualTo("⛓️ Matrix tools")
        // Kept in the list's order.
        assertThat(repository.observeRepos(matrix.id).first().map { it.id.fullName }).containsExactly("matrix-org/synapse", "maunium/stickerpicker").inOrder()

        // Here, the collection is renamed and a repository taken out; on GitHub, one is added to the list.
        repository.rename(matrix.id, "Matrix")
        repository.remove(matrix.id, repoSummary("maunium/stickerpicker").id)
        users.lists = listOf(users.lists.first().copy(repos = users.lists.first().repos + repoSummary("element-hq/element-web")), users.lists[1])

        assertThat(repository.import(account)).isEqualTo(ForgeResult.Success(ImportReport(lists = 2, added = 2)))
        val again = repository.observeAll().first()
        assertThat(again.map { it.name }).containsExactly("Matrix", "🖥 Server stuff").inOrder()
        assertThat(again.first().repoCount).isEqualTo(3)
        assertThat(again.first().importedFrom).isEqualTo("github.com:me:UL_1")
    }

    @Test
    fun only_github_has_lists_and_a_refusal_is_said() = runTest {
        val codeberg = accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "t")
        val github = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "t")
        users.failure = ForgeError.Unauthorized

        assertThat(repository.importableAccounts(listOf(codeberg, github))).containsExactly(github)
        assertThat(repository.import(codeberg)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(repository.import(github)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }
}
