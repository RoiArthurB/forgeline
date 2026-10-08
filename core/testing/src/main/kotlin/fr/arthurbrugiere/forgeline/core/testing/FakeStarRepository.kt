package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.star.StarRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.CompletableDeferred

class FakeStarRepository : StarRepository {
    val starred = mutableSetOf<RepoId>()
    var signedIn = true
    var setFailure: ForgeError? = null
    val lookups = mutableListOf<List<RepoId>>()

    /** When set, setStarred suspends until it completes: lets tests observe in-flight state. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun starredStatus(repos: List<RepoId>): Map<RepoId, Boolean> {
        lookups += repos
        return if (signedIn) repos.associateWith { it in starred } else emptyMap()
    }

    override suspend fun setStarred(repo: RepoId, starred: Boolean): ForgeResult<Unit> {
        gate?.await()
        setFailure?.let { return ForgeResult.Failure(it) }
        if (starred) this.starred += repo else this.starred -= repo
        return ForgeResult.Success(Unit)
    }

    val watched = mutableSetOf<RepoId>()

    /** Null where the forge can't say whether a repository is watched. */
    var watchKnown = true

    /** Where a fork lands; null refuses it. */
    var forkedTo: RepoId? = null
    var forkFailure: ForgeError = ForgeError.Http(403, null)
    val forks = mutableListOf<RepoId>()

    override suspend fun isWatching(repo: RepoId): Boolean? = if (signedIn && watchKnown) repo in watched else null

    override suspend fun setWatching(repo: RepoId, watching: Boolean): ForgeResult<Unit> {
        gate?.await()
        setFailure?.let { return ForgeResult.Failure(it) }
        if (watching) watched += repo else watched -= repo
        return ForgeResult.Success(Unit)
    }

    override suspend fun fork(repo: RepoId): ForgeResult<RepoId> {
        gate?.await()
        forks += repo
        return forkedTo?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(forkFailure)
    }
}
