package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary

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
}

fun repoSummary(fullName: String, stars: Int = 10, description: String? = "A repo"): RepoSummary {
    val (owner, name) = fullName.split('/')
    return RepoSummary(fr.arthurbrugiere.forgeline.core.model.RepoId(owner, name), description, "Kotlin", stars, 1, false, null)
}
