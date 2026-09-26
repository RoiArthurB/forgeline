package fr.arthurbrugiere.forgeline.core.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.arthurbrugiere.forgeline.core.data.di.AccountsDataStore
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import javax.inject.Inject

class DataStoreAccountRepository @Inject constructor(
    @param:AccountsDataStore private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : AccountRepository {

    private val state: Flow<StoredState> = dataStore.data.map { it.toState() }

    override val accounts: Flow<List<Account>> = state
        .map { stored -> stored.accounts.map { it.toAccount() } }
        .distinctUntilChanged()

    override val activeAccount: Flow<Account?> = state
        .map { stored -> stored.accounts.firstOrNull { it.id == stored.activeId }?.toAccount() }
        .distinctUntilChanged()

    override suspend fun signIn(forge: ForgeInstance, user: ForgeUser, token: String): Account {
        val stored = StoredAccount(
            id = Account.idFor(forge, user.login),
            forgeType = forge.type.name,
            host = forge.host,
            login = user.login,
            name = user.name,
            avatarUrl = user.avatarUrl,
            encryptedToken = cipher.encrypt(token),
        )
        dataStore.edit { prefs ->
            val current = prefs.toState()
            prefs.write(StoredState(current.accounts.filterNot { it.id == stored.id } + stored, stored.id))
        }
        return stored.toAccount()
    }

    override suspend fun token(accountId: String): String? {
        val stored = state.first().accounts.firstOrNull { it.id == accountId } ?: return null
        return try {
            cipher.decrypt(stored.encryptedToken)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun signOut(accountId: String) {
        dataStore.edit { prefs ->
            val current = prefs.toState()
            val remaining = current.accounts.filterNot { it.id == accountId }
            val activeId = if (current.activeId == accountId) remaining.lastOrNull()?.id else current.activeId
            prefs.write(StoredState(remaining, activeId))
        }
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
    }
}
