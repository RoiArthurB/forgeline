package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

class GitHubSearchApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : SearchApi {

    override suspend fun repositories(token: String?, query: String, page: Int): ForgeResult<SearchPage<RepoSummary>> = gitHubCall {
        search(token, "repositories", query, page).toResult {
            body<SearchJson<SearchRepoJson>>().toPage(nextPage()) { it.toModel() }
        }
    }

    override suspend fun issues(token: String?, query: String, page: Int): ForgeResult<SearchPage<IssueSearchResult>> = gitHubCall {
        search(token, "issues", query, page).toResult {
            body<SearchJson<SearchIssueJson>>().toPage(nextPage()) { it.toModel() }
        }
    }

    override suspend fun users(token: String?, query: String, page: Int): ForgeResult<SearchPage<UserSummary>> = gitHubCall {
        search(token, "users", query, page).toResult {
            body<SearchJson<SearchUserJson>>().toPage(nextPage()) { UserSummary(it.login, it.avatarUrl, isOrganization = it.type == "Organization") }
        }
    }

    // "@me" is whoever the token signs in. Archived repositories are left out: nothing can be done there.
    override suspend fun work(token: String, login: String, kind: WorkKind): ForgeResult<List<IssueSearchResult>> = gitHubCall {
        val mine = when (kind) {
            WorkKind.REVIEW_REQUESTED -> "is:pr review-requested:@me"
            WorkKind.OWN_PULL_REQUESTS -> "is:pr author:@me"
            WorkKind.ASSIGNED -> "assignee:@me"
        }
        search(token, "issues", "is:open $mine archived:false", page = 1, sort = "updated").toResult {
            body<SearchJson<SearchIssueJson>>().items.mapNotNull { it.toModel() }
        }
    }

    private suspend fun search(token: String?, what: String, query: String, page: Int, sort: String? = null): HttpResponse =
        httpClient.gitHubApi(
            apiBaseUrl, token, "search", what,
            query = mapOf("q" to query, "per_page" to "30", "page" to page.toString()) + listOfNotNull(sort?.let { "sort" to it }),
        )
}

@Serializable
private data class SearchJson<T>(@SerialName("total_count") val totalCount: Int, val items: List<T>) {
    fun <R> toPage(nextPage: Int?, map: (T) -> R?): SearchPage<R> = SearchPage(items.mapNotNull(map), totalCount, nextPage)
}

@Serializable
private data class SearchRepoJsonOwner(@SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class SearchRepoJson(
    @SerialName("full_name") val fullName: String,
    val owner: SearchRepoJsonOwner? = null,
    val description: String? = null,
    val language: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    val fork: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toModel(): RepoSummary? {
        val (owner, name) = fullName.split('/').takeIf { it.size == 2 } ?: return null
        return RepoSummary(RepoId(owner, name, ForgeInstance.GitHub), description, language, stars, forks, fork, updatedAt?.let(Instant::parse), this.owner?.avatarUrl)
    }
}

@Serializable
private data class SearchUserJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null, val type: String? = null)

@Serializable
private data class SearchLabelJson(val name: String, val color: String? = null)

@Serializable
private data class SearchPullRequestJson(@SerialName("merged_at") val mergedAt: String? = null)

@Serializable
private data class SearchIssueJson(
    val number: Int,
    val title: String,
    val state: String,
    val user: SearchUserJson? = null,
    val comments: Int? = null,
    @SerialName("created_at") val createdAt: String,
    val labels: List<SearchLabelJson> = emptyList(),
    val draft: Boolean? = null,
    @SerialName("repository_url") val repositoryUrl: String,
    @SerialName("pull_request") val pullRequest: SearchPullRequestJson? = null,
) {
    fun toModel(): IssueSearchResult? {
        // https://api.github.com/repos/{owner}/{name}
        val (owner, name) = repositoryUrl.split('/').takeLast(2).takeIf { it.size == 2 } ?: return null
        return IssueSearchResult(
            RepoId(owner, name, ForgeInstance.GitHub),
            IssueSummary(
                number = number,
                title = title,
                state = when {
                    pullRequest?.mergedAt != null -> IssueState.MERGED
                    state == "closed" -> IssueState.CLOSED
                    else -> IssueState.OPEN
                },
                author = user?.let { ForgeUser(it.login, null, it.avatarUrl) },
                comments = comments,
                createdAt = Instant.parse(createdAt),
                labels = labels.map { Label(it.name, it.color) },
                isPullRequest = pullRequest != null,
                isDraft = draft == true,
            ),
        )
    }
}
