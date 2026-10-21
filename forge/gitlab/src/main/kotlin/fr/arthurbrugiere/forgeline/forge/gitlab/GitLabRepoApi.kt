package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import fr.arthurbrugiere.forgeline.core.model.languageShares
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class GitLabRepoApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.GitLab,
) : RepoApi {
    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = repoAndRights(token, id).map { it.first }

    override suspend fun repoAndRights(token: String?, id: RepoId): ForgeResult<Pair<RepoDetails, RepoRights?>> = forgeCall {
        coroutineScope {
            // Asked alongside: a project says how many issues are open, not how many merge requests.
            val openPulls = async { openMergeRequests(token, id) }
            httpClient.gitlabApi(id.forge, token, "projects", id.fullName)
                .toResult { body<GitLabProjectJson>().let { it.toDetails(id.forge) to token?.let { _ -> it.toRights() } } }
                .map { (details, rights) -> details.copy(openPulls = openPulls.await()) to rights }
        }
    }

    /** How many merge requests are open, from the total GitLab sends with a one-item page; null when it can't be told. */
    private suspend fun openMergeRequests(token: String?, id: RepoId): Int? = try {
        httpClient.gitlabApi(
            id.forge, token, "projects", id.fullName, "merge_requests", query = mapOf("state" to "opened", "per_page" to "1"),
        ).takeIf { it.status == HttpStatusCode.OK }?.totalCount()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A count is a nicety: the repository opens without it.
        null
    }

    /** GitLab gives percentages rather than bytes: shares all the same. */
    override suspend fun languages(token: String?, id: RepoId): ForgeResult<List<RepoLanguage>> = forgeCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "languages").toResult { languageShares(body<Map<String, Double>>()) }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgeCall {
        val branch = ref ?: run {
            val projectResponse = httpClient.gitlabApi(id.forge, token, "projects", id.fullName)
            if (projectResponse.status != HttpStatusCode.OK) return@forgeCall projectResponse.failure()
            projectResponse.body<GitLabProjectJson>().defaultBranch ?: "main"
        }

        // Try standard README.md directly
        val directResponse = httpClient.gitlabApi(
            id.forge, token, "projects", id.fullName, "repository", "files", "README.md", "raw",
            query = mapOf("ref" to branch),
        )
        if (directResponse.status == HttpStatusCode.OK) {
            return@forgeCall ForgeResult.Success(Readme("README.md", directResponse.bodyAsText()))
        }

        // Otherwise scan tree at root for any file matching readme.*
        val treeResponse = httpClient.gitlabApi(
            id.forge, token, "projects", id.fullName, "repository", "tree",
            query = mapOf("ref" to branch, "per_page" to "100"),
        )
        if (treeResponse.status != HttpStatusCode.OK) return@forgeCall treeResponse.failure()
        val items = treeResponse.body<List<GitLabTreeItemJson>>()
        val readmeItem = items.firstOrNull { it.type == "blob" && it.name.startsWith("readme", ignoreCase = true) }
            ?: return@forgeCall ForgeResult.Success(null)

        val rawResponse = httpClient.gitlabApi(
            id.forge, token, "projects", id.fullName, "repository", "files", readmeItem.path, "raw",
            query = mapOf("ref" to branch),
        )
        rawResponse.toResult { Readme(readmeItem.path, bodyAsText()) }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        coroutineScope {
            val branchesDeferred = async {
                httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "repository", "branches", query = mapOf("per_page" to "100"))
            }
            val tagsDeferred = async {
                httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "repository", "tags", query = mapOf("per_page" to "100"))
            }
            val branchesRes = branchesDeferred.await()
            val tagsRes = tagsDeferred.await()

            if (branchesRes.status != HttpStatusCode.OK) return@coroutineScope branchesRes.failure()
            if (tagsRes.status != HttpStatusCode.OK) return@coroutineScope tagsRes.failure()

            val branches = branchesRes.body<List<GitLabBranchJson>>().map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            val tags = tagsRes.body<List<GitLabTagJson>>().map { it.name }.sortedWith(VersionOrder.reversed())
            ForgeResult.Success(GitRefs(branches, tags))
        }
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = forgeCall {
        val query = mutableMapOf("ref" to ref, "per_page" to "100")
        if (path.isNotBlank()) query["path"] = path

        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "repository", "tree", query = query)
            .toResult {
                body<List<GitLabTreeItemJson>>()
                    .map { it.toRepoFile() }
                    .sortedWith(compareBy({ it.type != RepoFileType.DIR }, { it.name.lowercase() }))
            }
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall {
        httpClient.gitlabApi(
            id.forge, token, "projects", id.fullName, "repository", "files", path, "raw",
            query = mapOf("ref" to ref),
        ).toResult { bodyAsText() }
    }

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        val params = mutableMapOf(
            "state" to if (query.open) "opened" else "closed",
            "order_by" to "created_at",
            "sort" to "desc",
            "per_page" to "${IssueQuery.PAGE_SIZE}",
            "page" to "${query.page}",
        )
        if (query.text.isNotBlank()) params["search"] = query.text

        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "issues", query = params)
            .toResult { body<List<GitLabIssueJson>>().map { it.toSummary(id) } }
    }

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        // GitLab lists one state at a time: those no longer open are the merged and the closed, asked together.
        val states = if (query.open) listOf("opened") else listOf("merged", "closed")
        val answers = coroutineScope {
            states.map { state ->
                async {
                    val params = mutableMapOf("state" to state, "order_by" to "created_at", "sort" to "desc", "per_page" to "${IssueQuery.PAGE_SIZE}", "page" to "${query.page}")
                    if (query.text.isNotBlank()) params["search"] = query.text
                    httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "merge_requests", query = params)
                }
            }.map { it.await() }
        }
        answers.firstOrNull { it.status != HttpStatusCode.OK }?.let { return@forgeCall it.failure() }
        ForgeResult.Success(answers.flatMap { it.body<List<GitLabMergeRequestJson>>() }.map { it.toSummary(id) }.sortedByDescending { it.createdAt })
    }

    override suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> =
        ForgeResult.Success(emptyList())

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgeCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "releases", query = mapOf("per_page" to "50"))
            .toResult { body<List<GitLabReleaseJson>>().map { it.toModel() } }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = forgeCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "releases", tag)
            .toResult { body<GitLabReleaseJson>().toModel() }
    }

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = forgeCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "pipelines", query = mapOf("per_page" to "30"))
            .toResult { body<List<GitLabPipelineJson>>().map { it.toRun() } }
    }

    override fun rawBaseUrl(id: RepoId, ref: String): String = "${id.forge.webUrl}/${id.fullName}/-/raw/$ref/"

    override fun blobBaseUrl(id: RepoId, ref: String): String = "${id.forge.webUrl}/${id.fullName}/-/blob/$ref/"
}
