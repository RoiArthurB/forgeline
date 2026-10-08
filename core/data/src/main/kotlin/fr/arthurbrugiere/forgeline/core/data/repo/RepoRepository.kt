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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
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

    /** The release under [tag] as last listed or loaded this session, to show at once; null when it wasn't. */
    fun cachedRelease(id: RepoId, tag: String): Release?

    /** Loads the release under [tag]. What only the list knows of it (whether it is the latest) is kept. */
    suspend fun release(id: RepoId, tag: String): ForgeResult<Release>

    suspend fun workflowRuns(id: RepoId): ForgeResult<List<WorkflowRun>>

    // What each list answered last this session, to show at once while the forge is asked again; null when it wasn't asked.

    fun rememberedIssues(id: RepoId, query: IssueQuery = IssueQuery()): List<IssueSummary>? = null

    fun rememberedPullRequests(id: RepoId, query: IssueQuery = IssueQuery()): List<IssueSummary>? = null

    fun rememberedReleases(id: RepoId): List<Release>? = null

    fun rememberedContents(id: RepoId, path: String, ref: String): List<RepoFile>? = null

    fun rawBaseUrl(id: RepoId, ref: String): String

    fun blobBaseUrl(id: RepoId, ref: String): String
}

// One for the whole app: the releases a repository's list loaded are what a release's page opens from.
@Singleton
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

    /**
     * The lists last answered, the ones read longest ago leaving first. Regression: a repository's tabs asked the
     * forge and showed a loading screen each time the repository was opened, however recently it had been.
     */
    private val lists = object : LinkedHashMap<String, List<*>>(REMEMBERED_LISTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<*>>?) = size > REMEMBERED_LISTS
    }

    private fun <T> ForgeResult<List<T>>.remember(key: String) = also { if (it is ForgeResult.Success) synchronized(lists) { lists[key] = it.value } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> remembered(key: String): List<T>? = synchronized(lists) { lists[key] } as List<T>?

    // An account sees what another doesn't (private repositories, drafts): what is remembered is one account's.
    private fun listKeyNow(id: RepoId, what: String) = "${lastToken[id.forge]?.hashCode()}|${id.cacheKey()}|$what"

    /** The token last used on each forge: what was remembered under another is not shown. */
    private val lastToken = ConcurrentHashMap<fr.arthurbrugiere.forgeline.core.model.ForgeInstance, String>()

    private suspend fun token(id: RepoId): String? = accounts.tokenOn(id.forge).also { if (it == null) lastToken.remove(id.forge) else lastToken[id.forge] = it }

    private val IssueQuery.key get() = "$open|${text.trim()}|$page"

    override suspend fun contents(id: RepoId, path: String, ref: String) =
        clients.repos(id.forge).contents(token(id), id, path, ref).remember(listKeyNow(id, "contents|$ref|$path"))

    override fun rememberedContents(id: RepoId, path: String, ref: String): List<RepoFile>? = remembered(listKeyNow(id, "contents|$ref|$path"))

    override fun rememberedIssues(id: RepoId, query: IssueQuery): List<IssueSummary>? = remembered(listKeyNow(id, "issues|${query.key}"))

    override fun rememberedPullRequests(id: RepoId, query: IssueQuery): List<IssueSummary>? = remembered(listKeyNow(id, "pulls|${query.key}"))

    override fun rememberedReleases(id: RepoId): List<Release>? = remembered(listKeyNow(id, "releases"))

    override suspend fun fileText(id: RepoId, path: String, ref: String) = clients.repos(id.forge).fileText(accounts.tokenOn(id.forge), id, path, ref)

    override suspend fun issues(id: RepoId, query: IssueQuery) =
        clients.repos(id.forge).issues(token(id), id, query).remember(listKeyNow(id, "issues|${query.key}"))

    override suspend fun pullRequests(id: RepoId, query: IssueQuery) =
        clients.repos(id.forge).pullRequests(token(id), id, query).remember(listKeyNow(id, "pulls|${query.key}"))

    override suspend fun pinnedIssues(id: RepoId) = clients.repos(id.forge).pinnedIssues(accounts.tokenOn(id.forge), id)

    /** Releases seen this session, by repository then tag: opening one from its list asks the forge nothing. */
    private val seenReleases = ConcurrentHashMap<Pair<RepoId, String>, Release>()

    override suspend fun releases(id: RepoId) = clients.repos(id.forge).releases(token(id), id).also { result ->
        if (result is ForgeResult.Success) result.value.forEach { seenReleases[id to it.tag] = it }
    }.remember(listKeyNow(id, "releases"))

    override fun cachedRelease(id: RepoId, tag: String): Release? = seenReleases[id to tag]

    override suspend fun release(id: RepoId, tag: String): ForgeResult<Release> =
        when (val result = clients.repos(id.forge).release(accounts.tokenOn(id.forge), id, tag)) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> ForgeResult.Success(result.value.copy(isLatest = seenReleases[id to tag]?.isLatest == true).also { seenReleases[id to tag] = it })
        }

    override suspend fun workflowRuns(id: RepoId) = clients.repos(id.forge).workflowRuns(accounts.tokenOn(id.forge), id)

    override fun rawBaseUrl(id: RepoId, ref: String) = clients.repos(id.forge).rawBaseUrl(id, ref)

    override fun blobBaseUrl(id: RepoId, ref: String) = clients.repos(id.forge).blobBaseUrl(id, ref)


    companion object {
        private val MAX_AGE = 30.minutes

        /** Repositories kept on disk, README included: the ones opened last. Regression: the cache only grew. */
        const val STORED_REPOS = 200

        /** Lists kept in memory for the session: a few repositories' tabs and folders. */
        const val REMEMBERED_LISTS = 96
    }
}
