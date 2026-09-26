package fr.arthurbrugiere.forgeline.core.data.star

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.first
import javax.inject.Inject

interface StarRepository {
    /** Known starred states; repos missing from the map are unknown (signed out or lookup failed). */
    suspend fun starredStatus(repos: List<RepoId>): Map<RepoId, Boolean>

    suspend fun setStarred(repo: RepoId, starred: Boolean): ForgeResult<Unit>
}

class DefaultStarRepository @Inject constructor(
    private val accounts: AccountRepository,
    private val api: StarApi,
) : StarRepository {

    override suspend fun starredStatus(repos: List<RepoId>): Map<RepoId, Boolean> {
        val token = activeToken() ?: return emptyMap()
        return when (val result = api.starredStatus(token, repos)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> emptyMap()
        }
    }

    override suspend fun setStarred(repo: RepoId, starred: Boolean): ForgeResult<Unit> {
        val token = activeToken() ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return api.setStarred(token, repo, starred)
    }

    private suspend fun activeToken(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }
}
