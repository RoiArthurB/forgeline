package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoSnapshot
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRepoRepository : RepoRepository {
    val snapshot = MutableStateFlow(RepoSnapshot(null, null, null))
    var nextRefresh: RefreshResult = RefreshResult.Refreshed
    val refreshes = mutableListOf<Boolean>()
    val calls = mutableListOf<String>()
    var directories = mapOf<String, ForgeResult<List<RepoFile>>>()
    val files = mutableMapOf<String, ForgeResult<String>>()
    var issues: ForgeResult<List<IssueSummary>> = ForgeResult.Success(emptyList())
    var pulls: ForgeResult<List<IssueSummary>> = ForgeResult.Success(emptyList())
    var releases: ForgeResult<List<Release>> = ForgeResult.Success(emptyList())
    var runs: ForgeResult<List<WorkflowRun>> = ForgeResult.Success(emptyList())

    override fun observe(id: RepoId): Flow<RepoSnapshot> = snapshot

    override suspend fun refresh(id: RepoId, force: Boolean): RefreshResult {
        refreshes += force
        return nextRefresh
    }

    override suspend fun contents(id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> {
        calls += "contents:${id.fullName}:$path@$ref"
        return directories[path] ?: ForgeResult.Success(emptyList())
    }

    override suspend fun fileText(id: RepoId, path: String, ref: String): ForgeResult<String> {
        calls += "file:${id.fullName}:$path@$ref"
        return files[path] ?: ForgeResult.Success("")
    }

    override suspend fun openIssues(id: RepoId) = issues.also { calls += "issues:${id.fullName}" }

    override suspend fun openPullRequests(id: RepoId) = pulls.also { calls += "pulls:${id.fullName}" }

    override suspend fun releases(id: RepoId) = releases.also { calls += "releases:${id.fullName}" }

    override suspend fun workflowRuns(id: RepoId) = runs.also { calls += "runs:${id.fullName}" }

    override fun rawBaseUrl(id: RepoId, ref: String) = "https://raw.example/${id.fullName}/$ref/"

    override fun blobBaseUrl(id: RepoId, ref: String) = "https://blob.example/${id.fullName}/$ref/"
}
