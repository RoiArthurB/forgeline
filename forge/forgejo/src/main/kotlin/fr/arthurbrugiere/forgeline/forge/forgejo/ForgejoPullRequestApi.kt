package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.parseUnifiedDiff
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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

/**
 * A Forgejo instance's pull requests and history. Its API lists the files of a change without their changes, so those
 * are read from the whole diff it serves beside (`pulls/12.diff`, `git/commits/<sha>.diff`), in one request.
 */
class ForgejoPullRequestApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : PullRequestApi {

    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = forgejoCall {
        // The diff holds every file at once: there is no second page.
        if (page > 1) return@forgejoCall ForgeResult.Success(ChangedFiles(emptyList(), null))
        repo(token, ref.repo, "pulls", "${ref.number}.diff", patienceMillis = DIFF_PATIENCE_MILLIS)
            .toResult { ChangedFiles(parseUnifiedDiff(bodyAsText()), null) }
    }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = forgejoCall {
        val commits = mutableListOf<PrCommitJson>()
        var page: Int? = 1
        while (page != null && page <= MAX_COMMIT_PAGES) {
            val response = repo(token, ref.repo, "pulls", ref.number.toString(), "commits", query = mapOf("limit" to "50", "page" to "$page") + LIGHT)
            if (response.status != HttpStatusCode.OK) return@forgejoCall response.failure()
            commits += response.body<List<PrCommitJson>>()
            page = response.nextPage()
        }
        ForgeResult.Success(commits.map { it.toModel() })
    }

    /** Forgejo keeps one kind: the statuses reported on a commit, its own Actions' among them. */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = forgejoCall {
        val pull = repo(token, ref.repo, "pulls", ref.number.toString())
        if (pull.status != HttpStatusCode.OK) return@forgejoCall pull.failure()
        val sha = pull.body<PrPullJson>().head.sha
        repo(token, ref.repo, "commits", sha, "status").toResult { body<PrCombinedStatusJson>().statuses.orEmpty().map { it.toModel(forge) } }
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = forgejoCall {
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
            ForgeResult.Success(
                MergeInfo(pullResponse.body<PrPullJson>().mergeable, repo.permissions?.push == true, methods.sortedByDescending { it == preferred }),
            )
        }
    }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod): ForgeResult<Unit> = forgejoCall {
        repo(
            token, ref.repo, "pulls", ref.number.toString(), "merge", method = HttpMethod.Post,
            body = buildJsonObject { put("Do", method.name.lowercase()) },
        ).toResult { }
    }

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = forgejoCall {
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

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = forgejoCall {
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

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = forgejoCall {
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

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = forgejoCall {
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
private data class PrHeadJson(val sha: String)

@Serializable
private data class PrPullJson(val head: PrHeadJson, val mergeable: Boolean? = null)

@Serializable
private data class PrPermissionsJson(val push: Boolean = false)

@Serializable
private data class PrRepoJson(
    @SerialName("allow_merge_commits") val allowMergeCommits: Boolean = true,
    @SerialName("allow_squash_merge") val allowSquashMerge: Boolean = false,
    @SerialName("allow_rebase") val allowRebase: Boolean = false,
    @SerialName("default_merge_style") val defaultMergeStyle: String? = null,
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
