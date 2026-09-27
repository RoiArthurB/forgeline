package fr.arthurbrugiere.forgeline.core.data.user

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
    private val repository = DefaultUserRepository(api, accounts)

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("me", null, null), "ghp_token")

    @Test
    fun profiles_are_remembered_for_an_instant_reopen() = runTest {
        api.users["octocat"] = userProfile("octocat")

        repository.user("octocat")

        assertThat(repository.cachedUser("octocat")).isEqualTo(userProfile("octocat"))
        assertThat(repository.cachedUser("OctoCat")).isEqualTo(userProfile("octocat"))
    }

    @Test
    fun signed_out_the_follow_state_is_unknown_and_cannot_change() = runTest {
        assertThat(repository.isFollowing("octocat")).isNull()
        assertThat(repository.setFollowing("octocat", true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.tokens).isEmpty()
    }

    @Test
    fun signed_in_following_uses_the_token() = runTest {
        signIn()

        assertThat(repository.isFollowing("octocat")).isFalse()
        repository.setFollowing("octocat", true)

        assertThat(repository.isFollowing("octocat")).isTrue()
        assertThat(api.tokens.distinct()).containsExactly("ghp_token")
    }

    @Test
    fun a_failed_follow_lookup_is_unknown_not_false() = runTest {
        signIn()
        api.failure = ForgeError.Network

        assertThat(repository.isFollowing("octocat")).isNull()
    }

    @Test
    fun the_signed_in_user_cannot_follow_themselves() = runTest {
        signIn()

        assertThat(repository.isFollowing("me")).isNull()
        assertThat(repository.isFollowing("ME")).isNull()
    }
}
