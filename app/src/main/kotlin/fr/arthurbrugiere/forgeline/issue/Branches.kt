package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.RepoId
import javax.inject.Inject

/** The branches of a repository: what a pull request can be pointed at. */
class Branches(private val list: suspend (RepoId) -> ForgeResult<List<String>>) {
    @Inject
    constructor(repos: RepoRepository) : this({ repo -> repos.refs(repo).map { it.branches } })

    /** None: for tests of what has nothing to do with branches. */
    constructor() : this({ ForgeResult.Success(emptyList()) })

    suspend operator fun invoke(repo: RepoId): ForgeResult<List<String>> = list(repo)
}
