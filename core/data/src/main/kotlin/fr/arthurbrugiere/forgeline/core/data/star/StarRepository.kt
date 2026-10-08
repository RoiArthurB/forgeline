package fr.arthurbrugiere.forgeline.core.data.star

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.first
import javax.inject.Inject

interface StarRepository {
    /** Known starred states; repos missing from the map are unknown (signed out or lookup failed). */
    suspend fun starredStatus(repos: List<RepoId>): Map<RepoId, Boolean>

    suspend fun setStarred(repo: RepoId, starred: Boolean): ForgeResult<Unit>

    /** Whether the account on [repo]'s forge watches it; null when unknown (signed out, lookup failed, no such thing there). */
    suspend fun isWatching(repo: RepoId): Boolean?

    suspend fun setWatching(repo: RepoId, watching: Boolean): ForgeResult<Unit>

    /** Copies [repo] to the account's own space and says where the copy is. */
    suspend fun fork(repo: RepoId): ForgeResult<RepoId>
}

class DefaultStarRepository @Inject constructor(
    private val accounts: AccountRepository,
    private val clients: ForgeClients,
) : StarRepository {

    override suspend fun starredStatus(repos: List<RepoId>): Map<RepoId, Boolean> {
        // Each forge answers for its own repositories, with the account signed in there.
        return repos.groupBy { it.forge }.flatMap { (forge, ids) -> starredOn(forge, ids).entries }.associate { it.key to it.value }
    }

    private suspend fun starredOn(forge: ForgeInstance, repos: List<RepoId>): Map<RepoId, Boolean> {
        val token = accounts.tokenOn(forge) ?: return emptyMap()
        return when (val result = clients.stars(forge).starredStatus(token, repos)) {
            is ForgeResult.Success -> result.value
            is ForgeResult.Failure -> emptyMap()
        }
    }

    override suspend fun setStarred(repo: RepoId, starred: Boolean): ForgeResult<Unit> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.stars(repo.forge).setStarred(token, repo, starred)
    }

    override suspend fun isWatching(repo: RepoId): Boolean? {
        val token = accounts.tokenOn(repo.forge) ?: return null
        return (clients.stars(repo.forge).isWatching(token, repo) as? ForgeResult.Success)?.value
    }

    override suspend fun setWatching(repo: RepoId, watching: Boolean): ForgeResult<Unit> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.stars(repo.forge).setWatching(token, repo, watching)
    }

    override suspend fun fork(repo: RepoId): ForgeResult<RepoId> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.stars(repo.forge).fork(token, repo)
    }

}
