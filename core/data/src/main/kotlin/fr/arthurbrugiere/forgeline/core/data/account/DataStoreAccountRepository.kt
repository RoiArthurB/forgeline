package fr.arthurbrugiere.forgeline.core.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.arthurbrugiere.forgeline.core.data.di.AccountsDataStore
import fr.arthurbrugiere.forgeline.core.data.di.BackgroundScope
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Asks a forge for a new access token. */
fun interface TokenRefresher {
    suspend fun refresh(forge: ForgeInstance, refreshToken: String): ForgeResult<OAuthTokens>
}

class ForgeTokenRefresher @Inject constructor(private val clients: ForgeClients) : TokenRefresher {
    override suspend fun refresh(forge: ForgeInstance, refreshToken: String) = clients.auth(forge).refresh(refreshToken)
}

@Singleton
class DataStoreAccountRepository @Inject constructor(
    @param:AccountsDataStore private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val refresher: TokenRefresher,
    private val clock: Clock,
    /** Renewing a token goes on in here, whoever asked for it: see [token]. */
    @param:BackgroundScope private val scope: CoroutineScope,
) : AccountRepository {

    private val refreshing = Mutex()

    private val ended = MutableStateFlow<Set<String>>(emptySet())

    override val signInEnded: Flow<Set<String>> = ended.asStateFlow()

    private val state: Flow<StoredState> = dataStore.data.map { it.toState() }

    override val accounts: Flow<List<Account>> = state
        .map { stored -> stored.accounts.map { it.toAccount() } }
        .distinctUntilChanged()

    override val activeAccount: Flow<Account?> = state
        .map { stored -> stored.accounts.firstOrNull { it.id == stored.activeId }?.toAccount() }
        .distinctUntilChanged()

    override suspend fun signIn(forge: ForgeInstance, user: ForgeUser, token: String, refreshToken: String?, expiresAtMillis: Long?): Account {
        val stored = StoredAccount(
            id = Account.idFor(forge, user.login),
            forgeType = forge.type.name,
            host = forge.host,
            login = user.login,
            name = user.name,
            avatarUrl = user.avatarUrl,
            encryptedToken = cipher.encrypt(token),
            encryptedRefreshToken = refreshToken?.let(cipher::encrypt),
            expiresAtMillis = expiresAtMillis,
        )
        dataStore.edit { prefs ->
            val current = prefs.toState()
            prefs.write(StoredState(current.accounts.filterNot { it.id == stored.id } + stored, stored.id))
        }
        ended.update { it - stored.id }
        return stored.toAccount()
    }

    override suspend fun token(accountId: String): String? {
        val stored = state.first().accounts.firstOrNull { it.id == accountId } ?: return null
        if (!stored.expiresSoon()) return decrypt(stored.encryptedToken)
        // Renewed apart from whoever asked: a forge spends a refresh token once, so a call cancelled after the forge
        // answered (a screen left mid-refresh) would lose the new tokens and the next call would spend the old one
        // again, refused. Started from here, the renewal runs to its end and stores what it got.
        return scope.async { refreshing.withLock { renew(accountId) } }.await()
    }

    override fun markSignInEnded(accountId: String) = ended.update { it + accountId }

    /** One renewal at a time: two calls racing would spend the refresh token twice. */
    private suspend fun renew(accountId: String): String? {
        val current = state.first().accounts.firstOrNull { it.id == accountId } ?: return null
        // Renewed meanwhile by the call that held the lock before this one.
        if (!current.expiresSoon()) return decrypt(current.encryptedToken)
        val refreshToken = current.encryptedRefreshToken?.let(::decrypt)
        if (refreshToken == null) {
            // Nothing to renew it with: once it has really expired, it is over.
            if (clock.millis() >= (current.expiresAtMillis ?: Long.MAX_VALUE)) ended.update { it + accountId }
            return decrypt(current.encryptedToken)
        }
        return when (val result = refresher.refresh(current.toAccount().forge, refreshToken)) {
            is ForgeResult.Failure -> {
                // Refused (a refresh token spent or revoked) is over; offline or a busy forge is for another time.
                // Either way hand back what there is: the forge will say if it no longer works.
                val error = result.error
                if (error == ForgeError.Unauthorized || (error is ForgeError.Http && error.status in 400..401)) ended.update { it + accountId }
                decrypt(current.encryptedToken)
            }
            is ForgeResult.Success -> {
                val tokens = result.value
                val renewed = current.copy(
                    encryptedToken = cipher.encrypt(tokens.accessToken),
                    // Forgejo rotates refresh tokens; keep the old one only if none came back.
                    encryptedRefreshToken = tokens.refreshToken?.let(cipher::encrypt) ?: current.encryptedRefreshToken,
                    expiresAtMillis = tokens.expiresInSeconds?.let { clock.millis() + it * 1_000 },
                )
                dataStore.edit { prefs ->
                    val latest = prefs.toState()
                    prefs.write(latest.copy(accounts = latest.accounts.map { if (it.id == accountId) renewed else it }))
                }
                // The token it replaces is no longer worth remembering.
                decrypted.remove(current.encryptedToken)
                ended.update { it - accountId }
                tokens.accessToken
            }
        }
    }

    private fun StoredAccount.expiresSoon(): Boolean = expiresAtMillis != null && clock.millis() >= expiresAtMillis - REFRESH_EARLY_MILLIS

    /**
     * Tokens already decrypted, by what is stored: a renewed or replaced token is stored differently, so it is never
     * answered from here. Regression: every forge request went through the Keystore to read the same token again.
     */
    private val decrypted = ConcurrentHashMap<String, String>()

    internal val rememberedTokens: Int get() = decrypted.size

    private fun decrypt(value: String): String? = decrypted[value] ?: readEncrypted(value)?.also { decrypted[value] = it }

    private fun readEncrypted(value: String): String? = try {
        cipher.decrypt(value)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    override suspend fun signOut(accountId: String) {
        dataStore.edit { prefs ->
            val current = prefs.toState()
            val remaining = current.accounts.filterNot { it.id == accountId }
            val activeId = if (current.activeId == accountId) remaining.lastOrNull()?.id else current.activeId
            prefs.write(StoredState(remaining, activeId))
        }
        ended.update { it - accountId }
        // Nothing of the account stays in memory; the others' tokens are read again when next needed.
        decrypted.clear()
    }

    private fun Preferences.toState(): StoredState = StoredState(
        accounts = this[ACCOUNTS]?.let { json.decodeFromString<List<StoredAccount>>(it) }.orEmpty(),
        activeId = this[ACTIVE_ID],
    )

    private fun androidx.datastore.preferences.core.MutablePreferences.write(state: StoredState) {
        this[ACCOUNTS] = json.encodeToString(state.accounts)
        if (state.activeId == null) remove(ACTIVE_ID) else this[ACTIVE_ID] = state.activeId
    }

    private data class StoredState(val accounts: List<StoredAccount>, val activeId: String?)

    @Serializable
    private data class StoredAccount(
        val id: String,
        val forgeType: String,
        val host: String,
        val login: String,
        val name: String?,
        val avatarUrl: String?,
        val encryptedToken: String,
        val encryptedRefreshToken: String? = null,
        val expiresAtMillis: Long? = null,
    ) {
        fun toAccount() = Account(
            id = id,
            forge = ForgeInstance(ForgeType.valueOf(forgeType), host),
            user = ForgeUser(login = login, name = name, avatarUrl = avatarUrl),
        )
    }

    private companion object {
        val ACCOUNTS = stringPreferencesKey("accounts")
        val ACTIVE_ID = stringPreferencesKey("active_account_id")
        val json = Json { ignoreUnknownKeys = true }

        /** Refreshed a minute early, so a token doesn't expire on its way to the forge. */
        const val REFRESH_EARLY_MILLIS = 60_000L
    }
}
