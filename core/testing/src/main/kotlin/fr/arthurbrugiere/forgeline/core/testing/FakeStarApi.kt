package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.model.RepoId

class FakeStarApi : StarApi {
    val starred = mutableSetOf<RepoId>()
    val tokensSeen = mutableListOf<String>()
    var failure: ForgeError? = null

    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> {
        tokensSeen += token
        failure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(repos.associateWith { it in starred })
    }

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> {
        tokensSeen += token
        failure?.let { return ForgeResult.Failure(it) }
        if (starred) this.starred += repo else this.starred -= repo
        return ForgeResult.Success(Unit)
    }

    val watched = mutableSetOf<RepoId>()

    /** Where a fork lands; null refuses it with [failure], or as unsupported. */
    var forkedTo: RepoId? = null

    override suspend fun isWatching(token: String, repo: RepoId): ForgeResult<Boolean> {
        tokensSeen += token
        failure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(repo in watched)
    }

    override suspend fun setWatching(token: String, repo: RepoId, watching: Boolean): ForgeResult<Unit> {
        tokensSeen += token
        failure?.let { return ForgeResult.Failure(it) }
        if (watching) watched += repo else watched -= repo
        return ForgeResult.Success(Unit)
    }

    override suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> {
        tokensSeen += token
        failure?.let { return ForgeResult.Failure(it) }
        return forkedTo?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unsupported)
    }
}
