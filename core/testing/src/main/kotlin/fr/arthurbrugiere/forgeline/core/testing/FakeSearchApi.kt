package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkKind

/** Serves every query the same results, split into pages of [pageSize]. */
class FakeSearchApi(private val pageSize: Int = 30) : SearchApi {
    var repositories: List<RepoSummary> = emptyList()
    var issues: List<IssueSearchResult> = emptyList()
    var users: List<UserSummary> = emptyList()
    var failure: ForgeError? = null
    val calls = mutableListOf<String>()
    val tokens = mutableListOf<String?>()

    private fun <T> page(kind: String, token: String?, query: String, page: Int, all: List<T>): ForgeResult<SearchPage<T>> {
        calls += "$kind:$query@$page"
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        val items = all.drop((page - 1) * pageSize).take(pageSize)
        return ForgeResult.Success(SearchPage(items, all.size, (page + 1).takeIf { page * pageSize < all.size }))
    }

    override suspend fun repositories(token: String?, query: String, page: Int) = page("repos", token, query, page, repositories)

    override suspend fun issues(token: String?, query: String, page: Int) = page("issues", token, query, page, issues)

    override suspend fun users(token: String?, query: String, page: Int) = page("users", token, query, page, users)

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
