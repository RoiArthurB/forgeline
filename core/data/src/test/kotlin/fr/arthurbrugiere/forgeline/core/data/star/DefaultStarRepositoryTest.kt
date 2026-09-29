package fr.arthurbrugiere.forgeline.core.data.star

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeStarApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultStarRepositoryTest {
    private val accounts = FakeAccountRepository()
    private val api = FakeStarApi()
    private val repository = DefaultStarRepository(accounts, FakeForgeClients(stars = api))
    private val repo = RepoId("paperclipai", "paperclip")

    private suspend fun signIn() = accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")

    @Test
    fun signed_out_nothing_is_known_as_starred_and_nothing_is_called() = runTest {
        assertThat(repository.starredStatus(listOf(repo))).isEmpty()
        assertThat(repository.setStarred(repo, true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.tokensSeen).isEmpty()
    }

    @Test
    fun signed_in_uses_the_active_account_token() = runTest {
        signIn()
        api.starred += repo

        assertThat(repository.starredStatus(listOf(repo))).containsExactly(repo, true)
        assertThat(repository.setStarred(repo, false)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(api.starred).isEmpty()
        assertThat(api.tokensSeen.distinct()).containsExactly("ghp_token")
    }

    @Test
    fun a_failed_status_lookup_means_unknown_rather_than_unstarred() = runTest {
        signIn()
        api.failure = ForgeError.Network

        assertThat(repository.starredStatus(listOf(repo))).isEmpty()
    }

    @Test
    fun each_forge_answers_for_its_own_repositories() = runTest {
        signIn()
        val cb = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
        val codeberg = FakeStarApi()
        val repository = DefaultStarRepository(accounts, FakeForgeClients(stars = api).apply { put(ForgeInstance.Codeberg, FakeForgeClients(stars = codeberg)) })

        // Signed in on GitHub only: Codeberg's star state stays unknown, and Codeberg isn't asked.
        val status = repository.starredStatus(listOf(repo, cb))

        assertThat(status.keys).doesNotContain(cb)
        assertThat(codeberg.tokensSeen).isEmpty()
    }
}
