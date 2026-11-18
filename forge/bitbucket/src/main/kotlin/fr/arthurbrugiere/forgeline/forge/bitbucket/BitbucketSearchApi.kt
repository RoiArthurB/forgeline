package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.SearchSort
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Account
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedPullrequests
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedRepositories
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Searching Bitbucket. It has no search across all of it since April 2026 (Atlassian closed its cross-workspace
 * APIs), and no directory of people: repositories are searched by name in the workspaces of the person signed in, which
 * needs an account, and nothing else is searched. Its pull requests are found per workspace, for the person's work.
 */
class BitbucketSearchApi(
    private val http: HttpClient,
    private val people: BitbucketPeople = BitbucketPeople(),
    private val users: BitbucketUserApi = BitbucketUserApi(http, people),
) : SearchApi {
    override fun sorts(scope: SearchScope): Set<SearchSort> = when (scope) {
        SearchScope.REPOSITORIES -> setOf(SearchSort.BEST_MATCH, SearchSort.UPDATED)
        else -> setOf(SearchSort.BEST_MATCH)
    }

    override suspend fun repositories(token: String?, query: String, page: Int, sort: SearchSort): ForgeResult<SearchPage<RepoSummary>> = forgeCall {
        // Without an account there is no workspace to search in.
        token ?: return@forgeCall ForgeResult.Failure(ForgeError.Unsupported)
        val workspaces = when (val found = users.workspaces(token)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val params = listOfNotNull(
            "q" to "name ~ ${bbql(query.trim())}",
            "pagelen" to "$PAGE_SIZE",
            "page" to "$page",
            // Best match is Bitbucket's own order, by name.
            if (sort == SearchSort.UPDATED) "sort" to "-updated_on" else null,
        )
        coroutineScope {
            val answers = workspaces.map { workspace -> async { http.bitbucket(token, "repositories", workspace, query = params).takeIf { it.status.isSuccess() }?.body<PaginatedRepositories>() } }.awaitAll().filterNotNull()
            val found = answers.flatMap { it.propertyValues.orEmpty() }.mapNotNull { it.toSummary() }
            val ordered = if (sort == SearchSort.UPDATED) found.sortedByDescending { it.updatedAt } else found.sortedWith(compareBy({ !it.id.name.equals(query.trim(), ignoreCase = true) }, { it.id.name.lowercase() }))
            ForgeResult.Success(SearchPage(ordered, answers.sumOf { it.propertySize ?: 0L }.toInt(), (page + 1).takeIf { answers.any { it.next != null } }))
        }
    }

    override suspend fun issues(token: String?, query: String, page: Int, sort: SearchSort): ForgeResult<SearchPage<IssueSearchResult>> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun users(token: String?, query: String, page: Int): ForgeResult<SearchPage<UserSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Bitbucket has no issues to start on. */
    override suspend fun firstIssues(token: String?, repos: List<RepoId>): ForgeResult<List<IssueSearchResult>> = ForgeResult.Success(emptyList())

    override suspend fun topic(token: String?, topic: String, page: Int): ForgeResult<SearchPage<RepoSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    /**
     * The person's open pull requests, or those they are asked to review, across their workspaces. Nothing is assigned
     * on Bitbucket. Review requests are looked for in the repositories updated last, as Bitbucket can't be asked for
     * them all at once.
     */
    override suspend fun work(token: String, login: String, kind: WorkKind): ForgeResult<List<IssueSearchResult>> = forgeCall {
        if (kind == WorkKind.ASSIGNED) return@forgeCall ForgeResult.Success(emptyList())
        val me = people.uuidOf(login) ?: http.bitbucket(token, "user").let { if (it.status.isSuccess()) it.body<Account>().uuid else return@forgeCall it.failure() }
            ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        coroutineScope {
            val pages = when (kind) {
                WorkKind.OWN_PULL_REQUESTS -> when (val workspaces = users.workspaces(token)) {
                    is ForgeResult.Failure -> return@coroutineScope workspaces
                    is ForgeResult.Success -> workspaces.value.map { workspace ->
                        async { http.bitbucket(token, "workspaces", workspace, "pullrequests", me, query = listOf("state" to "OPEN", "pagelen" to "50", PULL_LIST_FIELDS)).takeIf { it.status.isSuccess() }?.body<PaginatedPullrequests>() }
                    }
                }
                else -> when (val repos = users.reachableRepos(token)) {
                    is ForgeResult.Failure -> return@coroutineScope repos
                    is ForgeResult.Success -> repos.value.take(REVIEW_REPOS).map { repo ->
                        async {
                            http.bitbucket(token, *repo.id.api("pullrequests"), query = listOf("q" to "state = \"OPEN\" AND reviewers.uuid = ${bbql(me)}", "pagelen" to "50", PULL_LIST_FIELDS))
                                .takeIf { it.status.isSuccess() }?.body<PaginatedPullrequests>()
                        }
                    }
                }
            }.awaitAll().filterNotNull()
            ForgeResult.Success(
                pages.flatMap { it.propertyValues.orEmpty() }
                    .mapNotNull { pull -> pull.repoId()?.let { IssueSearchResult(it, pull.toSummary(people)) } }
                    .sortedByDescending { it.issue.updatedAt ?: it.issue.createdAt },
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 30

        /** How many of the most recently updated repositories review requests are looked for in. */
        const val REVIEW_REPOS = 30
    }
}
