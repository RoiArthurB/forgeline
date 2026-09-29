package fr.arthurbrugiere.forgeline.core.data.user

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeUserApi
import fr.arthurbrugiere.forgeline.core.testing.userProfile
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultUserRepositoryTest {
    private val api = FakeUserApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultUserRepository(FakeForgeClients(users = api), accounts)

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")

    @Test
    fun profiles_are_remembered_for_an_instant_reopen() = runTest {
        api.users["octocat"] = userProfile("octocat")

        repository.user(ForgeInstance.GitHub, "octocat")

        assertThat(repository.cachedUser(ForgeInstance.GitHub, "octocat")).isEqualTo(userProfile("octocat"))
        assertThat(repository.cachedUser(ForgeInstance.GitHub, "OctoCat")).isEqualTo(userProfile("octocat"))
    }

    @Test
    fun signed_out_the_follow_state_is_unknown_and_cannot_change() = runTest {
        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isNull()
        assertThat(repository.setFollowing(ForgeInstance.GitHub, "octocat", true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.tokens).isEmpty()
    }

    @Test
    fun signed_in_following_uses_the_token() = runTest {
        signIn()

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isFalse()
        repository.setFollowing(ForgeInstance.GitHub, "octocat", true)

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isTrue()
        assertThat(api.tokens.distinct()).containsExactly("ghp_token")
    }

    @Test
    fun a_failed_follow_lookup_is_unknown_not_false() = runTest {
        signIn()
        api.failure = ForgeError.Network

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "octocat")).isNull()
    }

    @Test
    fun the_signed_in_user_cannot_follow_themselves() = runTest {
        signIn()

        assertThat(repository.isFollowing(ForgeInstance.GitHub, "me")).isNull()
        assertThat(repository.isFollowing(ForgeInstance.GitHub, "ME")).isNull()
    }

    @Test
    fun the_same_login_on_two_forges_is_two_people() = runTest {
        // Codeberg's alice isn't GitHub's: each forge answers for its own, and the cache keeps them apart.
        val codeberg = FakeUserApi().apply { users["alice"] = userProfile("alice").copy(name = "Alice on Codeberg") }
        val clients = FakeForgeClients(users = api).apply { put(ForgeInstance.Codeberg, FakeForgeClients(users = codeberg)) }
        api.users["alice"] = userProfile("alice").copy(name = "Alice on GitHub")
        val repository = DefaultUserRepository(clients, accounts)

        repository.user(ForgeInstance.GitHub, "alice")
        repository.user(ForgeInstance.Codeberg, "alice")

        assertThat(repository.cachedUser(ForgeInstance.GitHub, "alice")?.name).isEqualTo("Alice on GitHub")
        assertThat(repository.cachedUser(ForgeInstance.Codeberg, "alice")?.name).isEqualTo("Alice on Codeberg")
    }

    @Test
    fun following_uses_the_account_signed_in_on_that_forge() = runTest {
        signIn()
        val codeberg = FakeUserApi()
        val clients = FakeForgeClients(users = api).apply { put(ForgeInstance.Codeberg, FakeForgeClients(users = codeberg)) }
        val repository = DefaultUserRepository(clients, accounts)

        // Signed in on GitHub only: nothing to follow with on Codeberg.
        assertThat(repository.isFollowing(ForgeInstance.Codeberg, "alice")).isNull()
        assertThat(repository.setFollowing(ForgeInstance.Codeberg, "alice", true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))

        accounts.signIn(ForgeInstance.Codeberg, ForgeUser("me", null, null), "cb_token")
        repository.setFollowing(ForgeInstance.Codeberg, "alice", true)

        assertThat(codeberg.tokens).containsExactly("cb_token")
    }
}
