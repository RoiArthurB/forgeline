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
}
