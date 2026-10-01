package fr.arthurbrugiere.forgeline.core.data.account

import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Instant
import java.time.Clock
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
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

    private var now = 1_000_000L
    private val clock = object : Clock() {
        override fun instant(): Instant = Instant.ofEpochMilli(now)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }
    private val refreshes = mutableListOf<String>()
    private var refreshAnswer: ForgeResult<OAuthTokens> = ForgeResult.Success(OAuthTokens("at-2", "rt-2", 3600))
    private val refresher = TokenRefresher { forge, refreshToken ->
        refreshes += "${forge.host}:$refreshToken"
        refreshAnswer
    }

    private val octocat = ForgeUser(login = "octocat", name = "The Octocat", avatarUrl = "https://example.com/a.png")

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) {
            tmp.newFile("accounts.preferences_pb").also { it.delete() }
        }

    @Test
    fun starts_signed_out() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)

        assertThat(repository.activeAccount.first()).isNull()
    }

    @Test
    fun sign_in_activates_the_account_and_keeps_its_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)

        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        assertThat(account).isEqualTo(Account("github:github.com:octocat", ForgeInstance.GitHub, octocat))
        assertThat(repository.activeAccount.first()).isEqualTo(account)
        assertThat(repository.token(account.id)).isEqualTo("ghp_secret")
    }

    /** Counts decryptions: on a phone each one is a round trip to the Keystore. */
    private class CountingCipher : TokenCipher {
        var decryptions = 0

        override fun encrypt(plaintext: String) = ReversingCipher.encrypt(plaintext)

        override fun decrypt(ciphertext: String): String {
            decryptions++
            return ReversingCipher.decrypt(ciphertext)
        }
    }

    @Test
    fun a_token_is_decrypted_once_not_for_every_request() = runTest {
        // Regression: every forge request went through the Keystore to read the same token again.
        val cipher = CountingCipher()
        val repository = DataStoreAccountRepository(dataStore(), cipher, refresher, clock)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        repeat(5) { assertThat(repository.token(account.id)).isEqualTo("ghp_secret") }

        assertThat(cipher.decryptions).isAtMost(1)
    }

    @Test
    fun signing_in_again_reads_the_new_token_not_the_one_remembered() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), CountingCipher(), refresher, clock)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_old")
        repository.token(account.id)

        repository.signIn(ForgeInstance.GitHub, octocat, "ghp_new")

        assertThat(repository.token(account.id)).isEqualTo("ghp_new")
    }

    @Test
    fun a_signed_out_account_s_token_is_no_longer_remembered() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), CountingCipher(), refresher, clock)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")
        repository.token(account.id)

        repository.signOut(account.id)

        assertThat(repository.token(account.id)).isNull()
        assertThat(repository.rememberedTokens).isEqualTo(0)
    }

    @Test
    fun tokens_are_stored_encrypted() = runTest {
        val store = dataStore()
        DataStoreAccountRepository(store, ReversingCipher, refresher, clock).signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        val raw = store.data.first().asMap().values.joinToString()
        assertThat(raw).doesNotContain("ghp_secret")
        assertThat(raw).contains("terces_phg")
    }

    @Test
    fun signing_in_again_updates_the_account_instead_of_duplicating_it() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)
        repository.signIn(ForgeInstance.GitHub, octocat, "old")

        val renamed = octocat.copy(name = "Mona")
        val account = repository.signIn(ForgeInstance.GitHub, renamed, "new")

        assertThat(repository.accounts.first()).containsExactly(account)
        assertThat(account.user.name).isEqualTo("Mona")
        assertThat(repository.token(account.id)).isEqualTo("new")
    }

    @Test
    fun sign_out_forgets_the_account_and_its_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)
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
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)
        val first = repository.signIn(ForgeInstance.GitHub, octocat, "a")
        val second = repository.signIn(ForgeInstance.GitHub, ForgeUser("hubot", null, null), "b")

        repository.signOut(second.id)

        assertThat(repository.activeAccount.first()).isEqualTo(first)
    }

    @Test
    fun an_undecryptable_token_reads_as_missing() = runTest {
        // Happens when the Keystore key is gone, e.g. after restoring data onto a new device.
        val store = dataStore()
        val account = DataStoreAccountRepository(store, ReversingCipher, refresher, clock).signIn(ForgeInstance.GitHub, octocat, "x")

        val repository = DataStoreAccountRepository(store, BrokenCipher, refresher, clock)

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

    @Test
    fun an_expiring_token_is_refreshed_once_and_kept() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now + 3_600_000)

        assertThat(repository.token(account.id)).isEqualTo("at-1")
        assertThat(refreshes).isEmpty()

        // Within a minute of expiring: refreshed, and the new refresh token replaces the old one.
        now += 3_600_000 - 30_000
        assertThat(repository.token(account.id)).isEqualTo("at-2")
        assertThat(repository.token(account.id)).isEqualTo("at-2")
        assertThat(refreshes).containsExactly("codeberg.org:rt-1")

        now += 3_600_000
        refreshAnswer = ForgeResult.Success(OAuthTokens("at-3", null, 3600))
        assertThat(repository.token(account.id)).isEqualTo("at-3")
        assertThat(refreshes.last()).isEqualTo("codeberg.org:rt-2")
    }

    @Test
    fun a_token_that_never_expires_is_never_refreshed() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_1")

        now += 365L * 24 * 3_600_000
        assertThat(repository.token(account.id)).isEqualTo("ghp_1")
        assertThat(refreshes).isEmpty()
    }

    @Test
    fun a_failed_refresh_hands_back_the_old_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)
        refreshAnswer = ForgeResult.Failure(ForgeError.Network)

        assertThat(repository.token(account.id)).isEqualTo("at-1")
    }
}
