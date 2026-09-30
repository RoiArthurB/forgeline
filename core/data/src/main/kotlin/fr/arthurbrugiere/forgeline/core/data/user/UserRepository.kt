package fr.arthurbrugiere.forgeline.core.data.user

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.data.account.accountOn
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface UserRepository {
    /** The last loaded profile of [login] on [forge] in this session, for an instant reopen. */
    fun cachedUser(forge: ForgeInstance, login: String): UserProfile?

    suspend fun user(forge: ForgeInstance, login: String): ForgeResult<UserProfile>

    suspend fun repos(forge: ForgeInstance, login: String): ForgeResult<List<RepoSummary>>

    suspend fun starred(forge: ForgeInstance, login: String): ForgeResult<List<RepoSummary>>

    /** Null when unknown: signed out on [forge], the lookup failed, or [login] is the signed-in user there. */
    suspend fun isFollowing(forge: ForgeInstance, login: String): Boolean?

    suspend fun setFollowing(forge: ForgeInstance, login: String, follow: Boolean): ForgeResult<Unit>
}

@Singleton
class DefaultUserRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) : UserRepository {

    private val profiles = object : LinkedHashMap<String, UserProfile>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UserProfile>) = size > CACHE_SIZE
    }

    // Logins are case-insensitive on every supported forge, and the same login on two forges is two people.
    private fun key(forge: ForgeInstance, login: String) = "${forge.host}/${login.lowercase()}"

    override fun cachedUser(forge: ForgeInstance, login: String): UserProfile? = synchronized(profiles) { profiles[key(forge, login)] }

    override suspend fun user(forge: ForgeInstance, login: String): ForgeResult<UserProfile> =
        clients.users(forge).user(accounts.tokenOn(forge), login).also { result ->
            if (result is ForgeResult.Success) synchronized(profiles) { profiles[key(forge, login)] = result.value }
        }

    override suspend fun repos(forge: ForgeInstance, login: String) = clients.users(forge).repos(accounts.tokenOn(forge), login)

    override suspend fun starred(forge: ForgeInstance, login: String) = clients.users(forge).starred(accounts.tokenOn(forge), login)

    override suspend fun isFollowing(forge: ForgeInstance, login: String): Boolean? {
        val account = accounts.accountOn(forge) ?: return null
        if (account.user.login.equals(login, ignoreCase = true)) return null
        val token = accounts.token(account.id) ?: return null
        return (clients.users(forge).isFollowing(token, login) as? ForgeResult.Success)?.value
    }

    override suspend fun setFollowing(forge: ForgeInstance, login: String, follow: Boolean): ForgeResult<Unit> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.users(forge).setFollowing(token, login, follow)
    }

    private companion object {
        const val CACHE_SIZE = 50
    }
}
