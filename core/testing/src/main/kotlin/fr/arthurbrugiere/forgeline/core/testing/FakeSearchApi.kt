package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.SearchSort
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkKind

/**
 * Serves every query the same results, split into pages of [pageSize], in the order given whatever the sort: a sort
 * other than best match is recorded in [calls] after a `~`.
 */
class FakeSearchApi(private val pageSize: Int = 30) : SearchApi {
    var repositories: List<RepoSummary> = emptyList()
    var issues: List<IssueSearchResult> = emptyList()
    var users: List<UserSummary> = emptyList()
    var failure: ForgeError? = null
    val calls = mutableListOf<String>()
    val tokens = mutableListOf<String?>()

    /** The orders it sorts in: every one each scope offers, unless a test says otherwise. */
    var sorts: (SearchScope) -> Set<SearchSort> = { it.sorts.toSet() }

    override fun sorts(scope: SearchScope): Set<SearchSort> = sorts.invoke(scope)

    private fun <T> page(kind: String, token: String?, query: String, page: Int, all: List<T>, sort: SearchSort = SearchSort.BEST_MATCH): ForgeResult<SearchPage<T>> {
        calls += "$kind:$query@$page" + if (sort == SearchSort.BEST_MATCH) "" else "~$sort"
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        val items = all.drop((page - 1) * pageSize).take(pageSize)
        return ForgeResult.Success(SearchPage(items, all.size, (page + 1).takeIf { page * pageSize < all.size }))
    }

    override suspend fun repositories(token: String?, query: String, page: Int, sort: SearchSort) = page("repos", token, query, page, repositories, sort)

    override suspend fun issues(token: String?, query: String, page: Int, sort: SearchSort) = page("issues", token, query, page, issues, sort)

    override suspend fun users(token: String?, query: String, page: Int) = page("users", token, query, page, users)

    /** The first issues each repository has; one missing has none. */
    val firstIssues = mutableMapOf<fr.arthurbrugiere.forgeline.core.model.RepoId, List<IssueSearchResult>>()

    override suspend fun firstIssues(token: String?, repos: List<fr.arthurbrugiere.forgeline.core.model.RepoId>): ForgeResult<List<IssueSearchResult>> {
        calls += "first:" + repos.joinToString(",") { it.fullName }
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(repos.flatMap { firstIssues[it].orEmpty() }.sortedByDescending { it.issue.createdAt })
    }

    /** What a topic answers, whichever it is. */
    var topicRepositories: List<RepoSummary> = emptyList()

    override suspend fun topic(token: String?, topic: String, page: Int) = page("topic", token, topic, page, topicRepositories)

    /** What each kind of work answers; a kind in [workFailures] fails instead. */
    val work = mutableMapOf<WorkKind, List<IssueSearchResult>>()
    val workFailures = mutableMapOf<WorkKind, ForgeError>()

    /** Completed by the test to let the answers to [work] through, when set. */
    var workGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun work(token: String, login: String, kind: WorkKind): ForgeResult<List<IssueSearchResult>> {
        calls += "work:$kind:$login"
        tokens += token
        workGate?.await()
        (workFailures[kind] ?: failure)?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(work[kind].orEmpty())
    }
}

fun repoSummary(
    fullName: String,
    stars: Int = 10,
    description: String? = "A repo",
    forge: fr.arthurbrugiere.forgeline.core.model.ForgeInstance = fr.arthurbrugiere.forgeline.core.model.ForgeInstance.GitHub,
): RepoSummary {
    val (owner, name) = fullName.split('/')
    return RepoSummary(fr.arthurbrugiere.forgeline.core.model.RepoId(owner, name, forge), description, "Kotlin", stars, 1, false, null)
}
