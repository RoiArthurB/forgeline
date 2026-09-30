package fr.arthurbrugiere.forgeline.core.data.account

import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    val accounts: Flow<List<Account>>

    val activeAccount: Flow<Account?>

    /**
     * Stores (or refreshes) the account and makes it the active one. A token that expires (Codeberg's browser sign-in:
     * an hour) comes with its [refreshToken] and [expiresAtMillis].
     */
    suspend fun signIn(
        forge: ForgeInstance,
        user: ForgeUser,
        token: String,
        refreshToken: String? = null,
        expiresAtMillis: Long? = null,
    ): Account

    /**
     * The decrypted token, refreshed first when it is about to expire; null when the account is unknown or its token
     * can no longer be read.
     */
    suspend fun token(accountId: String): String?

    suspend fun signOut(accountId: String)
}
