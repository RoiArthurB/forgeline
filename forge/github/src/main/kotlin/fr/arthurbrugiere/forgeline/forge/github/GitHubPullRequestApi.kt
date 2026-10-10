package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class GitHubPullRequestApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : PullRequestApi {

    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = gitHubCall {
        val response = pull(token, ref, "files", query = mapOf("per_page" to "$FILES_PER_PAGE", "page" to "$page"))
        response.toResult { ChangedFiles(body<List<PrFileJson>>().map { it.toModel() }, nextPage()) }
    }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = gitHubCall {
        // GitHub lists at most 250 commits of a pull request, whatever is asked.
        val first = pull(token, ref, "commits", query = mapOf("per_page" to "100"))
        if (first.status != HttpStatusCode.OK) return@gitHubCall first.failure()
        val commits = first.body<List<PrCommitJson>>().toMutableList()
        var next = first.nextPage()
        while (next != null && next <= 3) {
            val more = pull(token, ref, "commits", query = mapOf("per_page" to "100", "page" to "$next"))
            if (more.status != HttpStatusCode.OK) return@gitHubCall more.failure()
            commits += more.body<List<PrCommitJson>>()
            next = more.nextPage()
        }
        ForgeResult.Success(commits.map { it.toModel() })
    }

    /**
     * GitHub keeps two kinds apart: check runs (Actions and the apps built like it) and statuses (what older services
     * report). Both are about a commit, so the pull request is asked for its latest one first.
     */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = gitHubCall {
        val pull = pull(token, ref)
        if (pull.status != HttpStatusCode.OK) return@gitHubCall pull.failure()
        val sha = pull.body<PrPullJson>().head.sha
        coroutineScope {
            val runs = async { repo(token, ref.repo, "commits", sha, "check-runs", query = mapOf("per_page" to "100")) }
            val statuses = async { repo(token, ref.repo, "commits", sha, "status", query = mapOf("per_page" to "100")) }
            val runsResponse = runs.await()
            if (runsResponse.status != HttpStatusCode.OK) return@coroutineScope runsResponse.failure()
            // Statuses are the lesser half: a repository that can't be asked for them still shows its check runs.
            val reported = statuses.await().takeIf { it.status == HttpStatusCode.OK }?.body<PrCombinedStatusJson>()?.statuses.orEmpty()
            ForgeResult.Success(runsResponse.body<PrCheckRunsJson>().checkRuns.map { it.toModel() } + reported.map { it.toModel() })
        }
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = gitHubCall {
        coroutineScope {
            val pull = async { pull(token, ref) }
            val repo = async { repo(token, ref.repo) }
            val pullResponse = pull.await()
            if (pullResponse.status != HttpStatusCode.OK) return@coroutineScope pullResponse.failure()
            val repoResponse = repo.await()
            if (repoResponse.status != HttpStatusCode.OK) return@coroutineScope repoResponse.failure()
            val settings = repoResponse.body<PrRepoMergeJson>()
            // The repository only tells its merge settings to those who can push; it allows a merge commit by default.
            val methods = listOfNotNull(
                MergeMethod.MERGE.takeIf { settings.allowMergeCommit ?: true },
                MergeMethod.SQUASH.takeIf { settings.allowSquashMerge ?: false },
                MergeMethod.REBASE.takeIf { settings.allowRebaseMerge ?: false },
            )
            ForgeResult.Success(MergeInfo(pullResponse.body<PrPullJson>().mergeable, settings.permissions?.push == true, methods))
        }
    }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod): ForgeResult<Unit> = gitHubCall {
        pull(
            token, ref, "merge", method = HttpMethod.Put,
            body = buildJsonObject { put("merge_method", method.name.lowercase()) },
        ).toResult { }
    }

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = gitHubCall {
        pull(
            token, ref, "reviews", method = HttpMethod.Post,
            body = buildJsonObject {
                put("event", verdict.name)
                if (body.isNotBlank()) put("body", body)
                if (comments.isNotEmpty()) {
                    put(
                        "comments",
                        buildJsonArray {
                            comments.forEach { comment ->
                                add(
                                    buildJsonObject {
                                        put("path", comment.path)
                                        put("line", comment.newLine ?: comment.oldLine ?: 1)
                                        put("side", if (comment.onOldSide) "LEFT" else "RIGHT")
                                        put("body", comment.body)
                                    },
                                )
                            }
                        },
                    )
                }
            },
        ).toResult { }
    }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = gitHubCall {
        repo(
            token, repo, "pulls", method = HttpMethod.Post,
            body = buildJsonObject {
                put("title", request.title)
                put("body", request.body)
                put("head", request.head)
                put("base", request.base)
                put("draft", request.draft)
            },
        ).toResult { IssueRef(repo, body<PrCreatedJson>().number, isPullRequest = true) }
    }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = gitHubCall {
        val query = buildMap {
            put("per_page", "$HISTORY_PER_PAGE")
            put("page", "$page")
            ref?.let { put("sha", it) }
            path?.let { put("path", it) }
        }
        val response = repo(token, repo, "commits", query = query)
        response.toResult { CommitPage(body<List<PrCommitJson>>().map { it.toModel() }, nextPage()) }
    }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = gitHubCall {
        repo(token, repo, "commits", sha).toResult { body<PrCommitJson>().let { CommitDetails(it.toModel(), it.files.map { file -> file.toModel() }) } }
    }

    override val supportsBlame: Boolean = true

    /** GitHub's REST API has no blame: its GraphQL one does, for signed-in users only. */
    override suspend fun blame(token: String?, repo: RepoId, ref: String, path: String): ForgeResult<List<BlameRange>> = gitHubCall {
        if (token == null) return@gitHubCall ForgeResult.Failure(ForgeError.Unauthorized)
        httpClient.gitHubApi(
            apiBaseUrl, token, "graphql", method = HttpMethod.Post,
            body = buildJsonObject {
                put("query", BLAME_QUERY)
                put(
                    "variables",
                    buildJsonObject {
                        put("owner", repo.owner)
                        put("name", repo.name)
                        put("ref", ref)
                        put("path", path)
                    },
                )
            },
        ).toResult {
            // A ref or a path that isn't there answers with nothing under it, not with an error.
            val ranges = body<PrBlameResponse>().data?.repository?.target?.blame?.ranges ?: return@gitHubCall ForgeResult.Failure(ForgeError.Http(404, null))
            ranges.map { it.toModel() }
        }
    }

    private suspend fun repo(
        token: String?, id: RepoId, vararg segments: String, method: HttpMethod = HttpMethod.Get, query: Map<String, String> = emptyMap(), body: JsonObject? = null,
    ): HttpResponse = httpClient.gitHubApi(apiBaseUrl, token, "repos", id.owner, id.name, *segments, method = method, query = query, body = body)

    private suspend fun pull(
        token: String?, ref: IssueRef, vararg segments: String, method: HttpMethod = HttpMethod.Get, query: Map<String, String> = emptyMap(), body: JsonObject? = null,
    ): HttpResponse = repo(token, ref.repo, "pulls", ref.number.toString(), *segments, method = method, query = query, body = body)

    private companion object {
        const val FILES_PER_PAGE = 50
        const val HISTORY_PER_PAGE = 30

        const val BLAME_QUERY = "query Blame(\$owner: String!, \$name: String!, \$ref: String!, \$path: String!) { " +
            "repository(owner: \$owner, name: \$name) { target: object(expression: \$ref) { ... on Commit { blame(path: \$path) { ranges { " +
            "startingLine endingLine commit { oid message authoredDate author { name user { login avatarUrl } } } } } } } } }"
    }
}

@Serializable
private data class PrFileJson(
    val filename: String,
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
) {
    fun toModel() = ChangedFile(
        path = filename,
        previousPath = previousFilename,
        change = when (status) {
            "added", "copied" -> FileChange.ADDED
            "removed" -> FileChange.REMOVED
            "renamed" -> FileChange.RENAMED
            else -> FileChange.MODIFIED
        },
        additions = additions,
        deletions = deletions,
        patch = patch,
    )
}

@Serializable
private data class PrAccountJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
private data class PrGitPersonJson(val name: String? = null, val date: String? = null)

@Serializable
private data class PrGitCommitJson(val message: String = "", val author: PrGitPersonJson? = null, val committer: PrGitPersonJson? = null)

@Serializable
private data class PrCommitJson(val sha: String, val commit: PrGitCommitJson, val author: PrAccountJson? = null, val files: List<PrFileJson> = emptyList()) {
    fun toModel() = Commit(
        sha = sha,
        message = commit.message,
        authorName = commit.author?.name,
        author = author?.let { ForgeUser(it.login, null, it.avatarUrl) },
        date = (commit.author?.date ?: commit.committer?.date)?.let { runCatching { Instant.parse(it) }.getOrNull() },
    )
}

@Serializable
private data class PrHeadJson(val sha: String)

@Serializable
private data class PrPullJson(val head: PrHeadJson, val mergeable: Boolean? = null)

@Serializable
private data class PrPermissionsJson(val push: Boolean = false)

@Serializable
private data class PrRepoMergeJson(
    @SerialName("allow_merge_commit") val allowMergeCommit: Boolean? = null,
    @SerialName("allow_squash_merge") val allowSquashMerge: Boolean? = null,
    @SerialName("allow_rebase_merge") val allowRebaseMerge: Boolean? = null,
    val permissions: PrPermissionsJson? = null,
)

@Serializable
private data class PrCheckRunsJson(@SerialName("check_runs") val checkRuns: List<PrCheckRunJson> = emptyList())

private val actionsRun = Regex("""/actions/runs/(\d+)""")

@Serializable
private data class PrCheckOutputJson(val title: String? = null)

@Serializable
private data class PrCheckRunJson(
    val name: String,
    val status: String = "completed",
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("details_url") val detailsUrl: String? = null,
    val output: PrCheckOutputJson? = null,
) {
    fun toModel() = Check(
        name = name,
        state = when {
            status != "completed" -> CheckState.PENDING
            conclusion == "success" -> CheckState.SUCCESS
            conclusion == "skipped" || conclusion == "neutral" -> CheckState.SKIPPED
            else -> CheckState.FAILURE
        },
        description = output?.title,
        url = htmlUrl ?: detailsUrl,
        // An Actions job says which run it is part of in its address: the app shows that run itself.
        runId = (htmlUrl ?: detailsUrl)?.let { actionsRun.find(it)?.groupValues?.get(1)?.toLongOrNull() },
    )
}

@Serializable
private data class PrCombinedStatusJson(val statuses: List<PrStatusJson> = emptyList())

@Serializable
private data class PrStatusJson(val context: String, val state: String, val description: String? = null, @SerialName("target_url") val targetUrl: String? = null) {
    fun toModel() = Check(
        name = context,
        state = when (state) {
            "success" -> CheckState.SUCCESS
            "pending" -> CheckState.PENDING
            else -> CheckState.FAILURE
        },
        description = description,
        url = targetUrl,
    )
}

@Serializable
private data class PrBlameResponse(val data: PrBlameData? = null)

@Serializable
private data class PrBlameData(val repository: PrBlameRepository? = null)

@Serializable
private data class PrBlameRepository(val target: PrBlameTarget? = null)

@Serializable
private data class PrBlameTarget(val blame: PrBlame? = null)

@Serializable
private data class PrBlame(val ranges: List<PrBlameRangeJson> = emptyList())

@Serializable
private data class PrBlameUser(val login: String, val avatarUrl: String? = null)

@Serializable
private data class PrBlameAuthor(val name: String? = null, val user: PrBlameUser? = null)

@Serializable
private data class PrBlameCommit(val oid: String, val message: String = "", val authoredDate: String? = null, val author: PrBlameAuthor? = null)

@Serializable
private data class PrBlameRangeJson(val startingLine: Int, val endingLine: Int, val commit: PrBlameCommit) {
    fun toModel() = BlameRange(
        startingLine,
        endingLine,
        Commit(
            sha = commit.oid,
            message = commit.message,
            authorName = commit.author?.name,
            author = commit.author?.user?.let { ForgeUser(it.login, null, it.avatarUrl) },
            date = commit.authoredDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
        ),
    )
}

@Serializable
private data class PrCreatedJson(val number: Int)
