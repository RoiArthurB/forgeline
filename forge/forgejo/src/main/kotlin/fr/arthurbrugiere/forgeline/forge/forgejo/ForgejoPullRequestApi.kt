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
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

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
        val commits = mutableListOf<PrCommitJson>()
        var page: Int? = 1
        while (page != null && page <= MAX_COMMIT_PAGES) {
            val response = repo(token, ref.repo, "pulls", ref.number.toString(), "commits", query = mapOf("limit" to "50", "page" to "$page") + LIGHT)
            if (response.status != HttpStatusCode.OK) return@forgeCall response.failure()
            commits += response.body<List<PrCommitJson>>()
            page = response.nextPage()
        }
        ForgeResult.Success(commits.map { it.toModel() })
    }

    /** Forgejo keeps one kind: the statuses reported on a commit, its own Actions' among them. */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = forgeCall {
        val pull = repo(token, ref.repo, "pulls", ref.number.toString())
        if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
        val sha = pull.body<PrPullJson>().head.sha
        repo(token, ref.repo, "commits", sha, "status").toResult { body<PrCombinedStatusJson>().statuses.orEmpty().map { it.toModel(forge) } }
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = forgeCall {
        coroutineScope {
            val pull = async { repo(token, ref.repo, "pulls", ref.number.toString()) }
            val settings = async { repo(token, ref.repo) }
            val pullResponse = pull.await()
            if (pullResponse.status != HttpStatusCode.OK) return@coroutineScope pullResponse.failure()
            val repoResponse = settings.await()
            if (repoResponse.status != HttpStatusCode.OK) return@coroutineScope repoResponse.failure()
            val repo = repoResponse.body<PrRepoJson>()
            val methods = listOfNotNull(
                MergeMethod.MERGE.takeIf { repo.allowMergeCommits },
                MergeMethod.SQUASH.takeIf { repo.allowSquashMerge },
                MergeMethod.REBASE.takeIf { repo.allowRebase },
            )
            // The repository's own choice first, when it names one of these.
            val preferred = MergeMethod.entries.firstOrNull { it.name.lowercase() == repo.defaultMergeStyle }
            val details = pullResponse.body<PrPullJson>()
            val canMerge = repo.permissions?.push == true
            // A branch of the repository itself can go with the merge; a fork's isn't the repository's to delete.
            val ownBranch = details.head.repoId != null && details.head.repoId == details.base?.repoId
            // Forgejo doesn't say whether the target moved on: what it has that the change's branch lacks is compared,
            // for a branch of the repository itself. Unknown counts as up to date.
            val behind = if (ownBranch && canMerge && details.head.ref != null && details.base?.ref != null) {
                // The comparison is the rest of the path, slashes in branch names and all.
                val compared = repo(token, ref.repo, "compare", *"${details.head.ref}...${details.base.ref}".split('/').toTypedArray())
                compared.status == HttpStatusCode.OK && compared.body<PrCompareJson>().totalCommits > 0
            } else {
                false
            }
            ForgeResult.Success(
                MergeInfo(
                    details.mergeable, canMerge, methods.sortedByDescending { it == preferred },
                    deleteBranch = repo.defaultDeleteBranchAfterMerge.takeIf { ownBranch },
                    // Forgejo schedules a merge for when the checks succeed, and doesn't say afterwards that it did.
                    canMergeWhenChecksPass = canMerge,
                    behind = behind,
                    deletedBranch = if (ownBranch && canMerge && details.state == "closed") deletedBranch(token, ref.repo, details.head) else null,
                ),
            )
        }
    }

    /** [head], when its branch is no longer there: asked for by its name, it is not found. */
    private suspend fun deletedBranch(token: String, repo: RepoId, head: PrHeadJson): DeletedBranch? {
        val name = head.branch ?: return null
        if (head.sha.isEmpty()) return null
        val branch = repo(token, repo, "branches", *name.split('/').toTypedArray())
        return DeletedBranch(name, head.sha).takeIf { branch.status == HttpStatusCode.NotFound }
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
            val answers = reviews.body<List<PrReviewJson>>().filter { it.commentsCount > 0 }.map { review ->
                async { repo(token, ref.repo, "pulls", ref.number.toString(), "reviews", review.id.toString(), "comments") }
            }.map { it.await() }
            answers.firstOrNull { it.status != HttpStatusCode.OK }?.let { return@coroutineScope it.failure() }
            val remarks = answers.flatMap { it.body<List<PrRemarkJson>>() }.sortedBy { instant(it.createdAt) }
            val threads = remarks.groupBy { Triple(it.path, it.position, it.originalPosition) }.values.map { it.toThread() }
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
        val head = pull.body<PrPullJson>().head
        val (owner, name) = head.repo?.fullName?.split('/')?.takeIf { it.size == 2 } ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
        val branch = head.ref ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
        val at = RepoId(owner, name, ref.repo.forge)
        val path = thread.path.split('/').toTypedArray()
        val read = repo(token, at, "contents", *path, query = mapOf("ref" to branch))
        if (read.status != HttpStatusCode.OK) return@forgeCall read.failure()
        val file = read.body<PrContentJson>()
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
        ).toResult { IssueRef(repo, body<PrCreatedJson>().number, isPullRequest = true) }
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
            val commits = body<List<PrCommitJson>>()
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
            ForgeResult.Success(CommitDetails(commit.body<PrCommitJson>().toModel(), parseUnifiedDiff(diffResponse.bodyAsText())))
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

@Serializable
private data class PrGitPersonJson(val name: String? = null, val date: String? = null)

@Serializable
private data class PrGitCommitJson(val message: String = "", val author: PrGitPersonJson? = null, val committer: PrGitPersonJson? = null)

@Serializable
private data class PrCommitJson(val sha: String, val commit: PrGitCommitJson? = null, val author: UserJson? = null, val created: String? = null) {
    fun toModel() = Commit(
        sha = sha,
        message = commit?.message.orEmpty(),
        authorName = commit?.author?.name,
        author = author?.toModel(),
        date = instant(commit?.author?.date ?: commit?.committer?.date ?: created),
    )
}

@Serializable
private data class PrHeadJson(
    val sha: String = "",
    val ref: String? = null,
    @SerialName("repo_id") val repoId: Long? = null,
    val repo: PrBranchRepoJson? = null,
    val label: String? = null,
) {
    /**
     * The branch's name: once it is deleted, Forgejo names the pull request's own ref instead (`refs/pull/1/head`),
     * and only its label keeps the branch's (seen on Codeberg, 2026-10-29).
     */
    val branch: String? get() = if (ref?.startsWith("refs/pull/") == true) label else ref
}

/** Where a pull request's branch lives; a deleted fork has none. */
@Serializable
private data class PrBranchRepoJson(@SerialName("full_name") val fullName: String? = null)

/** A file as the contents API sends it: [content] in base64. */
@Serializable
private data class PrContentJson(val sha: String, val content: String? = null, val encoding: String? = null)

@Serializable
private data class PrCompareJson(@SerialName("total_commits") val totalCommits: Int = 0)

@Serializable
private data class PrPullJson(val head: PrHeadJson, val base: PrHeadJson? = null, val mergeable: Boolean? = null, val state: String? = null)

@Serializable
private data class PrPermissionsJson(val push: Boolean = false)

@Serializable
private data class PrRepoJson(
    @SerialName("allow_merge_commits") val allowMergeCommits: Boolean = true,
    @SerialName("allow_squash_merge") val allowSquashMerge: Boolean = false,
    @SerialName("allow_rebase") val allowRebase: Boolean = false,
    @SerialName("default_merge_style") val defaultMergeStyle: String? = null,
    @SerialName("default_delete_branch_after_merge") val defaultDeleteBranchAfterMerge: Boolean = false,
    val permissions: PrPermissionsJson? = null,
)

@Serializable
private data class PrCombinedStatusJson(val statuses: List<PrStatusJson>? = null)

@Serializable
private data class PrStatusJson(val context: String = "", val status: String = "", val description: String? = null, @SerialName("target_url") val targetUrl: String? = null) {
    fun toModel(forge: ForgeInstance) = Check(
        name = context,
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
}

@Serializable
private data class PrCreatedJson(val number: Int)

@Serializable
private data class PrReviewJson(val id: Long, @SerialName("comments_count") val commentsCount: Int = 0)

/**
 * A remark on a line: [position] is its line in the file after the change, [originalPosition] before it, the other 0.
 * Only a thread's first remark says who resolved it.
 */
@Serializable
private data class PrRemarkJson(
    val id: Long,
    val body: String = "",
    val user: UserJson? = null,
    val path: String,
    val position: Int = 0,
    @SerialName("original_position") val originalPosition: Int = 0,
    @SerialName("diff_hunk") val diffHunk: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val resolver: UserJson? = null,
)

private fun List<PrRemarkJson>.toThread(): ReviewThread {
    val first = first()
    return ReviewThread(
        id = first.id.toString(),
        path = first.path,
        line = first.position.takeIf { it > 0 } ?: first.originalPosition.takeIf { it > 0 },
        onOldSide = first.position == 0,
        hunk = first.diffHunk,
        comments = map { ThreadComment(it.id, it.user?.toModel(), it.body, instant(it.createdAt)) },
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
