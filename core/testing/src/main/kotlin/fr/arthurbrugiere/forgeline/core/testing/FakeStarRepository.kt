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
}
