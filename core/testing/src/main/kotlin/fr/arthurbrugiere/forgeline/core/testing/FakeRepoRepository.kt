package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoSnapshot
import fr.arthurbrugiere.forgeline.core.data.trending.RefreshResult
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@Suppress("UNCHECKED_CAST")
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
    var refs: ForgeResult<GitRefs> = ForgeResult.Success(GitRefs(listOf("main"), emptyList()))
    val readmes = mutableMapOf<String, ForgeResult<Readme?>>()

    override fun observe(id: RepoId): Flow<RepoSnapshot> = snapshot

    override suspend fun refresh(id: RepoId, force: Boolean): RefreshResult {
        refreshes += force
        return nextRefresh
    }

    override suspend fun readme(id: RepoId, ref: String): ForgeResult<Readme?> {
        calls += "readme:${id.fullName}@$ref"
        return readmes[ref] ?: ForgeResult.Success(null)
    }

    override suspend fun refs(id: RepoId) = refs.also { calls += "refs:${id.fullName}" }

    override suspend fun contents(id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> {
        calls += "contents:${id.fullName}:$path@$ref"
        gate?.await()
        return directories[path] ?: ForgeResult.Success(emptyList())
    }

    override suspend fun fileText(id: RepoId, path: String, ref: String): ForgeResult<String> {
        calls += "file:${id.fullName}:$path@$ref"
        return files[path] ?: ForgeResult.Success("")
    }

    /** What a list other than the default one answers, by "closed" or its words; [issues] and [pulls] otherwise. */
    val answers = mutableMapOf<IssueQuery, ForgeResult<List<IssueSummary>>>()

    /** When set, lists wait for it: a slow forge. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun issues(id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> {
        calls += "issues:${id.fullName}${query.suffix}"
        gate?.await()
        return answers[query] ?: issues.takeIf { query.page == 1 } ?: ForgeResult.Success(emptyList())
    }

    override suspend fun pullRequests(id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> {
        calls += "pulls:${id.fullName}${query.suffix}"
        gate?.await()
        return answers[query] ?: pulls.takeIf { query.page == 1 } ?: ForgeResult.Success(emptyList())
    }

    /** The pages of discussions, by what asks for each (null for the first); a failure for those not given. */
    val discussionPages = mutableMapOf<String?, ForgeResult<fr.arthurbrugiere.forgeline.core.model.DiscussionPage>>()
    val discussions = mutableMapOf<Int, ForgeResult<fr.arthurbrugiere.forgeline.core.model.Discussion>>()
    val seenDiscussions = mutableMapOf<Int, fr.arthurbrugiere.forgeline.core.model.DiscussionSummary>()

    override suspend fun discussions(id: RepoId, after: String?): ForgeResult<fr.arthurbrugiere.forgeline.core.model.DiscussionPage> {
        calls += "discussions:${id.fullName}${after?.let { " after $it" }.orEmpty()}"
        gate?.await()
        return discussionPages[after] ?: ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(404, "Not Found"))
    }

    override suspend fun discussion(id: RepoId, number: Int): ForgeResult<fr.arthurbrugiere.forgeline.core.model.Discussion> {
        calls += "discussion:${id.fullName}#$number"
        gate?.await()
        return discussions[number] ?: ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(404, "Not Found"))
    }

    override fun rememberedDiscussion(id: RepoId, number: Int) = seenDiscussions[number]

    /** What the session remembers of each list, by the call that would ask for it. */
    val remembered = mutableMapOf<String, Any>()

    override fun rememberedIssues(id: RepoId, query: IssueQuery) = remembered["issues${query.suffix}"] as List<IssueSummary>?

    override fun rememberedPullRequests(id: RepoId, query: IssueQuery) = remembered["pulls${query.suffix}"] as List<IssueSummary>?

    override fun rememberedReleases(id: RepoId) = remembered["releases"] as List<Release>?

    override fun rememberedContents(id: RepoId, path: String, ref: String) = remembered["contents:$path@$ref"] as List<RepoFile>?

    var pinned: ForgeResult<List<IssueSummary>> = ForgeResult.Success(emptyList())

    override suspend fun pinnedIssues(id: RepoId) = pinned.also { calls += "pinned:${id.fullName}" }

    override suspend fun releases(id: RepoId) = releases.also { calls += "releases:${id.fullName}" }

    /** Releases already seen this session, by tag. */
    val seenReleases = mutableMapOf<String, Release>()

    /** What loading one release answers, by tag; 404 otherwise. */
    val releaseAnswers = mutableMapOf<String, ForgeResult<Release>>()

    override fun cachedRelease(id: RepoId, tag: String): Release? = seenReleases[tag]

    override suspend fun release(id: RepoId, tag: String): ForgeResult<Release> {
        calls += "release:${id.fullName}@$tag"
        gate?.await()
        return releaseAnswers[tag] ?: ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Http(404, "Not Found"))
    }

    override suspend fun workflowRuns(id: RepoId) = runs.also { calls += "runs:${id.fullName}" }

    override fun rawBaseUrl(id: RepoId, ref: String) = "https://raw.example/${id.fullName}/$ref/"

    override fun blobBaseUrl(id: RepoId, ref: String) = "https://blob.example/${id.fullName}/$ref/"
}
