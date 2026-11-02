package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.suggestionApplied
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.DeletedBranch
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import fr.arthurbrugiere.forgeline.core.model.parseUnifiedDiff
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.forgejo.api.CombinedStatus
import fr.arthurbrugiere.forgeline.forge.forgejo.api.CommitStatus
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Compare
import fr.arthurbrugiere.forgeline.forge.forgejo.api.ContentsResponse
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PRBranchInfo
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PullRequest
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PullReview
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PullReviewComment
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Repository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Commit as ApiCommit

/**
 * A Forgejo instance's pull requests and history. Its API lists the files of a change without their changes, so those
 * are read from the whole diff it serves beside (`pulls/12.diff`, `git/commits/<sha>.diff`), in one request.
 */
class ForgejoPullRequestApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : PullRequestApi {

    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = forgeCall {
        // The diff holds every file at once: there is no second page.
        if (page > 1) return@forgeCall ForgeResult.Success(ChangedFiles(emptyList(), null))
        repo(token, ref.repo, "pulls", "${ref.number}.diff", patienceMillis = DIFF_PATIENCE_MILLIS)
            .toResult { ChangedFiles(parseUnifiedDiff(bodyAsText()), null) }
    }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = forgeCall {
        val commits = mutableListOf<ApiCommit>()
        var page: Int? = 1
        while (page != null && page <= MAX_COMMIT_PAGES) {
            val response = repo(token, ref.repo, "pulls", ref.number.toString(), "commits", query = mapOf("limit" to "50", "page" to "$page") + LIGHT)
            if (response.status != HttpStatusCode.OK) return@forgeCall response.failure()
            commits += response.body<List<ApiCommit>>()
            page = response.nextPage()
        }
        ForgeResult.Success(commits.map { it.toModel() })
    }

    /** Forgejo keeps one kind: the statuses reported on a commit, its own Actions' among them. */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = forgeCall {
        val pull = repo(token, ref.repo, "pulls", ref.number.toString())
        if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
        val sha = pull.body<PullRequest>().head?.sha.orEmpty()
        repo(token, ref.repo, "commits", sha, "status").toResult { body<CombinedStatus>().statuses.orEmpty().map { it.toModel(forge) } }
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = forgeCall {
        coroutineScope {
            val pull = async { repo(token, ref.repo, "pulls", ref.number.toString()) }
            val settings = async { repo(token, ref.repo) }
            val pullResponse = pull.await()
            if (pullResponse.status != HttpStatusCode.OK) return@coroutineScope pullResponse.failure()
            val repoResponse = settings.await()
            if (repoResponse.status != HttpStatusCode.OK) return@coroutineScope repoResponse.failure()
            val repo = repoResponse.body<Repository>()
            val methods = listOfNotNull(
                MergeMethod.MERGE.takeIf { repo.allowMergeCommits != false },
                MergeMethod.SQUASH.takeIf { repo.allowSquashMerge == true },
                MergeMethod.REBASE.takeIf { repo.allowRebase == true },
            )
            // The repository's own choice first, when it names one of these.
            val preferred = MergeMethod.entries.firstOrNull { it.name.lowercase() == repo.defaultMergeStyle }
            val details = pullResponse.body<PullRequest>()
            val head = required(details.head, "a pull request's head")
            val base = details.base
            val canMerge = repo.permissions?.push == true
            // A branch of the repository itself can go with the merge; a fork's isn't the repository's to delete.
            val ownBranch = head.repoId != null && head.repoId == base?.repoId
            // Forgejo doesn't say whether the target moved on: what it has that the change's branch lacks is compared,
            // for a branch of the repository itself. Unknown counts as up to date.
            val behind = if (ownBranch && canMerge && head.ref != null && base?.ref != null) {
                // The comparison is the rest of the path, slashes in branch names and all.
                val compared = repo(token, ref.repo, "compare", *"${head.ref}...${base.ref}".split('/').toTypedArray())
                compared.status == HttpStatusCode.OK && compared.body<Compare>().totalCommits.count() > 0
            } else {
                false
            }
            ForgeResult.Success(
                MergeInfo(
                    details.mergeable, canMerge, methods.sortedByDescending { it == preferred },
                    deleteBranch = (repo.defaultDeleteBranchAfterMerge == true).takeIf { ownBranch },
                    // Forgejo schedules a merge for when the checks succeed, and doesn't say afterwards that it did.
                    canMergeWhenChecksPass = canMerge,
                    behind = behind,
                    deletedBranch = if (ownBranch && canMerge && details.state == "closed") deletedBranch(token, ref.repo, head) else null,
                ),
            )
        }
    }

    /** [head], when its branch is no longer there: asked for by its name, it is not found. */
    private suspend fun deletedBranch(token: String, repo: RepoId, head: PRBranchInfo): DeletedBranch? {
        val name = head.branch ?: return null
        val sha = head.sha?.ifEmpty { null } ?: return null
        val branch = repo(token, repo, "branches", *name.split('/').toTypedArray())
        return DeletedBranch(name, sha).takeIf { branch.status == HttpStatusCode.NotFound }
    }

    /** A new branch made from the commit the old one stood at. */
    override suspend fun restoreBranch(token: String, ref: IssueRef, branch: DeletedBranch): ForgeResult<Unit> = forgeCall {
        repo(
            token, ref.repo, "branches", method = HttpMethod.Post,
            body = buildJsonObject {
                put("new_branch_name", branch.name)
                put("old_ref_name", branch.sha)
            },
        ).toResult { }
    }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean): ForgeResult<Unit> = forgeCall {
        repo(
            token, ref.repo, "pulls", ref.number.toString(), "merge", method = HttpMethod.Post,
            body = buildJsonObject {
                put("Do", method.name.lowercase())
                put("delete_branch_after_merge", deleteBranch)
                if (whenChecksPass) put("merge_when_checks_succeed", true)
            },
        ).toResult { }
    }

    /**
     * Forgejo keeps remarks on lines under the reviews they were sent with, replies included: every review that has
     * some is asked for them, at once, and they are put back into threads by the line they are on, oldest first.
     */
    override suspend fun threads(token: String?, ref: IssueRef): ForgeResult<List<ReviewThread>> = forgeCall {
        coroutineScope {
            val reviews = repo(token, ref.repo, "pulls", ref.number.toString(), "reviews")
            if (reviews.status != HttpStatusCode.OK) return@coroutineScope reviews.failure()
            val answers = reviews.body<List<PullReview>>().filter { it.commentsCount.count() > 0 }.map { review ->
                async { repo(token, ref.repo, "pulls", ref.number.toString(), "reviews", required(review.id, "a review's id").toString(), "comments") }
            }.map { it.await() }
            answers.firstOrNull { it.status != HttpStatusCode.OK }?.let { return@coroutineScope it.failure() }
            val remarks = answers.flatMap { it.body<List<PullReviewComment>>() }.sortedBy { instant(it.createdAt) }
            val threads = remarks.groupBy { Triple(it.path, it.position ?: 0, it.originalPosition ?: 0) }.values.map { it.toThread() }
            // Each remark's reactions are an issue comment's, asked of each alongside the others.
            val reactions = threads.flatMap { it.comments }.map { comment -> async { comment.id to httpClient.forgejoCommentReactions(forge, token, ref.repo, comment.id) } }
                .awaitAll().toMap()
            ForgeResult.Success(threads.map { thread -> thread.copy(comments = thread.comments.map { it.copy(reactions = reactions[it.id].orEmpty()) }) })
        }
    }

    /** Forgejo's API has no reply as such: a remark on the same line, sent as a review of its own, joins the thread. */
    override suspend fun reply(token: String, ref: IssueRef, thread: ReviewThread, body: String): ForgeResult<Unit> {
        val line = thread.line ?: return ForgeResult.Failure(ForgeError.Unsupported)
        val remark = LineComment(thread.path, oldLine = line.takeIf { thread.onOldSide }, newLine = line.takeUnless { thread.onOldSide }, body = body)
        return review(token, ref, ReviewVerdict.COMMENT, "", listOf(remark))
    }

    override val reactsToThreadComments: Boolean = true

    /** As an issue comment's: Forgejo keeps a remark on a line as one. */
    override suspend fun toggleThreadReaction(token: String, login: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, reaction: Reaction): ForgeResult<Map<Reaction, Int>> =
        ForgejoIssueApi(httpClient, forge).toggleReaction(token, login, ref, comment.id, reaction)

    override val editsThreadComments: Boolean = true

    /** A remark on a line is an issue comment to Forgejo: its own endpoints rewrite it. */
    override suspend fun editThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, body: String): ForgeResult<Unit> = forgeCall {
        repo(token, ref.repo, "issues", "comments", comment.id.toString(), method = HttpMethod.Patch, body = buildJsonObject { put("body", body) }).toResult { }
    }

    /**
     * Its comment endpoint answers 204 to deleting a remark on a line and leaves it (Codeberg, 2026-10-25): only a plain
     * comment goes. Deleting the review it came in would take any other remark of it along, so it isn't offered.
     */
    override val deletesThreadComments: Boolean = false

    override val dismissesReviews: Boolean = true

    override suspend fun dismissReview(token: String, ref: IssueRef, reviewId: Long, message: String): ForgeResult<Unit> = forgeCall {
        repo(
            token, ref.repo, "pulls", ref.number.toString(), "reviews", reviewId.toString(), "dismissals", method = HttpMethod.Post,
            body = buildJsonObject { put("message", message) },
        ).toResult { }
    }

    override val appliesSuggestions: Boolean = true

    /** The file is read and written whole through the contents API, by its blob's sha, as on GitHub. */
    override suspend fun applySuggestion(token: String, ref: IssueRef, thread: ReviewThread, suggestion: List<String>, message: String): ForgeResult<Unit> = forgeCall {
        val pull = repo(token, ref.repo, "pulls", ref.number.toString())
        if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
        val head = required(pull.body<PullRequest>().head, "a pull request's head")
        val (owner, name) = head.repo?.fullName?.split('/')?.takeIf { it.size == 2 } ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
        val branch = head.ref ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
        val at = RepoId(owner, name, ref.repo.forge)
        val path = thread.path.split('/').toTypedArray()
        val read = repo(token, at, "contents", *path, query = mapOf("ref" to branch))
        if (read.status != HttpStatusCode.OK) return@forgeCall read.failure()
        val file = read.body<ContentsResponse>()
        val text = file.content?.takeIf { file.encoding == "base64" }?.let { Base64.getMimeDecoder().decode(it).decodeToString() }
            ?: return@forgeCall ForgeResult.Failure(ForgeError.Unsupported)
        val changed = when (val replaced = suggestionApplied(text, thread, suggestion)) {
            is ForgeResult.Failure -> return@forgeCall replaced
            is ForgeResult.Success -> replaced.value
        }
        val write = repo(
            token, at, "contents", *path, method = HttpMethod.Put,
            body = buildJsonObject {
                put("message", message)
                put("content", Base64.getEncoder().encodeToString(changed.encodeToByteArray()))
                put("sha", file.sha)
                put("branch", branch)
            },
        )
        if (write.status == HttpStatusCode.Conflict) ForgeResult.Failure(ForgeError.Conflict) else write.toResult { }
    }

    /** Forgejo merges the branch it goes into in, the way its own button does. */
    override suspend fun updateBranch(token: String, ref: IssueRef): ForgeResult<Unit> = forgeCall {
        repo(token, ref.repo, "pulls", ref.number.toString(), "update", method = HttpMethod.Post, query = mapOf("style" to "merge")).toResult { }
    }

    override suspend fun cancelMergeWhenChecksPass(token: String, ref: IssueRef): ForgeResult<Unit> = forgeCall {
        repo(token, ref.repo, "pulls", ref.number.toString(), "merge", method = HttpMethod.Delete).toResult { }
    }

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = forgeCall {
        repo(
            token, ref.repo, "pulls", ref.number.toString(), "reviews", method = HttpMethod.Post,
            body = buildJsonObject {
                put(
                    "event",
                    when (verdict) {
                        ReviewVerdict.APPROVE -> "APPROVED"
                        ReviewVerdict.REQUEST_CHANGES -> "REQUEST_CHANGES"
                        ReviewVerdict.COMMENT -> "COMMENT"
                    },
                )
                put("body", body)
                put(
                    "comments",
                    buildJsonArray {
                        comments.forEach { comment ->
                            add(
                                buildJsonObject {
                                    put("path", comment.path)
                                    put("body", comment.body)
                                    // The line in the file after the change, or before it; the other stays 0.
                                    put("new_position", comment.newLine ?: 0)
                                    put("old_position", if (comment.onOldSide) comment.oldLine ?: 0 else 0)
                                },
                            )
                        }
                    },
                )
            },
        ).toResult { }
    }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = forgeCall {
        repo(
            token, repo, "pulls", method = HttpMethod.Post,
            body = buildJsonObject {
                // Forgejo has no draft flag: a title that starts with "WIP:" is one.
                put("title", if (request.draft && !request.title.startsWith(DRAFT_PREFIX, ignoreCase = true)) "$DRAFT_PREFIX ${request.title}" else request.title)
                put("body", request.body)
                put("head", request.head)
                put("base", request.base)
            },
        ).toResult { IssueRef(repo, required(body<PullRequest>().number, "a pull request's number").toInt(), isPullRequest = true) }
    }

    /**
     * Forgejo's API has no word of pull request templates: it reads one file itself, the first of [PR_TEMPLATE_FOLDERS]
     * that holds one of [PR_TEMPLATE_NAMES] on the default [branch]. Looked for the same way, through git trees (1 to
     * 2 s each on Codeberg), only into the folders the repository has.
     */
    override suspend fun pullRequestTemplates(token: String, repo: RepoId, branch: String): ForgeResult<List<IssueTemplate>> = forgeCall {
        val root = when (val listed = httpClient.forgejoTree(forge, token, repo, branch, "")) {
            is ForgeResult.Failure -> return@forgeCall listed
            is ForgeResult.Success -> listed.value.orEmpty()
        }
        var path: String? = null
        for (folder in PR_TEMPLATE_FOLDERS) {
            val entries = when {
                folder.isEmpty() -> root
                root.none { it.path == folder && it.type == "tree" } -> continue
                else -> when (val listed = httpClient.forgejoTree(forge, token, repo, branch, folder)) {
                    is ForgeResult.Failure -> return@forgeCall listed
                    is ForgeResult.Success -> listed.value.orEmpty()
                }
            }
            val name = PR_TEMPLATE_NAMES.firstOrNull { name -> entries.any { it.path == name && it.type == "blob" } } ?: continue
            path = if (folder.isEmpty()) name else "$folder/$name"
            break
        }
        val found = path ?: return@forgeCall ForgeResult.Success(emptyList())
        repo(token, repo, "raw", *found.split('/').toTypedArray(), query = mapOf("ref" to branch)).toResult { listOf(markdownTemplate(found, bodyAsText())) }
    }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = forgeCall {
        val query = buildMap {
            put("limit", "$HISTORY_PER_PAGE")
            put("page", "$page")
            ref?.let { put("sha", it) }
            path?.let { put("path", it) }
            putAll(LIGHT)
        }
        val response = repo(token, repo, "commits", query = query)
        response.toResult {
            val commits = body<List<ApiCommit>>()
            // Older servers send no Link header: a full page may have another after it.
            CommitPage(commits.map { it.toModel() }, nextPage() ?: (page + 1).takeIf { commits.size == HISTORY_PER_PAGE && headers["Link"] == null })
        }
    }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = forgeCall {
        coroutineScope {
            val diff = async { repo(token, repo, "git", "commits", "$sha.diff", patienceMillis = DIFF_PATIENCE_MILLIS) }
            val commit = repo(token, repo, "git", "commits", sha, query = LIGHT)
            if (commit.status != HttpStatusCode.OK) return@coroutineScope commit.failure()
            val diffResponse = diff.await()
            if (diffResponse.status != HttpStatusCode.OK) return@coroutineScope diffResponse.failure()
            ForgeResult.Success(CommitDetails(commit.body<ApiCommit>().toModel(), parseUnifiedDiff(diffResponse.bodyAsText())))
        }
    }

    private suspend fun repo(
        token: String?, id: RepoId, vararg segments: String, method: HttpMethod = HttpMethod.Get, query: Map<String, String> = emptyMap(),
        body: JsonObject? = null, patienceMillis: Long? = null,
    ): HttpResponse = httpClient.forgejoApi(forge, token, "repos", id.owner, id.name, *segments, method = method, query = query, body = body, patienceMillis = patienceMillis)

    private companion object {
        const val HISTORY_PER_PAGE = 30
        const val MAX_COMMIT_PAGES = 5
        const val DRAFT_PREFIX = "WIP:"

        /** A large change's diff takes the server a while to write. */
        const val DIFF_PATIENCE_MILLIS = 30_000L

        /** Commits without what makes them slow to list: each one's stats, signature check and files. */
        val LIGHT = mapOf("stat" to "false", "verification" to "false", "files" to "false")
    }
}

private fun ApiCommit.toModel() = Commit(
    sha = required(sha, "a commit's sha"),
    message = commit?.message.orEmpty(),
    authorName = commit?.author?.name,
    author = author?.toModel(),
    date = instant(commit?.author?.date ?: commit?.committer?.date ?: created),
)

/**
 * The branch's name: once it is deleted, Forgejo names the pull request's own ref instead (`refs/pull/1/head`), and
 * only its label keeps the branch's (seen on Codeberg, 2026-10-29).
 */
private val PRBranchInfo.branch: String? get() = if (ref?.startsWith("refs/pull/") == true) label else ref

private fun CommitStatus.toModel(forge: ForgeInstance) = Check(
    name = context.orEmpty(),
    state = when (status) {
        "success" -> CheckState.SUCCESS
        "pending" -> CheckState.PENDING
        "skipped" -> CheckState.SKIPPED
        else -> CheckState.FAILURE
    },
    description = description?.ifBlank { null },
    // Forgejo's own Actions point to their page without saying the host.
    url = targetUrl?.ifBlank { null }?.let { if (it.startsWith("/")) forge.webUrl + it else it },
)

/**
 * Remarks on one line, oldest first: a remark's `position` is its line in the file after the change,
 * `original_position` before it, the other 0. Only a thread's first remark says who resolved it.
 */
private fun List<PullReviewComment>.toThread(): ReviewThread {
    val first = first()
    val position = first.position ?: 0
    return ReviewThread(
        id = required(first.id, "a remark's id").toString(),
        path = required(first.path, "a remark's file"),
        line = position.takeIf { it > 0 } ?: first.originalPosition?.takeIf { it > 0 },
        onOldSide = position == 0,
        hunk = first.diffHunk,
        comments = map { ThreadComment(required(it.id, "a remark's id"), it.user?.toModel(), it.body.orEmpty(), instant(it.createdAt)) },
        resolved = first.resolver != null,
        canResolve = false,
    )
}

/** Where Forgejo looks for a pull request template, in its order: the root first. */
private val PR_TEMPLATE_FOLDERS = listOf("", ".forgejo", ".gitea", ".github")

/** The file names Forgejo takes for one; its YAML forms are left out. */
private val PR_TEMPLATE_NAMES = listOf("PULL_REQUEST_TEMPLATE.md", "pull_request_template.md")

/**
 * A Markdown template at [path] whose [text] may open with YAML front matter (between `---` lines): its `name` and
 * `about` are read from it, and the description is what follows.
 */
internal fun markdownTemplate(path: String, text: String): IssueTemplate {
    val lines = text.lines()
    val end = if (lines.firstOrNull()?.trim() == "---") lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) else null
    fun field(key: String) = end?.let { lines.subList(1, it) }?.firstNotNullOfOrNull { line ->
        line.takeIf { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.trim('"', '\'')?.ifEmpty { null }
    }
    val body = if (end == null) text else lines.drop(end + 1).joinToString("\n").trimStart('\n')
    return IssueTemplate(path, field("name") ?: path.substringAfterLast('/'), about = field("about"), body = body)
}
