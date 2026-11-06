package fr.arthurbrugiere.forgeline.core.data.account

import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    val accounts: Flow<List<Account>>

    val activeAccount: Flow<Account?>

    /**
     * The ids of the accounts chosen to act on their forge, where several are signed in to one: at most one a forge.
     * Signing in to an account makes it the one on its forge; [actAs] picks another.
     */
    val acting: Flow<Set<String>>

    /** Makes [accountId] the account that reads and writes on its forge, in place of any other signed in there. */
    suspend fun actAs(accountId: String)

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

    /**
     * The ids of accounts whose sign-in has ended: the forge refused to renew it (its refresh token was spent or
     * revoked), or it expired with nothing to renew it. Their requests are refused until the person signs in again.
     * Learned when a token is asked for, so not before the first request of a launch.
     */
    val signInEnded: Flow<Set<String>>

    /**
     * A forge refused the token it was given for [accountId] (401): the sign-in is over, whatever the app thought of
     * its expiry (revoked on the forge's site, say). Cleared by signing in again.
     */
    fun markSignInEnded(accountId: String)
}
