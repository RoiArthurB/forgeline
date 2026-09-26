package fr.arthurbrugiere.forgeline.core.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreAccountRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val octocat = ForgeUser(login = "octocat", name = "The Octocat", avatarUrl = "https://example.com/a.png")

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            tmp.newFile("accounts.preferences_pb").also { it.delete() }
        }

    @Test
    fun starts_signed_out() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher)

        assertThat(repository.activeAccount.first()).isNull()
    }

    @Test
    fun sign_in_activates_the_account_and_keeps_its_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher)

        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        assertThat(account).isEqualTo(Account("github:github.com:octocat", ForgeInstance.GitHub, octocat))
        assertThat(repository.activeAccount.first()).isEqualTo(account)
        assertThat(repository.token(account.id)).isEqualTo("ghp_secret")
    }

    @Test
    fun tokens_are_stored_encrypted() = runTest {
        val store = dataStore()
        DataStoreAccountRepository(store, ReversingCipher).signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        val raw = store.data.first().asMap().values.joinToString()
        assertThat(raw).doesNotContain("ghp_secret")
        assertThat(raw).contains("terces_phg")
    }

    @Test
    fun signing_in_again_updates_the_account_instead_of_duplicating_it() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher)
        repository.signIn(ForgeInstance.GitHub, octocat, "old")

        val renamed = octocat.copy(name = "Mona")
        val account = repository.signIn(ForgeInstance.GitHub, renamed, "new")

        assertThat(repository.accounts.first()).containsExactly(account)
        assertThat(account.user.name).isEqualTo("Mona")
        assertThat(repository.token(account.id)).isEqualTo("new")
    }

    @Test
    fun sign_out_forgets_the_account_and_its_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        repository.activeAccount.test {
            assertThat(awaitItem()).isEqualTo(account)
            repository.signOut(account.id)
            assertThat(awaitItem()).isNull()
        }
        assertThat(repository.token(account.id)).isNull()
    }

    @Test
    fun signing_out_the_active_account_falls_back_to_another_one() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher)
        val first = repository.signIn(ForgeInstance.GitHub, octocat, "a")
        val second = repository.signIn(ForgeInstance.GitHub, ForgeUser("hubot", null, null), "b")

        repository.signOut(second.id)

        assertThat(repository.activeAccount.first()).isEqualTo(first)
    }

    @Test
    fun an_undecryptable_token_reads_as_missing() = runTest {
        // Happens when the Keystore key is gone, e.g. after restoring data onto a new device.
        val store = dataStore()
        val account = DataStoreAccountRepository(store, ReversingCipher).signIn(ForgeInstance.GitHub, octocat, "x")

        val repository = DataStoreAccountRepository(store, BrokenCipher)

        assertThat(repository.token(account.id)).isNull()
    }

    private object ReversingCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext.reversed()

        override fun decrypt(ciphertext: String) = ciphertext.reversed()
    }

    private object BrokenCipher : TokenCipher {
        override fun encrypt(plaintext: String) = error("no key")

        override fun decrypt(ciphertext: String) = throw java.security.GeneralSecurityException("key invalidated")
    }
}
