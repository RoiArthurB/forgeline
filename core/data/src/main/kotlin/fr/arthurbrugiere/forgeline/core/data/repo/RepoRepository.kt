package fr.arthurbrugiere.forgeline.core.data.repo

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
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

    suspend fun issues(id: RepoId, query: IssueQuery = IssueQuery()): ForgeResult<List<IssueSummary>>

    suspend fun pullRequests(id: RepoId, query: IssueQuery = IssueQuery()): ForgeResult<List<IssueSummary>>

    suspend fun pinnedIssues(id: RepoId): ForgeResult<List<IssueSummary>>

    suspend fun releases(id: RepoId): ForgeResult<List<Release>>

    suspend fun workflowRuns(id: RepoId): ForgeResult<List<WorkflowRun>>

    fun rawBaseUrl(id: RepoId, ref: String): String

    fun blobBaseUrl(id: RepoId, ref: String): String
}

class DefaultRepoRepository @Inject constructor(
    private val dao: RepoDao,
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val clock: Clock,
) : RepoRepository {

    override fun observe(id: RepoId): Flow<RepoSnapshot> = dao.observe(id.cacheKey()).map { entity ->
        RepoSnapshot(entity?.decodeDetails(), entity?.readme(), entity?.fetchedAtMillis)
    }

    override suspend fun refresh(id: RepoId, force: Boolean): RefreshResult {
        val fetchedAt = dao.fetchedAt(id.cacheKey())
        if (!force && fetchedAt != null && clock.millis() - fetchedAt < MAX_AGE.inWholeMilliseconds) return RefreshResult.Fresh
        val api = clients.repos(id.forge)
        val token = accounts.tokenOn(id.forge)
        // The details and the README side by side: one round trip to a far forge instead of two.
        val (detailsResult, readmeAsked) = coroutineScope {
            val details = async { api.repo(token, id) }
            val readme = async { api.readme(token, id) }
            details.await() to readme.await()
        }
        val details = when (detailsResult) {
            is ForgeResult.Failure -> return RefreshResult.Failed(detailsResult.error)
            is ForgeResult.Success -> detailsResult.value
        }
        // details.id is canonical: a moved repo may not answer its old name for the README, so ask again there.
        val readmeResult = if (details.id == id) readmeAsked else api.readme(token, details.id)
        val readme = when (val result = readmeResult) {
            is ForgeResult.Failure -> return RefreshResult.Failed(result.error)
            is ForgeResult.Success -> result.value
        }
        dao.upsert(RepoCacheEntity(id.cacheKey(), details.encode(), readme?.path, readme?.markdown, clock.millis()))
        dao.prune(STORED_REPOS)
        return RefreshResult.Refreshed
    }

    override suspend fun readme(id: RepoId, ref: String) = clients.repos(id.forge).readme(accounts.tokenOn(id.forge), id, ref)

    override suspend fun refs(id: RepoId) = clients.repos(id.forge).refs(accounts.tokenOn(id.forge), id)

    override suspend fun contents(id: RepoId, path: String, ref: String) = clients.repos(id.forge).contents(accounts.tokenOn(id.forge), id, path, ref)

    override suspend fun fileText(id: RepoId, path: String, ref: String) = clients.repos(id.forge).fileText(accounts.tokenOn(id.forge), id, path, ref)

    override suspend fun issues(id: RepoId, query: IssueQuery) = clients.repos(id.forge).issues(accounts.tokenOn(id.forge), id, query)

    override suspend fun pullRequests(id: RepoId, query: IssueQuery) = clients.repos(id.forge).pullRequests(accounts.tokenOn(id.forge), id, query)

    override suspend fun pinnedIssues(id: RepoId) = clients.repos(id.forge).pinnedIssues(accounts.tokenOn(id.forge), id)

    override suspend fun releases(id: RepoId) = clients.repos(id.forge).releases(accounts.tokenOn(id.forge), id)

    override suspend fun workflowRuns(id: RepoId) = clients.repos(id.forge).workflowRuns(accounts.tokenOn(id.forge), id)

    override fun rawBaseUrl(id: RepoId, ref: String) = clients.repos(id.forge).rawBaseUrl(id, ref)

    override fun blobBaseUrl(id: RepoId, ref: String) = clients.repos(id.forge).blobBaseUrl(id, ref)


    companion object {
        private val MAX_AGE = 30.minutes

        /** Repositories kept on disk, README included: the ones opened last. Regression: the cache only grew. */
        const val STORED_REPOS = 200
    }
}
