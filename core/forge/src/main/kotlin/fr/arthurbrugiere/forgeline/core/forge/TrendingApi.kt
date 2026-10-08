package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TrendingPeriod
import fr.arthurbrugiere.forgeline.core.model.TrendingRepo

interface TrendingApi {
    val forge: ForgeInstance

    /** Public: works without an account. The order is the forge's ranking and must be kept. */
    suspend fun trending(period: TrendingPeriod): ForgeResult<List<TrendingRepo>>
}

interface StarApi {
    /** Repos missing from the result could not be resolved (deleted, renamed, private). */
    suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>>

    suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit>

    /** Whether the account is told of everything that happens in [repo]. */
    suspend fun isWatching(token: String, repo: RepoId): ForgeResult<Boolean> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Watches [repo], or goes back to being told only of what involves the account. */
    suspend fun setWatching(token: String, repo: RepoId, watching: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /**
     * Copies [repo] to the account's own space and says where the copy is. Forges answer before the copy is filled:
     * it may be empty for a moment. An account that already has a fork is given that one.
     */
    suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> = ForgeResult.Failure(ForgeError.Unsupported)
}
