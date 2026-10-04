package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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

    private fun projectPath(id: RepoId): String = encodePath(id.fullName)

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id))
            .toResult { body<GitLabProjectJson>().toDetails(id.forge) }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = gitlabCall {
        val branch = ref ?: run {
            val projectResponse = httpClient.gitlabApi(id.forge, token, "projects", projectPath(id))
            if (projectResponse.status != HttpStatusCode.OK) return@gitlabCall projectResponse.failure()
            projectResponse.body<GitLabProjectJson>().defaultBranch ?: "main"
        }

        // Try standard README.md directly
        val directResponse = httpClient.gitlabApi(
            id.forge, token, "projects", projectPath(id), "repository", "files", encodePath("README.md"), "raw",
            query = mapOf("ref" to branch),
        )
        if (directResponse.status == HttpStatusCode.OK) {
            return@gitlabCall ForgeResult.Success(Readme("README.md", directResponse.bodyAsText()))
        }

        // Otherwise scan tree at root for any file matching readme.*
        val treeResponse = httpClient.gitlabApi(
            id.forge, token, "projects", projectPath(id), "repository", "tree",
            query = mapOf("ref" to branch, "per_page" to "100"),
        )
        if (treeResponse.status != HttpStatusCode.OK) return@gitlabCall treeResponse.failure()
        val items = treeResponse.body<List<GitLabTreeItemJson>>()
        val readmeItem = items.firstOrNull { it.type == "blob" && it.name.startsWith("readme", ignoreCase = true) }
            ?: return@gitlabCall ForgeResult.Success(null)

        val rawResponse = httpClient.gitlabApi(
            id.forge, token, "projects", projectPath(id), "repository", "files", encodePath(readmeItem.path), "raw",
            query = mapOf("ref" to branch),
        )
        rawResponse.toResult { Readme(readmeItem.path, bodyAsText()) }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = gitlabCall {
        coroutineScope {
            val branchesDeferred = async {
                httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "repository", "branches", query = mapOf("per_page" to "100"))
            }
            val tagsDeferred = async {
                httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "repository", "tags", query = mapOf("per_page" to "100"))
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

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = gitlabCall {
        val query = mutableMapOf("ref" to ref, "per_page" to "100")
        if (path.isNotBlank()) query["path"] = path

        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "repository", "tree", query = query)
            .toResult {
                body<List<GitLabTreeItemJson>>()
                    .map { it.toRepoFile() }
                    .sortedWith(compareBy({ it.type != RepoFileType.DIR }, { it.name.lowercase() }))
            }
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = gitlabCall {
        httpClient.gitlabApi(
            id.forge, token, "projects", projectPath(id), "repository", "files", encodePath(path), "raw",
            query = mapOf("ref" to ref),
        ).toResult { bodyAsText() }
    }

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = gitlabCall {
        val params = mutableMapOf(
            "state" to if (query.open) "opened" else "closed",
            "order_by" to "created_at",
            "sort" to "desc",
            "per_page" to "50",
        )
        if (query.text.isNotBlank()) params["search"] = query.text

        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "issues", query = params)
            .toResult { body<List<GitLabIssueJson>>().map { it.toSummary(id) } }
    }

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = gitlabCall {
        // GitLab lists one state at a time: those no longer open are the merged and the closed, asked together.
        val states = if (query.open) listOf("opened") else listOf("merged", "closed")
        val answers = coroutineScope {
            states.map { state ->
                async {
                    val params = mutableMapOf("state" to state, "order_by" to "created_at", "sort" to "desc", "per_page" to "50")
                    if (query.text.isNotBlank()) params["search"] = query.text
                    httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "merge_requests", query = params)
                }
            }.map { it.await() }
        }
        answers.firstOrNull { it.status != HttpStatusCode.OK }?.let { return@gitlabCall it.failure() }
        ForgeResult.Success(answers.flatMap { it.body<List<GitLabMergeRequestJson>>() }.map { it.toSummary(id) }.sortedByDescending { it.createdAt })
    }

    override suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> =
        ForgeResult.Success(emptyList())

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "releases", query = mapOf("per_page" to "50"))
            .toResult { body<List<GitLabReleaseJson>>().map { it.toModel() } }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "releases", encodePath(tag))
            .toResult { body<GitLabReleaseJson>().toModel() }
    }

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "pipelines", query = mapOf("per_page" to "30"))
            .toResult { body<List<GitLabPipelineJson>>().map { it.toRun() } }
    }

    override fun rawBaseUrl(id: RepoId, ref: String): String = "${id.forge.webUrl}/${id.fullName}/-/raw/$ref/"

    override fun blobBaseUrl(id: RepoId, ref: String): String = "${id.forge.webUrl}/${id.fullName}/-/blob/$ref/"
}
