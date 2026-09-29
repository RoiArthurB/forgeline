package fr.arthurbrugiere.forgeline.core.data.repo

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

data class RepoSnapshot(
    val details: RepoDetails?,
    val readme: Readme?,
    val fetchedAtMillis: Long?,
)

interface RepoRepository {
    /** Cached details and README, then updates after each refresh. */
    fun observe(id: RepoId): Flow<RepoSnapshot>

    suspend fun refresh(id: RepoId, force: Boolean = false): RefreshResult

    // The calls below expect the canonical id, i.e. RepoDetails.id, never an old name.

    /** The README at another [ref] than the default branch, uncached. */
    suspend fun readme(id: RepoId, ref: String): ForgeResult<Readme?>

    suspend fun refs(id: RepoId): ForgeResult<GitRefs>

    suspend fun contents(id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>>

    suspend fun fileText(id: RepoId, path: String, ref: String): ForgeResult<String>

    suspend fun openIssues(id: RepoId): ForgeResult<List<IssueSummary>>

    suspend fun openPullRequests(id: RepoId): ForgeResult<List<IssueSummary>>

    suspend fun releases(id: RepoId): ForgeResult<List<Release>>

    suspend fun workflowRuns(id: RepoId): ForgeResult<List<WorkflowRun>>

    fun rawBaseUrl(id: RepoId, ref: String): String

    fun blobBaseUrl(id: RepoId, ref: String): String
}

class DefaultRepoRepository @Inject constructor(
    private val dao: RepoDao,
    private val api: RepoApi,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : RepoRepository {

    override fun observe(id: RepoId): Flow<RepoSnapshot> = dao.observe(id.cacheKey()).map { entity ->
        RepoSnapshot(entity?.decodeDetails(), entity?.readme(), entity?.fetchedAtMillis)
    }

    override suspend fun refresh(id: RepoId, force: Boolean): RefreshResult {
        val fetchedAt = dao.fetchedAt(id.cacheKey())
        if (!force && fetchedAt != null && clock.millis() - fetchedAt < MAX_AGE.inWholeMilliseconds) return RefreshResult.Fresh
        val token = token()
        val details = when (val result = api.repo(token, id)) {
            is ForgeResult.Failure -> return RefreshResult.Failed(result.error)
            is ForgeResult.Success -> result.value
        }
        // details.id is canonical: a moved repo answers its old name, but search wouldn't.
        val readme = when (val result = api.readme(token, details.id)) {
            is ForgeResult.Failure -> return RefreshResult.Failed(result.error)
            is ForgeResult.Success -> result.value
        }
        dao.upsert(RepoCacheEntity(id.cacheKey(), details.encode(), readme?.path, readme?.markdown, clock.millis()))
        return RefreshResult.Refreshed
    }

    override suspend fun readme(id: RepoId, ref: String) = api.readme(token(), id, ref)

    override suspend fun refs(id: RepoId) = api.refs(token(), id)

    override suspend fun contents(id: RepoId, path: String, ref: String) = api.contents(token(), id, path, ref)

    override suspend fun fileText(id: RepoId, path: String, ref: String) = api.fileText(token(), id, path, ref)

    override suspend fun openIssues(id: RepoId) = api.openIssues(token(), id)

    override suspend fun openPullRequests(id: RepoId) = api.openPullRequests(token(), id)

    override suspend fun releases(id: RepoId) = api.releases(token(), id)

    override suspend fun workflowRuns(id: RepoId) = api.workflowRuns(token(), id)

    override fun rawBaseUrl(id: RepoId, ref: String) = api.rawBaseUrl(id, ref)

    override fun blobBaseUrl(id: RepoId, ref: String) = api.blobBaseUrl(id, ref)

    private suspend fun token(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }

    private companion object {
        val MAX_AGE = 30.minutes
    }
}
