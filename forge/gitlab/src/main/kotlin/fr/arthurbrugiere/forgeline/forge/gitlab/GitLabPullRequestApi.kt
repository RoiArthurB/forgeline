package fr.arthurbrugiere.forgeline.forge.gitlab

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
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.countChanges
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A GitLab instance's merge requests and history. */
class GitLabPullRequestApi(private val httpClient: HttpClient, private val forge: ForgeInstance = ForgeInstance.GitLab) : PullRequestApi {

    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = gitlabCall {
        val response = mergeRequest(token, ref, "diffs", query = mapOf("per_page" to "$FILES_PER_PAGE", "page" to "$page"))
        response.toResult { ChangedFiles(body<List<MrDiffJson>>().map { it.toModel() }, nextPage()) }
    }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = gitlabCall {
        val commits = mutableListOf<MrCommitJson>()
        var page: Int? = 1
        while (page != null && page <= MAX_PAGES) {
            val response = mergeRequest(token, ref, "commits", query = mapOf("per_page" to "100", "page" to "$page"))
            if (!response.status.isSuccess()) return@gitlabCall response.failure()
            commits += response.body<List<MrCommitJson>>()
            page = response.nextPage()
        }
        // GitLab lists a merge request's commits newest first.
        ForgeResult.Success(commits.map { it.toModel() }.reversed())
    }

    /**
     * The jobs of the latest pipeline run on the merge request; a job opens its pipeline, which the app shows as a run.
     * Where the jobs can't be listed (signed out, or a pipeline that ran in a fork), the pipeline stands for them.
     */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = gitlabCall {
        val mr = mergeRequest(token, ref)
        if (!mr.status.isSuccess()) return@gitlabCall mr.failure()
        val pipeline = mr.body<MrJson>().headPipeline ?: return@gitlabCall ForgeResult.Success(emptyList())
        val jobs = project(token, ref.repo, "pipelines", pipeline.id.toString(), "jobs", query = mapOf("per_page" to "100"))
        if (!jobs.status.isSuccess()) {
            return@gitlabCall ForgeResult.Success(listOf(Check(PIPELINE, checkState(pipeline.status), null, pipeline.webUrl)))
        }
        ForgeResult.Success(jobs.body<List<MrJobJson>>().sortedBy { it.id }.map { it.toModel(pipeline.id) })
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = gitlabCall {
        coroutineScope {
            val mr = async { mergeRequest(token, ref) }
            val settings = async { project(token, ref.repo) }
            val mrResponse = mr.await()
            if (!mrResponse.status.isSuccess()) return@coroutineScope mrResponse.failure()
            val projectResponse = settings.await()
            if (!projectResponse.status.isSuccess()) return@coroutineScope projectResponse.failure()
            val details = mrResponse.body<MrJson>()
            // How a merge lands (a merge commit, a rebase, a fast-forward) is the project's setting, not a choice at
            // merge time: what can be chosen is whether the commits are squashed into one first.
            val methods = when (projectResponse.body<MrProjectJson>().squashOption) {
                "never" -> listOf(MergeMethod.MERGE)
                "always" -> listOf(MergeMethod.SQUASH)
                "default_on" -> listOf(MergeMethod.SQUASH, MergeMethod.MERGE)
                else -> listOf(MergeMethod.MERGE, MergeMethod.SQUASH)
            }
            val mergeable = when (details.detailedMergeStatus ?: details.mergeStatus) {
                "mergeable", "can_be_merged" -> true
                "checking", "unchecked", "preparing", "approvals_syncing", null -> null
                else -> false
            }
            ForgeResult.Success(MergeInfo(mergeable, details.user?.canMerge == true, methods))
        }
    }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod): ForgeResult<Unit> = gitlabCall {
        mergeRequest(token, ref, "merge", method = HttpMethod.Put, body = buildJsonObject { put("squash", method == MergeMethod.SQUASH) }).toResult { }
    }

    /** GitLab's API approves and comments; asking for changes is only in its own pages. */
    override val verdicts: Set<ReviewVerdict> = setOf(ReviewVerdict.COMMENT, ReviewVerdict.APPROVE)

    /**
     * GitLab has no review to send as one: the remarks on lines go first, each a discussion of its own, then what the
     * review says, then the approval, so that an approval is never left standing without what came with it.
     */
    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = gitlabCall {
        if (verdict == ReviewVerdict.REQUEST_CHANGES) return@gitlabCall ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported)
        if (comments.isNotEmpty()) {
            val mr = mergeRequest(token, ref)
            if (!mr.status.isSuccess()) return@gitlabCall mr.failure()
            val refs = mr.body<MrJson>().diffRefs ?: return@gitlabCall ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unreadable)
            comments.forEach { comment ->
                val sent = mergeRequest(
                    token, ref, "discussions", method = HttpMethod.Post,
                    body = buildJsonObject {
                        put("body", comment.body)
                        put(
                            "position",
                            buildJsonObject {
                                put("position_type", "text")
                                put("base_sha", refs.baseSha)
                                put("start_sha", refs.startSha)
                                put("head_sha", refs.headSha)
                                put("new_path", comment.path)
                                put("old_path", comment.previousPath ?: comment.path)
                                // A line the change leaves alone is told by both its numbers.
                                comment.newLine?.let { put("new_line", it) }
                                comment.oldLine?.let { put("old_line", it) }
                            },
                        )
                    },
                )
                if (!sent.status.isSuccess()) return@gitlabCall sent.failure()
            }
        }
        if (body.isNotBlank()) {
            val note = mergeRequest(token, ref, "notes", method = HttpMethod.Post, body = buildJsonObject { put("body", body) })
            if (!note.status.isSuccess()) return@gitlabCall note.failure()
        }
        if (verdict == ReviewVerdict.APPROVE) {
            val approved = mergeRequest(token, ref, "approve", method = HttpMethod.Post)
            if (!approved.status.isSuccess()) return@gitlabCall approved.failure()
        }
        ForgeResult.Success(Unit)
    }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = gitlabCall {
        project(
            token, repo, "merge_requests", method = HttpMethod.Post,
            body = buildJsonObject {
                // A draft is a title that says so.
                put("title", if (request.draft && !request.title.startsWith(DRAFT_PREFIX, ignoreCase = true)) "$DRAFT_PREFIX ${request.title}" else request.title)
                put("description", request.body)
                put("source_branch", request.head)
                put("target_branch", request.base)
            },
        ).toResult { IssueRef(repo, body<MrCreatedJson>().iid, isPullRequest = true) }
    }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = gitlabCall {
        val query = buildMap {
            put("per_page", "$HISTORY_PER_PAGE")
            put("page", "$page")
            ref?.let { put("ref_name", it) }
            path?.let { put("path", it) }
        }
        val response = project(token, repo, "repository", "commits", query = query)
        response.toResult { CommitPage(body<List<MrCommitJson>>().map { it.toModel() }, nextPage()) }
    }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = gitlabCall {
        coroutineScope {
            val diff = async { project(token, repo, "repository", "commits", sha, "diff", query = mapOf("per_page" to "100")) }
            val commit = project(token, repo, "repository", "commits", sha)
            if (!commit.status.isSuccess()) return@coroutineScope commit.failure()
            val diffResponse = diff.await()
            if (!diffResponse.status.isSuccess()) return@coroutineScope diffResponse.failure()
            ForgeResult.Success(CommitDetails(commit.body<MrCommitJson>().toModel(), diffResponse.body<List<MrDiffJson>>().map { it.toModel() }))
        }
    }

    override val supportsBlame: Boolean = true

    /** GitLab answers runs of lines with the commit that left them, in the file's order: their numbers are counted here. */
    override suspend fun blame(token: String?, repo: RepoId, ref: String, path: String): ForgeResult<List<BlameRange>> = gitlabCall {
        project(token, repo, "repository", "files", encodePath(path), "blame", query = mapOf("ref" to ref)).toResult {
            var line = 1
            body<List<MrBlameJson>>().filter { it.lines.isNotEmpty() }.map { range ->
                BlameRange(line, line + range.lines.size - 1, range.commit.toModel()).also { line += range.lines.size }
            }
        }
    }

    private suspend fun project(
        token: String?, id: RepoId, vararg segments: String, method: HttpMethod = HttpMethod.Get, query: Map<String, String> = emptyMap(), body: JsonObject? = null,
    ): HttpResponse = httpClient.gitlabApi(id.forge, token, "projects", encodePath(id.fullName), *segments, method = method, query = query, body = body)

    private suspend fun mergeRequest(
        token: String?, ref: IssueRef, vararg segments: String, method: HttpMethod = HttpMethod.Get, query: Map<String, String> = emptyMap(), body: JsonObject? = null,
    ): HttpResponse = project(token, ref.repo, "merge_requests", ref.number.toString(), *segments, method = method, query = query, body = body)

    private companion object {
        const val FILES_PER_PAGE = 50
        const val HISTORY_PER_PAGE = 30
        const val MAX_PAGES = 5
        const val DRAFT_PREFIX = "Draft:"

        /** What GitLab itself calls the whole of a merge request's checks. */
        const val PIPELINE = "Pipeline"
    }
}

@Serializable
private data class MrDiffJson(
    @SerialName("old_path") val oldPath: String,
    @SerialName("new_path") val newPath: String,
    val diff: String = "",
    @SerialName("new_file") val newFile: Boolean = false,
    @SerialName("renamed_file") val renamedFile: Boolean = false,
    @SerialName("deleted_file") val deletedFile: Boolean = false,
) {
    fun toModel(): ChangedFile {
        // Nothing to read for a file that isn't text, or whose change is too large for GitLab to send.
        val patch = diff.takeIf { it.isNotBlank() }
        val (added, removed) = patch?.let(::countChanges) ?: (0 to 0)
        return ChangedFile(
            path = if (deletedFile) oldPath else newPath,
            previousPath = oldPath.takeIf { renamedFile },
            change = when {
                newFile -> FileChange.ADDED
                deletedFile -> FileChange.REMOVED
                renamedFile -> FileChange.RENAMED
                else -> FileChange.MODIFIED
            },
            additions = added,
            deletions = removed,
            patch = patch,
        )
    }
}

@Serializable
private data class MrCommitJson(
    val id: String,
    val title: String? = null,
    val message: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("authored_date") val authoredDate: String? = null,
    @SerialName("committed_date") val committedDate: String? = null,
) {
    // GitLab names who wrote a commit, not their account.
    fun toModel() = Commit(id, message ?: title.orEmpty(), authorName, null, gitlabInstant(authoredDate ?: committedDate))
}

@Serializable
private data class MrBlameJson(val commit: MrCommitJson, val lines: List<String> = emptyList())

@Serializable
private data class MrPipelineJson(val id: Long, val status: String = "", @SerialName("web_url") val webUrl: String? = null)

@Serializable
private data class MrDiffRefsJson(@SerialName("base_sha") val baseSha: String, @SerialName("head_sha") val headSha: String, @SerialName("start_sha") val startSha: String)

@Serializable
private data class MrUserJson(@SerialName("can_merge") val canMerge: Boolean = false)

@Serializable
private data class MrJson(
    @SerialName("head_pipeline") val headPipeline: MrPipelineJson? = null,
    @SerialName("diff_refs") val diffRefs: MrDiffRefsJson? = null,
    @SerialName("merge_status") val mergeStatus: String? = null,
    @SerialName("detailed_merge_status") val detailedMergeStatus: String? = null,
    val user: MrUserJson? = null,
)

@Serializable
private data class MrProjectJson(@SerialName("squash_option") val squashOption: String? = null)

@Serializable
private data class MrJobJson(val id: Long, val name: String, val status: String, val stage: String? = null, @SerialName("web_url") val webUrl: String? = null) {
    fun toModel(pipelineId: Long) = Check(
        name = name,
        state = checkState(status),
        description = stage,
        url = webUrl,
        runId = pipelineId,
    )
}

private fun checkState(status: String) = when (status) {
    "success" -> CheckState.SUCCESS
    "failed" -> CheckState.FAILURE
    // Not run, by choice: skipped, cancelled, or waiting for someone to start it by hand.
    "skipped", "canceled", "manual" -> CheckState.SKIPPED
    else -> CheckState.PENDING
}

@Serializable
private data class MrCreatedJson(val iid: Int)
