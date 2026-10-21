package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

interface TrendingApi {
    val forge: ForgeInstance

    /** Public: works without an account. The order is the forge's ranking and must be kept. */
    suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>>
}

/** Whether the account stars a repository, and watches it; null where that couldn't be told. */
data class RepoViewer(val starred: Boolean?, val watching: Boolean?)

interface StarApi {
    /** Repos missing from the result could not be resolved (deleted, renamed, private). */
    suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>>

    suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit>

    /** Whether the account is told of everything that happens in [repo]. */
    suspend fun isWatching(token: String, repo: RepoId): ForgeResult<Boolean> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Watches [repo], or goes back to being told only of what involves the account. */
    suspend fun setWatching(token: String, repo: RepoId, watching: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether the account stars [repo] and watches it, asked side by side; in one request where the forge can answer both. */
    suspend fun viewer(token: String, repo: RepoId): RepoViewer = coroutineScope {
        val starred = async { (starredStatus(token, listOf(repo)) as? ForgeResult.Success)?.value?.get(repo) }
        val watching = async { (isWatching(token, repo) as? ForgeResult.Success)?.value }
        RepoViewer(starred.await(), watching.await())
    }

    /**
     * Copies [repo] to the account's own space and says where the copy is. Forges answer before the copy is filled:
     * it may be empty for a moment. An account that already has a fork is given that one.
     */
    suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> = ForgeResult.Failure(ForgeError.Unsupported)
}
