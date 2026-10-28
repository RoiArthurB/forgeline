package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.collections.CollectionsRepository
import fr.arthurbrugiere.forgeline.core.data.collections.ImportReport
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.model.RepoCollection
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Collections in memory: each a name and its repositories, the latest added first. */
class FakeCollectionsRepository : CollectionsRepository {
    private data class Held(val name: String, val repos: List<RepoSummary> = emptyList())

    private val held = MutableStateFlow<Map<String, Held>>(linkedMapOf())
    private var next = 1
    val imports = mutableListOf<Account>()
    var importAnswer: ForgeResult<ImportReport> = ForgeResult.Success(ImportReport(0, 0))

    override fun observeAll(): Flow<List<RepoCollection>> = held.map { all -> all.map { (id, it) -> RepoCollection(id, it.name, it.repos.size) } }

    override fun observeName(id: String) = held.map { it[id]?.name }

    override fun observeRepos(id: String) = held.map { it[id]?.repos.orEmpty() }

    override fun observeHolding(repo: RepoId) = held.map { all -> all.filterValues { it.repos.any { r -> r.id == repo } }.keys }

    override suspend fun create(name: String): String = "c${next++}".also { id -> held.update { it + (id to Held(name.trim())) } }

    override suspend fun rename(id: String, name: String) = held.update { all -> all[id]?.let { all + (id to it.copy(name = name.trim())) } ?: all }

    override suspend fun delete(id: String) = held.update { it - id }

    override suspend fun add(id: String, repo: RepoSummary) =
        held.update { all -> all[id]?.let { all + (id to it.copy(repos = listOf(repo) + it.repos.filter { r -> r.id != repo.id })) } ?: all }

    override suspend fun remove(id: String, repo: RepoId) =
        held.update { all -> all[id]?.let { all + (id to it.copy(repos = it.repos.filter { r -> r.id != repo })) } ?: all }

    override suspend fun import(account: Account): ForgeResult<ImportReport> {
        imports += account
        return importAnswer
    }
}
