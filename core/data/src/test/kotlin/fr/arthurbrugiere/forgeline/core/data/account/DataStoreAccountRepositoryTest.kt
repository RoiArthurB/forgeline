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
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)

        assertThat(repository.activeAccount.first()).isNull()
    }

    @Test
    fun sign_in_activates_the_account_and_keeps_its_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)

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
        val repository = DataStoreAccountRepository(dataStore(), cipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        repeat(5) { assertThat(repository.token(account.id)).isEqualTo("ghp_secret") }

        assertThat(cipher.decryptions).isAtMost(1)
    }

    @Test
    fun signing_in_again_reads_the_new_token_not_the_one_remembered() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), CountingCipher(), refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_old")
        repository.token(account.id)

        repository.signIn(ForgeInstance.GitHub, octocat, "ghp_new")

        assertThat(repository.token(account.id)).isEqualTo("ghp_new")
    }

    @Test
    fun a_signed_out_account_s_token_is_no_longer_remembered() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), CountingCipher(), refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_secret")
        repository.token(account.id)

        repository.signOut(account.id)

        assertThat(repository.token(account.id)).isNull()
        assertThat(repository.rememberedTokens).isEqualTo(0)
    }

    @Test
    fun tokens_are_stored_encrypted() = runTest {
        val store = dataStore()
        DataStoreAccountRepository(store, ReversingCipher, refresher, clock, backgroundScope).signIn(ForgeInstance.GitHub, octocat, "ghp_secret")

        val raw = store.data.first().asMap().values.joinToString()
        assertThat(raw).doesNotContain("ghp_secret")
        assertThat(raw).contains("terces_phg")
    }

    @Test
    fun signing_in_again_updates_the_account_instead_of_duplicating_it() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        repository.signIn(ForgeInstance.GitHub, octocat, "old")

        val renamed = octocat.copy(name = "Mona")
        val account = repository.signIn(ForgeInstance.GitHub, renamed, "new")

        assertThat(repository.accounts.first()).containsExactly(account)
        assertThat(account.user.name).isEqualTo("Mona")
        assertThat(repository.token(account.id)).isEqualTo("new")
    }

    @Test
    fun sign_out_forgets_the_account_and_its_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
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
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val first = repository.signIn(ForgeInstance.GitHub, octocat, "a")
        val second = repository.signIn(ForgeInstance.GitHub, ForgeUser("hubot", null, null), "b")

        repository.signOut(second.id)

        assertThat(repository.activeAccount.first()).isEqualTo(first)
    }

    @Test
    fun an_undecryptable_token_reads_as_missing() = runTest {
        // Happens when the Keystore key is gone, e.g. after restoring data onto a new device.
        val store = dataStore()
        val account = DataStoreAccountRepository(store, ReversingCipher, refresher, clock, backgroundScope).signIn(ForgeInstance.GitHub, octocat, "x")

        val repository = DataStoreAccountRepository(store, BrokenCipher, refresher, clock, backgroundScope)

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
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
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
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.GitHub, octocat, "ghp_1")

        now += 365L * 24 * 3_600_000
        assertThat(repository.token(account.id)).isEqualTo("ghp_1")
        assertThat(refreshes).isEmpty()
    }

    @Test
    fun a_failed_refresh_hands_back_the_old_token() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)
        refreshAnswer = ForgeResult.Failure(ForgeError.Network)

        assertThat(repository.token(account.id)).isEqualTo("at-1")
    }

    @Test
    fun a_caller_that_goes_away_while_the_forge_renews_the_token_doesnt_lose_what_it_answers() = runTest {
        // Regression: Codeberg's refresh tokens work once. A screen left mid-refresh cancelled the call after the
        // forge had answered, the new tokens were never stored, and the next call spent the old refresh token again:
        // refused, so the account was left with an expired token for good.
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = TokenRefresher { forge, refreshToken ->
            refreshes += "${forge.host}:$refreshToken"
            answered.await()
            refreshAnswer
        }
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, slow, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)
        val caller = launch { repository.token(account.id) }
        runCurrent()

        caller.cancel()
        answered.complete(Unit)
        advanceUntilIdle()

        assertThat(repository.token(account.id)).isEqualTo("at-2")
        assertThat(refreshes).containsExactly("codeberg.org:rt-1")
    }

    @Test
    fun calls_arriving_together_share_one_refresh() = runTest {
        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = TokenRefresher { forge, refreshToken ->
            refreshes += "${forge.host}:$refreshToken"
            answered.await()
            refreshAnswer
        }
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, slow, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)

        // What every screen and the background sync do when the app opens after a night.
        val tokens = (1..6).map { async { repository.token(account.id) } }
        runCurrent()
        answered.complete(Unit)
        advanceUntilIdle()

        assertThat(tokens.map { it.await() }).containsExactly("at-2", "at-2", "at-2", "at-2", "at-2", "at-2")
        assertThat(refreshes).containsExactly("codeberg.org:rt-1")
    }

    @Test
    fun a_refresh_token_the_forge_refuses_ends_the_sign_in() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)
        assertThat(repository.signInEnded.first()).isEmpty()
        // Codeberg's answer to a refresh token already spent: 400, "token was already used".
        refreshAnswer = ForgeResult.Failure(ForgeError.Http(400, "token was already used"))

        // The old token is handed back, the forge will say it no longer works; but the app now knows.
        assertThat(repository.token(account.id)).isEqualTo("at-1")

        assertThat(repository.signInEnded.first()).containsExactly(account.id)
    }

    @Test
    fun a_forge_that_cannot_be_reached_or_is_busy_does_not_end_the_sign_in() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)

        listOf(ForgeError.Network, ForgeError.RateLimited(null), ForgeError.Http(503, "busy"), ForgeError.Unreadable).forEach { error ->
            refreshAnswer = ForgeResult.Failure(error)
            repository.token(account.id)
        }

        assertThat(repository.signInEnded.first()).isEmpty()
    }

    @Test
    fun an_expired_token_with_nothing_to_renew_it_has_ended_but_not_one_about_to_expire() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", expiresAtMillis = now + 30_000)

        assertThat(repository.token(account.id)).isEqualTo("at-1")
        assertThat(repository.signInEnded.first()).isEmpty()

        now += 60_000
        assertThat(repository.token(account.id)).isEqualTo("at-1")
        assertThat(repository.signInEnded.first()).containsExactly(account.id)
        assertThat(refreshes).isEmpty()
    }

    @Test
    fun signing_in_again_or_a_renewal_that_works_or_signing_out_clears_it() = runTest {
        val repository = DataStoreAccountRepository(dataStore(), ReversingCipher, refresher, clock, backgroundScope)
        val account = repository.signIn(ForgeInstance.Codeberg, octocat, "at-1", refreshToken = "rt-1", expiresAtMillis = now)
        refreshAnswer = ForgeResult.Failure(ForgeError.Http(400, "token was already used"))
        repository.token(account.id)
        assertThat(repository.signInEnded.first()).containsExactly(account.id)

        repository.signIn(ForgeInstance.Codeberg, octocat, "at-9", refreshToken = "rt-9", expiresAtMillis = now + 3_600_000)
        assertThat(repository.signInEnded.first()).isEmpty()

        // A renewal that works after an earlier refusal (a refusal can be a hiccup).
        now += 3_600_000
        repository.token(account.id)
        assertThat(repository.signInEnded.first()).containsExactly(account.id)
        refreshAnswer = ForgeResult.Success(OAuthTokens("at-2", "rt-2", 3600))
        assertThat(repository.token(account.id)).isEqualTo("at-2")
        assertThat(repository.signInEnded.first()).isEmpty()

        refreshAnswer = ForgeResult.Failure(ForgeError.Http(401, "no"))
        now += 3_600_000
        repository.token(account.id)
        assertThat(repository.signInEnded.first()).containsExactly(account.id)
        repository.signOut(account.id)
        assertThat(repository.signInEnded.first()).isEmpty()
    }
}
