package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeBlocker
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import fr.arthurbrugiere.forgeline.core.model.overall
import fr.arthurbrugiere.forgeline.core.model.parseUnifiedDiff
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.BaseCommit
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedChangeset
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedCommitstatuses
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedDiffstats
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedPullrequestComments
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Pullrequest
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PullrequestComment
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PullrequestMergeabilityChecks
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What stands behind a Bitbucket pull request: its files and commits, its builds (commit statuses, Pipelines' among
 * them), its review (approving, asking for changes, remarks on lines and their threads) and merging it; and a
 * repository's history.
 */
class BitbucketPullRequestApi(
    private val http: HttpClient,
    private val people: BitbucketPeople = BitbucketPeople(),
) : PullRequestApi {
    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = forgeCall {
        // The whole change comes on the first page: its diffstat says each file, its diff their changes.
        if (page > 1) return@forgeCall ForgeResult.Success(ChangedFiles(emptyList(), null))
        changes(token, ref.pull("diffstat"), ref.pull("diff")).map { ChangedFiles(it, null) }
    }

    /** The files a change touches ([stat], Bitbucket's diffstat) with their patches, read from its whole diff ([diff]). */
    private suspend fun changes(token: String?, stat: Array<String>, diff: Array<String>): ForgeResult<List<ChangedFile>> = coroutineScope {
        val text = async { http.bitbucket(token, *diff, accept = "text/plain").let { if (it.status.isSuccess()) it.bodyAsText() else null } }
        val answer = http.bitbucket(token, *stat, query = listOf("pagelen" to "500"))
        if (!answer.status.isSuccess()) return@coroutineScope answer.failure()
        val patches = text.await()?.let(::parseUnifiedDiff).orEmpty().associateBy { it.path }
        ForgeResult.Success(answer.body<PaginatedDiffstats>().propertyValues.orEmpty().mapNotNull { it.toChangedFile(patches) })
    }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = forgeCall {
        // Newest first, as Bitbucket lists them.
        http.bitbucket(token, *ref.pull("commits"), query = listOf("pagelen" to "100"))
            .toResult { body<PaginatedChangeset>().propertyValues.orEmpty().map { it.toCommit(people) }.asReversed() }
    }

    /** What reported on the pull request's latest commit: Pipelines and any other service's build statuses. */
    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = forgeCall {
        coroutineScope {
            val head = async { http.bitbucket(token, *ref.pull(), query = listOf("fields" to "source.commit.hash")).let { if (it.status.isSuccess()) it.body<Pullrequest>().source?.commit?.hash else null } }
            when (val statuses = statuses(token, ref)) {
                is ForgeResult.Failure -> statuses
                is ForgeResult.Success -> {
                    val latest = head.await()
                    val onLatest = statuses.value.filter { (sha, _) -> latest == null || sha.startsWith(latest) || latest.startsWith(sha) }
                    ForgeResult.Success(onLatest.map { it.second })
                }
            }
        }
    }

    /** Every status reported on the pull request's commits, each with its commit's hash, the latest of each name. */
    private suspend fun statuses(token: String?, ref: IssueRef): ForgeResult<List<Pair<String, Check>>> {
        val answer = http.bitbucket(token, *ref.pull("statuses"), query = listOf("pagelen" to "100", "sort" to "-updated_on"))
        if (!answer.status.isSuccess()) return answer.failure()
        val statuses = answer.body<PaginatedCommitstatuses>().propertyValues.orEmpty()
        return ForgeResult.Success(
            statuses.mapNotNull { status -> status.commit?.hash?.let { it to status } }
                .distinctBy { (sha, status) -> sha to status.key }
                .map { (sha, status) -> sha to status.toCheck(ref.repo) },
        )
    }

    override suspend fun commitChecks(token: String?, ref: IssueRef): ForgeResult<Map<String, CheckState>> = forgeCall {
        statuses(token, ref).map { statuses -> statuses.groupBy({ it.first }, { it.second.state }).mapNotNull { (sha, states) -> states.overall()?.let { sha to it } }.toMap() }
    }

    /**
     * Whether [ref] can be merged, from the checks Bitbucket runs before a merge: the reader's permission, conflicts, and
     * each rule its branch has (approvals, passing builds, resolved tasks...); and how, from the strategies its branch allows.
     */
    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = forgeCall {
        coroutineScope {
            val checks = async { http.bitbucket(token, *ref.pull("mergeability", "checks")).let { if (it.status.isSuccess()) it.body<PullrequestMergeabilityChecks>().propertyValues.orEmpty() else null } }
            val answer = http.bitbucket(token, *ref.pull())
            if (!answer.status.isSuccess()) return@coroutineScope answer.failure()
            val pull = answer.body<Pullrequest>()
            val branch = pull.destination?.branch
            val strategies = branch?.mergeStrategies.orEmpty().ifEmpty { listOf("merge_commit", "squash", "fast_forward") }
            val default = branch?.defaultMergeStrategy?.let(::mergeMethodOf)
            val methods = strategies.mapNotNull(::mergeMethodOf).distinct().sortedBy { if (it == default) 0 else 1 }
            val found = checks.await()
            val blocking = found.orEmpty().filter { it.blocking == true }
            val sameRepo = pull.source?.repository?.fullName.equals(pull.destination?.repository?.fullName, ignoreCase = true)
            ForgeResult.Success(
                MergeInfo(
                    mergeable = when {
                        pull.state != "OPEN" -> false
                        found == null -> null
                        else -> found.none { it.type == "git_mergeability_check" && it.blocking == true }
                    },
                    canMerge = pull.state == "OPEN" && found?.none { it.type == "current_user_permission_check" && it.blocking == true } != false,
                    methods = methods,
                    // A fork's branch isn't this repository's to delete.
                    deleteBranch = if (sameRepo) pull.closeSourceBranch == true else null,
                    blockers = blocking.mapNotNull { it.blocker() }.distinct(),
                ),
            )
        }
    }

    private fun fr.arthurbrugiere.forgeline.forge.bitbucket.api.PullrequestMergeabilityCheck.blocker(): MergeBlocker? = when (type) {
        "git_mergeability_check" -> MergeBlocker.CONFLICTS
        "current_user_permission_check", "pullrequest_state_check" -> null
        "standard_merge_check" -> when ((check as? JsonObject)?.get("kind")?.let { (it as? JsonPrimitive)?.contentOrNull }) {
            "minimum_approvals", "default_reviewers_approval", "minimum_approvals_from_default_reviewers" -> MergeBlocker.REVIEWS
            "minimum_successful_builds", "no_failed_builds", "all_builds_successful" -> MergeBlocker.CHECKS
            "no_changes_requested", "reset_pullrequest_changes_requested_on_change" -> MergeBlocker.CHANGES_REQUESTED
            "resolved_tasks", "all_comments_resolved" -> MergeBlocker.THREADS
            else -> MergeBlocker.OTHER
        }
        else -> MergeBlocker.OTHER
    }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean): ForgeResult<Unit> = forgeCall {
        // Merging once the checks pass isn't something Bitbucket's API offers.
        if (whenChecksPass) return@forgeCall ForgeResult.Failure(ForgeError.Unsupported)
        val pull = http.bitbucket(token, *ref.pull(), query = listOf("fields" to "destination.branch.merge_strategies,destination.branch.default_merge_strategy"))
        val branch = if (pull.status.isSuccess()) pull.body<Pullrequest>().destination?.branch else null
        val strategy = strategyFor(method, branch?.mergeStrategies.orEmpty(), branch?.defaultMergeStrategy) ?: when (method) {
            MergeMethod.MERGE -> "merge_commit"
            MergeMethod.SQUASH -> "squash"
            MergeMethod.REBASE -> "fast_forward"
        }
        val body = buildJsonObject {
            put("type", "pullrequest")
            put("merge_strategy", strategy)
            put("close_source_branch", deleteBranch)
        }
        // A long merge answers 202, and goes on without the app.
        http.bitbucket(token, *ref.pull("merge"), method = HttpMethod.Post, body = body).toResult { }
    }

    /** Asking for changes is a verdict of its own on Bitbucket, as approving is; remarks on lines are posted one by one first. */
    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = forgeCall {
        comments.forEach { remark ->
            val posted = http.bitbucket(token, *ref.pull("comments"), method = HttpMethod.Post, body = lineComment(remark))
            if (!posted.status.isSuccess()) return@forgeCall posted.failure()
        }
        if (body.isNotBlank()) {
            val said = http.bitbucket(token, *ref.pull("comments"), method = HttpMethod.Post, body = content(body))
            if (!said.status.isSuccess()) return@forgeCall said.failure()
        }
        when (verdict) {
            ReviewVerdict.COMMENT -> ForgeResult.Success(Unit)
            ReviewVerdict.APPROVE -> http.bitbucket(token, *ref.pull("approve"), method = HttpMethod.Post).toResult { }
            ReviewVerdict.REQUEST_CHANGES -> http.bitbucket(token, *ref.pull("request-changes"), method = HttpMethod.Post).toResult { }
        }
    }

    /**
     * A remark on a line, as Bitbucket places it: `to` is a line of the file after the change, `from` before it; a range
     * starts at `start_to` or `start_from`. A remark on the whole file has a path alone.
     */
    private fun lineComment(remark: LineComment): JsonObject = buildJsonObject {
        putJsonObject("content") { put("raw", remark.body) }
        putJsonObject("inline") {
            put("path", remark.path)
            if (!remark.onFile) {
                if (remark.newLine != null) put("to", remark.newLine) else remark.oldLine?.let { put("from", it) }
                if (remark.isRange) {
                    if (remark.startNewLine != null) put("start_to", remark.startNewLine) else remark.startOldLine?.let { put("start_from", it) }
                }
            }
        }
    }

    override val commentsOnRanges: Boolean = true

    override val commentsOnFiles: Boolean = true

    override val editsThreadComments: Boolean = true

    override val resolvesThreads: Boolean = true

    /** The remarks on lines, each with the replies under it (Bitbucket's replies name their parent), oldest first. */
    override suspend fun threads(token: String?, ref: IssueRef): ForgeResult<List<ReviewThread>> = forgeCall {
        comments(token, ref).map { comments ->
            val byId = comments.associateBy { it.id }
            fun rootOf(comment: PullrequestComment): PullrequestComment {
                var at = comment
                repeat(MAX_DEPTH) { at = at.parent?.id?.let(byId::get) ?: return at }
                return at
            }
            comments.filter { it.inline != null }
                .groupBy { rootOf(it).id }
                .mapNotNull { (rootId, thread) ->
                    val root = byId[rootId] ?: return@mapNotNull null
                    val inline = root.inline ?: return@mapNotNull null
                    val kept = thread.filter { it.deleted != true }.sortedBy { bitbucketInstant(it.createdOn) }
                    if (kept.isEmpty()) return@mapNotNull null
                    ReviewThread(
                        id = "$rootId",
                        path = inline.path.orEmpty(),
                        line = inline.to ?: inline.from,
                        onOldSide = inline.to == null && inline.from != null,
                        hunk = null,
                        comments = kept.map { ThreadComment(it.id ?: 0, people.user(it.user), it.content?.raw.orEmpty(), bitbucketInstant(it.createdOn)) },
                        resolved = root.resolution != null,
                        canResolve = token != null,
                        startLine = inline.startTo ?: inline.startFrom,
                    )
                }
                .sortedBy { thread -> thread.comments.first().createdAt }
        }
    }

    /** Every comment of [ref], lines' and the conversation's, oldest first. */
    private suspend fun comments(token: String?, ref: IssueRef): ForgeResult<List<PullrequestComment>> {
        val all = mutableListOf<PullrequestComment>()
        var page = 1
        while (page <= MAX_PAGES) {
            val answer = http.bitbucket(token, *ref.pull("comments"), query = listOf("pagelen" to "100", "page" to "$page"))
            if (!answer.status.isSuccess()) return answer.failure()
            val read = answer.body<PaginatedPullrequestComments>()
            all += read.propertyValues.orEmpty()
            page = nextPageOf(read.next) ?: break
        }
        return ForgeResult.Success(all)
    }

    override suspend fun reply(token: String, ref: IssueRef, thread: ReviewThread, body: String): ForgeResult<Unit> = forgeCall {
        val reply = buildJsonObject {
            putJsonObject("content") { put("raw", body) }
            putJsonObject("parent") { put("id", thread.id.toLong()) }
        }
        http.bitbucket(token, *ref.pull("comments"), method = HttpMethod.Post, body = reply).toResult { }
    }

    override suspend fun editThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, body: String): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *ref.pull("comments", "${comment.id}"), method = HttpMethod.Put, body = content(body)).toResult { }
    }

    override suspend fun deleteThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *ref.pull("comments", "${comment.id}"), method = HttpMethod.Delete).toResult { }
    }

    override suspend fun setResolved(token: String, ref: IssueRef, thread: ReviewThread, resolved: Boolean): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *ref.pull("comments", thread.id, "resolve"), method = if (resolved) HttpMethod.Post else HttpMethod.Delete).toResult { }
    }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = forgeCall {
        val body = buildJsonObject {
            put("title", request.title)
            put("description", request.body)
            putJsonObject("source") { putJsonObject("branch") { put("name", request.head) } }
            putJsonObject("destination") { putJsonObject("branch") { put("name", request.base) } }
            put("draft", request.draft)
        }
        val answer = http.bitbucket(token, *repo.api("pullrequests"), method = HttpMethod.Post, body = body)
        if (!answer.status.isSuccess()) return@forgeCall answer.failure()
        answer.body<Pullrequest>().id?.let { ForgeResult.Success(IssueRef(repo, it, isPullRequest = true)) } ?: ForgeResult.Failure(ForgeError.Unreadable)
    }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = forgeCall {
        // Without a revision Bitbucket lists every branch's commits: HEAD is the main branch's.
        val query = listOfNotNull("pagelen" to "30", "page" to "$page", path?.takeIf { it.isNotBlank() }?.let { "path" to it })
        val answer = http.bitbucket(token, *repo.api("commits", ref ?: "HEAD"), query = query)
        if (answer.status == HttpStatusCode.NotFound && page > 1) return@forgeCall ForgeResult.Success(CommitPage(emptyList(), null))
        answer.toResult { body<PaginatedChangeset>().let { read -> CommitPage(read.propertyValues.orEmpty().map { it.toCommit(people) }, nextPageOf(read.next)) } }
    }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = forgeCall {
        coroutineScope {
            val files = async { changes(token, repo.api("diffstat", sha), repo.api("diff", sha)) }
            when (val commit = http.bitbucket(token, *repo.api("commit", sha)).toResult { body<BaseCommit>().toCommit(people) }) {
                is ForgeResult.Failure -> commit
                is ForgeResult.Success -> files.await().map { CommitDetails(commit.value, it) }
            }
        }
    }

    private companion object {
        const val MAX_PAGES = 10

        /** How deep replies are followed up to their thread's first remark. */
        const val MAX_DEPTH = 50
    }
}
