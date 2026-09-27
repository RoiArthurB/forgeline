package fr.arthurbrugiere.forgeline.core.data.user

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

interface UserRepository {
    fun cachedUser(login: String): UserProfile?

    suspend fun user(login: String): ForgeResult<UserProfile>

    suspend fun repos(login: String): ForgeResult<List<RepoSummary>>

    suspend fun starred(login: String): ForgeResult<List<RepoSummary>>

    /** Null when unknown: signed out, the lookup failed, or [login] is the signed-in user. */
    suspend fun isFollowing(login: String): Boolean?

    suspend fun setFollowing(login: String, follow: Boolean): ForgeResult<Unit>
}

@Singleton
class DefaultUserRepository @Inject constructor(
    private val api: UserApi,
    private val accounts: AccountRepository,
) : UserRepository {

    private val profiles = object : LinkedHashMap<String, UserProfile>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UserProfile>) = size > CACHE_SIZE
    }

    // Logins are case-insensitive on every supported forge.
    override fun cachedUser(login: String): UserProfile? = synchronized(profiles) { profiles[login.lowercase()] }

    override suspend fun user(login: String): ForgeResult<UserProfile> = api.user(token(), login).also { result ->
        if (result is ForgeResult.Success) synchronized(profiles) { profiles[login.lowercase()] = result.value }
    }

    override suspend fun repos(login: String) = api.repos(token(), login)

    override suspend fun starred(login: String) = api.starred(token(), login)

    override suspend fun isFollowing(login: String): Boolean? {
        val account = accounts.activeAccount.first() ?: return null
        if (account.user.login.equals(login, ignoreCase = true)) return null
        val token = accounts.token(account.id) ?: return null
        return (api.isFollowing(token, login) as? ForgeResult.Success)?.value
    }

    override suspend fun setFollowing(login: String, follow: Boolean): ForgeResult<Unit> {
        val token = token() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return api.setFollowing(token, login, follow)
    }

    private suspend fun token(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }

    private companion object {
        const val CACHE_SIZE = 50
    }
}
