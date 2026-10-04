package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class GitLabSearchApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : SearchApi {

    override suspend fun repositories(token: String?, query: String, page: Int): ForgeResult<SearchPage<RepoSummary>> = gitlabCall {
        val response = httpClient.gitlabApi(
            forge, token, "projects",
            query = mapOf("search" to query, "page" to page.toString(), "per_page" to "$PAGE_SIZE"),
        )
        response.toResult {
            val items = body<List<GitLabProjectJson>>().map { it.toSummary(forge) }
            SearchPage(items, totalCount() ?: items.size, nextPage())
        }
    }

    // Through the search endpoint: listing every merge request of gitlab.com that matches (`/merge_requests?scope=all`)
    // times out there (408 after 15 s, measured 2026-10-04), where the search answers in a second or two.
    override suspend fun issues(token: String?, query: String, page: Int): ForgeResult<SearchPage<IssueSearchResult>> = gitlabCall {
        coroutineScope {
            val issuesDeferred = async {
                httpClient.gitlabApi(
                    forge, token, "search",
                    query = mapOf("scope" to "issues", "search" to query, "page" to page.toString(), "per_page" to "$HALF_PAGE_SIZE"),
                )
            }
            val mrsDeferred = async {
                httpClient.gitlabApi(
                    forge, token, "search",
                    query = mapOf("scope" to "merge_requests", "search" to query, "page" to page.toString(), "per_page" to "$HALF_PAGE_SIZE"),
                )
            }

            val issuesRes = issuesDeferred.await()
            val mrsRes = mrsDeferred.await()

            if (!issuesRes.status.isSuccess() && !mrsRes.status.isSuccess()) {
                return@coroutineScope issuesRes.failure()
            }

            val issueResults = if (issuesRes.status.isSuccess()) {
                issuesRes.body<List<GitLabIssueJson>>().mapNotNull { issue ->
                    val repoId = parseRepoFromIssueUrl(issue.webUrl) ?: return@mapNotNull null
                    IssueSearchResult(repoId, issue.toSummary(repoId))
                }
            } else {
                emptyList()
            }

            val mrResults = if (mrsRes.status.isSuccess()) {
                mrsRes.body<List<GitLabMergeRequestJson>>().mapNotNull { mr ->
                    val repoId = parseRepoFromIssueUrl(mr.webUrl) ?: return@mapNotNull null
                    IssueSearchResult(repoId, mr.toSummary(repoId))
                }
            } else {
                emptyList()
            }

            val combined = (issueResults + mrResults).sortedByDescending { it.issue.createdAt }
            val total = (issuesRes.totalCount() ?: 0) + (mrsRes.totalCount() ?: 0)
            val hasNext = (issuesRes.nextPage() != null) || (mrsRes.nextPage() != null) || (combined.size >= PAGE_SIZE)
            ForgeResult.Success(SearchPage(combined, total.coerceAtLeast(combined.size), (page + 1).takeIf { hasNext }))
        }
    }

    override suspend fun users(token: String?, query: String, page: Int): ForgeResult<SearchPage<UserSummary>> = gitlabCall {
        val response = httpClient.gitlabApi(
            forge, token, "users",
            query = mapOf("search" to query, "page" to page.toString(), "per_page" to "$PAGE_SIZE"),
        )
        response.toResult {
            val items = body<List<GitLabUserJson>>().map { it.toSummary(forge) }
            SearchPage(items, totalCount() ?: items.size, nextPage())
        }
    }

    /** The project a result is in, from its page's address; null when that can't be read, and the result is left out. */
    private fun parseRepoFromIssueUrl(webUrl: String?): RepoId? {
        val path = webUrl?.takeIf { "/-/" in it }?.substringBefore("/-/")?.substringAfter("://")?.substringAfter('/', "") ?: return null
        if ('/' !in path) return null
        return RepoId(path.substringBeforeLast('/'), path.substringAfterLast('/'), forge)
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val HALF_PAGE_SIZE = 15
    }
}
