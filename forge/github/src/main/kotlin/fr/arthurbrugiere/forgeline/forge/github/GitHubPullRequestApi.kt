package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeBlocker
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class GitHubPullRequestApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : PullRequestApi {

    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = forgeCall {
        val response = pull(token, ref, "files", query = mapOf("per_page" to "$FILES_PER_PAGE", "page" to "$page"))
        response.toResult { ChangedFiles(body<List<PrFileJson>>().map { it.toModel() }, nextPage()) }
    }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = forgeCall {
        // GitHub lists at most 250 commits of a pull request, whatever is asked.
        val first = pull(token, ref, "commits", query = mapOf("per_page" to "100"))
        if (first.status != HttpStatusCode.OK) return@forgeCall first.failure()
        val commits = first.body<List<PrCommitJson>>().toMutableList()
        var next = first.nextPage()
        while (next != null && next <= 3) {
            val more = pull(token, ref, "commits", query = mapOf("per_page" to "100", "page" to "$next"))
            if (more.status != HttpStatusCode.OK) return@forgeCall more.failure()
            commits += more.body<List<PrCommitJson>>()
            next = more.nextPage()
        }
        ForgeResult.Success(commits.map { it.toModel() })
    }

    /**
     * GitHub keeps two kinds apart: check runs (Actions and the apps built like it) and statuses (what older services
     * report). Both are about a commit, so the pull request is asked for its latest one first.
     */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = forgeCall {
        val pull = pull(token, ref)
        if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
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

    override suspend fun checkReport(token: String?, repo: RepoId, checkId: Long): ForgeResult<CheckReport> = forgeCall {
        repo(token, repo, "check-runs", checkId.toString()).toResult { body<PrCheckRunJson>().toReport() }
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = forgeCall {
        coroutineScope {
            val pull = async { pull(token, ref) }
            val repo = async { repo(token, ref.repo) }
            val pullResponse = pull.await()
            if (pullResponse.status != HttpStatusCode.OK) return@coroutineScope pullResponse.failure()
            val repoResponse = repo.await()
            if (repoResponse.status != HttpStatusCode.OK) return@coroutineScope repoResponse.failure()
            val settings = repoResponse.body<PrRepoMergeJson>()
            val details = pullResponse.body<PrPullJson>()
            // The repository only tells its merge settings to those who can push; it allows a merge commit by default.
            val methods = listOfNotNull(
                MergeMethod.MERGE.takeIf { settings.allowMergeCommit ?: true },
                MergeMethod.SQUASH.takeIf { settings.allowSquashMerge ?: false },
                MergeMethod.REBASE.takeIf { settings.allowRebaseMerge ?: false },
            )
            val canMerge = settings.permissions?.push == true
            // A branch of the repository itself can go with the merge; one of a fork isn't the repository's to delete,
            // and a repository that deletes merged branches does so whatever is said.
            val ownBranch = details.head.repo?.fullName != null && details.head.repo.fullName.equals(details.base?.repo?.fullName, ignoreCase = true)
            val blockers = when (details.mergeableState) {
                "dirty" -> listOf(MergeBlocker.CONFLICTS)
                "behind" -> listOf(MergeBlocker.BEHIND)
                "blocked" -> blockersOf(token, ref)
                else -> emptyList()
            }
            ForgeResult.Success(
                MergeInfo(
                    details.mergeable, canMerge, methods,
                    deleteBranch = false.takeIf { ownBranch && settings.deleteBranchOnMerge != true },
                    canMergeWhenChecksPass = canMerge && settings.allowAutoMerge == true,
                    mergesWhenChecksPass = details.autoMerge != null,
                    behind = details.mergeableState == "behind",
                    blockers = blockers,
                ),
            )
        }
    }

    /**
     * GitHub's REST API says only that a rule blocks the merge, not which: its GraphQL API says whether the reviews it
     * needs are in and where the last commit's checks stand. A rule neither explains (a ruleset, conversations to
     * resolve) is told as one the forge doesn't name.
     */
    private suspend fun blockersOf(token: String, ref: IssueRef): List<MergeBlocker> {
        val variables = buildJsonObject {
            put("owner", ref.repo.owner)
            put("name", ref.repo.name)
            put("number", ref.number)
        }
        val pull = (graphQl(token, BLOCKERS_QUERY, variables) as? ForgeResult.Success)?.value
            ?.get("repository")?.jsonObject?.get("pullRequest")?.jsonObject
            ?.let { ForgeJson.decodeFromJsonElement<PrStandingJson>(it) }
            ?: return listOf(MergeBlocker.OTHER)
        return listOfNotNull(
            MergeBlocker.CHANGES_REQUESTED.takeIf { pull.reviewDecision == "CHANGES_REQUESTED" },
            MergeBlocker.REVIEWS.takeIf { pull.reviewDecision == "REVIEW_REQUIRED" },
            MergeBlocker.CHECKS.takeIf { pull.commits.nodes.lastOrNull()?.commit?.statusCheckRollup?.state.let { it != null && it != "SUCCESS" } },
        ).ifEmpty { listOf(MergeBlocker.OTHER) }
    }

    /**
     * Merges now through the REST API, then deletes the branch when asked: GitHub's merge doesn't. Merging once the
     * checks pass is GitHub's auto-merge, only in its GraphQL API, by the pull request's node id.
     */
    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean): ForgeResult<Unit> {
        if (whenChecksPass) {
            return onPullNode(token, ref) { id ->
                graphQl(
                    token,
                    "mutation(\$id: ID!, \$method: PullRequestMergeMethod!) { enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: \$method}) { clientMutationId } }",
                    buildJsonObject {
                        put("id", id)
                        put("method", method.name)
                    },
                )
            }
        }
        return forgeCall {
            val merged = pull(token, ref, "merge", method = HttpMethod.Put, body = buildJsonObject { put("merge_method", method.name.lowercase()) })
            if (merged.status != HttpStatusCode.OK) return@forgeCall merged.failure()
            if (!deleteBranch) return@forgeCall ForgeResult.Success(Unit)
            val pull = pull(token, ref)
            if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
            val branch = pull.body<PrPullJson>().head.ref ?: return@forgeCall ForgeResult.Success(Unit)
            repo(token, ref.repo, "git", "refs", "heads", *branch.split('/').toTypedArray(), method = HttpMethod.Delete).toResult { }
        }
    }

    /**
     * Signed in, GitHub's GraphQL API says which threads are resolved and whether the reader may change that. Signed
     * out, the REST API's comments are put back into threads by what each answers; none can be resolved then.
     */
    override suspend fun threads(token: String?, ref: IssueRef): ForgeResult<List<ReviewThread>> {
        if (token == null) {
            return forgeCall {
                pull(null, ref, "comments", query = mapOf("per_page" to "100")).toResult { threadsOf(body<List<PrReviewCommentJson>>()) }
            }
        }
        val variables = buildJsonObject {
            put("owner", ref.repo.owner)
            put("name", ref.repo.name)
            put("number", ref.number)
        }
        return graphQl(token, THREADS_QUERY, variables).map { data ->
            val nodes = data["repository"]?.jsonObject?.get("pullRequest")?.jsonObject?.get("reviewThreads")?.jsonObject?.get("nodes") ?: return@map emptyList()
            ForgeJson.decodeFromJsonElement<List<PrThreadJson>>(nodes).map { it.toModel() }
        }
    }

    /** A reply goes under the thread's first comment, by its id. */
    override suspend fun reply(token: String, ref: IssueRef, thread: ReviewThread, body: String): ForgeResult<Unit> = forgeCall {
        val first = thread.comments.firstOrNull() ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
        pull(token, ref, "comments", first.id.toString(), "replies", method = HttpMethod.Post, body = buildJsonObject { put("body", body) }).toResult { }
    }

    override val resolvesThreads: Boolean = true

    override suspend fun setResolved(token: String, ref: IssueRef, thread: ReviewThread, resolved: Boolean): ForgeResult<Unit> {
        val mutation = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        return graphQl(token, "mutation(\$id: ID!) { $mutation(input: {threadId: \$id}) { thread { id } } }", buildJsonObject { put("id", thread.id) }).map { }
    }

    override val dismissesReviews: Boolean = true

    override suspend fun dismissReview(token: String, ref: IssueRef, reviewId: Long, message: String): ForgeResult<Unit> = forgeCall {
        pull(
            token, ref, "reviews", reviewId.toString(), "dismissals", method = HttpMethod.Put,
            body = buildJsonObject {
                put("message", message)
                put("event", "DISMISS")
            },
        ).toResult { }
    }

    /** GitHub merges the branch it goes into in, later: it answers 202 at once. */
    override suspend fun updateBranch(token: String, ref: IssueRef): ForgeResult<Unit> = forgeCall {
        pull(token, ref, "update-branch", method = HttpMethod.Put, body = buildJsonObject { }).toResult { }
    }

    override suspend fun cancelMergeWhenChecksPass(token: String, ref: IssueRef): ForgeResult<Unit> = onPullNode(token, ref) { id ->
        graphQl(token, "mutation(\$id: ID!) { disablePullRequestAutoMerge(input: {pullRequestId: \$id}) { clientMutationId } }", buildJsonObject { put("id", id) })
    }

    private suspend fun onPullNode(token: String, ref: IssueRef, act: suspend (id: String) -> ForgeResult<JsonObject>): ForgeResult<Unit> {
        val node = forgeCall {
            val pull = pull(token, ref)
            if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
            pull.body<PrPullJson>().nodeId?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }
        return when (node) {
            is ForgeResult.Failure -> node
            is ForgeResult.Success -> act(node.value).map { }
        }
    }

    private suspend fun graphQl(token: String, query: String, variables: JsonObject): ForgeResult<JsonObject> =
        forgeCall { httpClient.gitHubGraphQl(apiBaseUrl, token, query, variables) }

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = forgeCall {
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

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = forgeCall {
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

    /** GitHub reads them from the default branch itself, wherever they are kept (the root, `.github`, `docs`). */
    override suspend fun pullRequestTemplates(token: String, repo: RepoId, branch: String): ForgeResult<List<IssueTemplate>> = graphQl(
        token,
        "query(\$owner: String!, \$name: String!) { repository(owner: \$owner, name: \$name) { pullRequestTemplates { filename body } } }",
        buildJsonObject {
            put("owner", repo.owner)
            put("name", repo.name)
        },
    ).map { data ->
        val templates = (data["repository"] as? JsonObject)?.get("pullRequestTemplates") as? JsonArray ?: return@map emptyList()
        ForgeJson.decodeFromJsonElement<List<PrTemplateJson>>(templates).mapNotNull { template ->
            val file = template.filename ?: return@mapNotNull null
            IssueTemplate(file, file.substringAfterLast('/'), body = template.body.orEmpty())
        }
    }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = forgeCall {
        val query = buildMap {
            put("per_page", "$HISTORY_PER_PAGE")
            put("page", "$page")
            ref?.let { put("sha", it) }
            path?.let { put("path", it) }
        }
        val response = repo(token, repo, "commits", query = query)
        response.toResult { CommitPage(body<List<PrCommitJson>>().map { it.toModel() }, nextPage()) }
    }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = forgeCall {
        repo(token, repo, "commits", sha).toResult { body<PrCommitJson>().let { CommitDetails(it.toModel(), it.files.map { file -> file.toModel() }) } }
    }

    override val supportsBlame: Boolean = true

    /** GitHub's REST API has no blame: its GraphQL one does, for signed-in users only. */
    override suspend fun blame(token: String?, repo: RepoId, ref: String, path: String): ForgeResult<List<BlameRange>> = forgeCall {
        if (token == null) return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val variables = buildJsonObject {
            put("owner", repo.owner)
            put("name", repo.name)
            put("ref", ref)
            put("path", path)
        }
        httpClient.gitHubGraphQl(apiBaseUrl, token, BLAME_QUERY, variables).map { data ->
            // A ref or a path that isn't there answers with nothing under it, not with an error.
            val ranges = ForgeJson.decodeFromJsonElement<PrBlameData>(data).repository?.target?.blame?.ranges
                ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
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

        const val THREADS_QUERY = "query Threads(\$owner: String!, \$name: String!, \$number: Int!) { repository(owner: \$owner, name: \$name) { " +
            "pullRequest(number: \$number) { reviewThreads(first: 100) { nodes { id isResolved isOutdated path line originalLine diffSide " +
            "viewerCanResolve viewerCanUnresolve comments(first: 100) { nodes { databaseId body createdAt diffHunk author { login avatarUrl } } } } } } } }"

        const val BLOCKERS_QUERY = "query Standing(\$owner: String!, \$name: String!, \$number: Int!) { repository(owner: \$owner, name: \$name) { " +
            "pullRequest(number: \$number) { reviewDecision commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } } } }"

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
private data class PrBranchRepoJson(@SerialName("full_name") val fullName: String? = null)

/** Where a pull request's change is ([PullJson.head]) or goes ([PullJson.base]); a deleted fork has no repository. */
@Serializable
private data class PrHeadJson(val sha: String = "", val ref: String? = null, val repo: PrBranchRepoJson? = null)

@Serializable
private data class PrPullJson(
    val head: PrHeadJson,
    val base: PrHeadJson? = null,
    val mergeable: Boolean? = null,
    @SerialName("node_id") val nodeId: String? = null,
    // "behind" when the branch it goes into moved on: one of GitHub's words for what stands in a merge's way.
    @SerialName("mergeable_state") val mergeableState: String? = null,
    // Set while GitHub is to merge it once its checks pass.
    @SerialName("auto_merge") val autoMerge: JsonObject? = null,
)

/** What GitHub's GraphQL API says of a blocked pull request: whether its reviews are in, and its last commit's checks. */
@Serializable
private data class PrStandingJson(val reviewDecision: String? = null, val commits: PrStandingCommitsJson = PrStandingCommitsJson())

@Serializable
private data class PrStandingCommitsJson(val nodes: List<PrStandingNodeJson> = emptyList())

@Serializable
private data class PrStandingNodeJson(val commit: PrStandingCommitJson? = null)

@Serializable
private data class PrStandingCommitJson(val statusCheckRollup: PrRollupJson? = null)

@Serializable
private data class PrRollupJson(val state: String? = null)

@Serializable
private data class PrPermissionsJson(val push: Boolean = false)

@Serializable
private data class PrRepoMergeJson(
    @SerialName("allow_merge_commit") val allowMergeCommit: Boolean? = null,
    @SerialName("allow_squash_merge") val allowSquashMerge: Boolean? = null,
    @SerialName("allow_rebase_merge") val allowRebaseMerge: Boolean? = null,
    @SerialName("allow_auto_merge") val allowAutoMerge: Boolean? = null,
    @SerialName("delete_branch_on_merge") val deleteBranchOnMerge: Boolean? = null,
    val permissions: PrPermissionsJson? = null,
)

@Serializable
private data class PrCheckRunsJson(@SerialName("check_runs") val checkRuns: List<PrCheckRunJson> = emptyList())

private val actionsRun = Regex("""/actions/runs/(\d+)""")

@Serializable
private data class PrCheckOutputJson(val title: String? = null, val summary: String? = null, val text: String? = null)

@Serializable
private data class PrCheckAppJson(val name: String? = null)

@Serializable
private data class PrCheckRunJson(
    val name: String,
    val status: String = "completed",
    val conclusion: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("details_url") val detailsUrl: String? = null,
    val output: PrCheckOutputJson? = null,
    val id: Long? = null,
    val app: PrCheckAppJson? = null,
    @SerialName("completed_at") val completedAt: String? = null,
) {
    private val state
        get() = when {
            status != "completed" -> CheckState.PENDING
            conclusion == "success" -> CheckState.SUCCESS
            conclusion == "skipped" || conclusion == "neutral" -> CheckState.SKIPPED
            else -> CheckState.FAILURE
        }

    fun toModel() = Check(
        name = name,
        state = state,
        description = output?.title,
        url = htmlUrl ?: detailsUrl,
        // An Actions job says which run it is part of in its address: the app shows that run itself.
        runId = (htmlUrl ?: detailsUrl)?.let { actionsRun.find(it)?.groupValues?.get(1)?.toLongOrNull() },
        checkId = id,
    )

    fun toReport() = CheckReport(
        name = name,
        state = state,
        app = app?.name,
        title = output?.title,
        summary = output?.summary?.trim()?.ifEmpty { null },
        text = output?.text?.trim()?.ifEmpty { null },
        completedAt = completedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        // The service's own page is where the forge's page for the check would only send on to.
        detailsUrl = detailsUrl?.takeIf { it != htmlUrl },
        url = htmlUrl,
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

/** A comment on a line, as GitHub's REST API lists them: a reply names the comment it answers. */
@Serializable
private data class PrReviewCommentJson(
    val id: Long,
    @SerialName("in_reply_to_id") val inReplyTo: Long? = null,
    val path: String,
    val line: Int? = null,
    @SerialName("original_line") val originalLine: Int? = null,
    val side: String? = null,
    @SerialName("diff_hunk") val diffHunk: String? = null,
    val user: PrAccountJson? = null,
    val body: String = "",
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toComment() = ThreadComment(id, user?.let { ForgeUser(it.login, null, it.avatarUrl) }, body, createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() })
}

/** Each first comment with the replies under it, in the order GitHub listed them (oldest first). */
private fun threadsOf(comments: List<PrReviewCommentJson>): List<ReviewThread> {
    val replies = comments.filter { it.inReplyTo != null }.groupBy { it.inReplyTo }
    return comments.filter { it.inReplyTo == null }.map { first ->
        ReviewThread(
            id = first.id.toString(),
            path = first.path,
            line = first.line ?: first.originalLine,
            onOldSide = first.side == "LEFT",
            hunk = first.diffHunk,
            comments = listOf(first.toComment()) + replies[first.id].orEmpty().map { it.toComment() },
            resolved = false,
            canResolve = false,
            outdated = first.line == null,
        )
    }
}

@Serializable
private data class PrGraphAuthorJson(val login: String, val avatarUrl: String? = null)

@Serializable
private data class PrGraphCommentJson(val databaseId: Long, val body: String = "", val createdAt: String? = null, val diffHunk: String? = null, val author: PrGraphAuthorJson? = null)

@Serializable
private data class PrGraphCommentsJson(val nodes: List<PrGraphCommentJson> = emptyList())

@Serializable
private data class PrThreadJson(
    val id: String,
    val isResolved: Boolean = false,
    val isOutdated: Boolean = false,
    val path: String,
    val line: Int? = null,
    val originalLine: Int? = null,
    val diffSide: String? = null,
    val viewerCanResolve: Boolean = false,
    val viewerCanUnresolve: Boolean = false,
    val comments: PrGraphCommentsJson = PrGraphCommentsJson(),
) {
    fun toModel() = ReviewThread(
        id = id,
        path = path,
        line = line ?: originalLine,
        onOldSide = diffSide == "LEFT",
        hunk = comments.nodes.firstOrNull()?.diffHunk,
        comments = comments.nodes.map { comment ->
            ThreadComment(comment.databaseId, comment.author?.let { ForgeUser(it.login, null, it.avatarUrl) }, comment.body, comment.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() })
        },
        resolved = isResolved,
        canResolve = if (isResolved) viewerCanUnresolve else viewerCanResolve,
        outdated = isOutdated,
    )
}

@Serializable
private data class PrTemplateJson(val filename: String? = null, val body: String? = null)
