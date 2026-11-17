package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedPullRequestApi
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import org.tangled.ci.QueryPipelinesQueryParams
import org.tangled.git.GetBlameQueryParams
import org.tangled.git.Oid
import org.tangled.review.SubmitPatchPatch
import org.tangled.review.SubmitPatchRequest
import org.tangled.temp.git.GetBranchQueryParams
import sh.christian.ozone.api.RKey
import java.util.concurrent.ConcurrentHashMap
import org.tangled.git.ListCommitsQueryParams as KnotListCommitsQueryParams

/**
 * A Tangled pull request's change and a repository's history. A pull request is a ticket with a target branch, its
 * change the patches (`org.tangled.review.patch`) its author submitted, the last of which is what it would merge: its
 * files are diffed by the knot ([TangledChanges]), its commits listed by the index, its checks asked of the
 * repository's spindle, which runs its CI. Opening one is as Tangled's web app does it (`web/src/lib/api/pull.ts`,
 * read 2026-11-17): a ticket into the base branch, then a patch from the merge base to the head branch's tip, which the
 * knot fetches itself (`org.tangled.review.submitPatch`).
 *
 * Tangled merges through a service of its own whose call no lexicon describes, and has no review verdicts or remarks
 * on lines yet: a review here is a comment, and merging stays in the browser.
 */
class TangledPullRequestApi internal constructor(
    private val xrpc: TangledXrpc,
    private val knot: TangledKnot,
    private val conversations: TangledConversations,
    private val issues: TangledIssueApi,
) : UnsupportedPullRequestApi() {

    override val supportsHistory: Boolean get() = true

    override val supportsBlame: Boolean get() = true

    override val verdicts: Set<ReviewVerdict> get() = setOf(ReviewVerdict.COMMENT)

    /** Where each page of a history starts, as the knot pages it: after the last commit of the page before. */
    private val historyCursors = ConcurrentHashMap<String, String>()

    /** [ref]'s repository and its latest patch. */
    private suspend fun latest(ref: IssueRef): ForgeResult<Pair<TicketRepo, PatchRecordJson>> {
        val repo = when (val found = conversations.repo(ref.repo)) {
            is ForgeResult.Failure -> return found
            is ForgeResult.Success -> found.value
        }
        return when (val patches = conversations.patches(ticketUri(repo.did, ref.number))) {
            is ForgeResult.Failure -> patches
            is ForgeResult.Success -> patches.value.lastOrNull()?.value?.let { ForgeResult.Success(repo to it) } ?: notFound("No patch for #${ref.number}")
        }
    }

    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = forgeCall {
        val (repo, patch) = when (val found = latest(ref)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val base = patch.base?.oid ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        val head = patch.head?.oid ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        changedFiles(repo, base, repo.did, head, patch.source?.repo ?: repo.did, page)
    }

    private suspend fun changedFiles(repo: TicketRepo, base: String, baseRepo: String, head: String, headRepo: String, page: Int): ForgeResult<ChangedFiles> =
        when (val diff = TangledChanges.diff(knot, repo, base, head)) {
            is ForgeResult.Failure -> diff
            is ForgeResult.Success -> {
                val shown = diff.value.diffs.drop((page - 1) * FILES_PAGE).take(FILES_PAGE)
                val more = diff.value.diffs.size > page * FILES_PAGE
                ForgeResult.Success(ChangedFiles(TangledChanges.files(xrpc, shown, baseRepo, base, headRepo, head), if (more) page + 1 else null))
            }
        }

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = forgeCall {
        val (repo, patch) = when (val found = latest(ref)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val head = patch.head?.oid ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        TangledChanges.commits(xrpc, listOfNotNull(repo.did, patch.source?.repo), patch.base?.oid, head)
            .map { commits -> commits.map(TangledChanges::commit).reversed() }
    }

    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = forgeCall {
        val (repo, patch) = when (val found = latest(ref)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val head = patch.head?.oid ?: return@forgeCall ForgeResult.Success(emptyList())
        checksOf(repo, head)
    }

    /** What the repository's spindle ran on [commit], its latest pipeline's workflows; none without a spindle. */
    private suspend fun checksOf(repo: TicketRepo, commit: String): ForgeResult<List<Check>> {
        val spindle = repo.spindle ?: return ForgeResult.Success(emptyList())
        val did = when (val valid = asDid(repo.did)) {
            is ForgeResult.Failure -> return valid
            is ForgeResult.Success -> valid.value
        }
        return xrpc.service(knot.base(spindle)).queryPipelines(QueryPipelinesQueryParams(did, commits = listOf(commit), limit = 1)).toResult { answer ->
            val pipeline = answer.pipelines.firstOrNull() ?: return@toResult emptyList()
            pipeline.workflows.map { run ->
                Check(
                    name = run.name ?: run.id,
                    state = when (run.status) {
                        "success" -> CheckState.SUCCESS
                        "failed", "timeout" -> CheckState.FAILURE
                        "cancelled" -> CheckState.SKIPPED
                        else -> CheckState.PENDING
                    },
                    description = run.error,
                    url = "${repo.id.webUrl}/pipelines/${pipeline.id.rkey}/workflow/${run.id}",
                )
            }
        }
    }

    /** Tangled merges through a call of its own no lexicon describes yet: the browser does it. */
    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = ForgeResult.Success(MergeInfo(mergeable = null, canMerge = false, methods = emptyList()))

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean): ForgeResult<Unit> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** A comment on the pull request: Tangled has no verdicts, nor remarks on lines, yet. */
    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = when {
        verdict != ReviewVerdict.COMMENT || comments.isNotEmpty() -> ForgeResult.Failure(ForgeError.Unsupported)
        body.isBlank() -> ForgeResult.Success(Unit)
        else -> issues.comment(token, ref, body).map { }
    }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = forgeCall {
        val target = when (val found = conversations.repo(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val host = when (val writable = target.writableKnot) {
            is ForgeResult.Failure -> return@forgeCall writable
            is ForgeResult.Success -> writable.value
        }
        val did = when (val valid = asDid(target.did)) {
            is ForgeResult.Failure -> return@forgeCall valid
            is ForgeResult.Success -> valid.value
        }
        val baseTip = when (val branch = xrpc.index.getBranch(GetBranchQueryParams(did, request.base)).toResult { it.hash }) {
            is ForgeResult.Failure -> return@forgeCall branch
            is ForgeResult.Success -> branch.value
        }
        val headTip = when (val branch = xrpc.index.getBranch(GetBranchQueryParams(did, request.head)).toResult { it.hash }) {
            is ForgeResult.Failure -> return@forgeCall branch
            is ForgeResult.Success -> branch.value
        }
        // The patch starts where the head branch left the base one, as Tangled's own pull requests do.
        val base = (TangledChanges.commits(xrpc, listOf(target.did), baseTip, headTip) as? ForgeResult.Success)?.value
            ?.lastOrNull()?.parents?.firstOrNull() ?: baseTip
        val ticket = when (val written = issues.createTicket(token, host, target.did, request.title, request.body, request.base)) {
            is ForgeResult.Failure -> return@forgeCall written
            is ForgeResult.Success -> written.value
        }
        val number = ticket.uri.recordKey.toIntOrNull() ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        val patch = when (val service = knot.signedService(token, host, target.did, SUBMIT_PATCH)) {
            is ForgeResult.Failure -> service
            is ForgeResult.Success -> service.value.submitPatch(
                SubmitPatchRequest(did, SubmitPatchPatch(RKey(number.toString()), Oid(base), Oid(headTip), did, request.head)),
            ).toResult { }
        }
        when (patch) {
            // A pull request without a change can't be reviewed: the ticket goes again, as Tangled's web app does.
            is ForgeResult.Failure -> patch.also { knot.delete(token, host, ticket.uri) }
            is ForgeResult.Success -> ForgeResult.Success(IssueRef(repo, number, isPullRequest = true))
        }
    }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = forgeCall {
        val target = when (val found = conversations.repo(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val host = target.knot ?: return@forgeCall ForgeResult.Failure(ForgeError.Unsupported)
        val did = when (val valid = asDid(target.did)) {
            is ForgeResult.Failure -> return@forgeCall valid
            is ForgeResult.Success -> valid.value
        }
        val from = ref ?: target.defaultBranch
        val key = "${target.did} $from ${path.orEmpty()}"
        // The knot pages by the last commit given: a page is found from the one before it.
        val cursor = if (page > 1) historyCursors["$key ${page - 1}"] ?: return@forgeCall ForgeResult.Success(CommitPage(emptyList(), null)) else null
        knot.git(host).listCommits(KnotListCommitsQueryParams(did, from, path?.trim('/').orEmpty(), HISTORY_PAGE.toLong(), cursor)).toResult { answer ->
            answer.cursor?.takeIf { it.isNotBlank() }?.let { historyCursors["$key $page"] = it }
            CommitPage(
                answer.commits.map { commit ->
                    Commit(commit.hash, commit.message.trimEnd(), commit.author.name, null, commit.author.`when`.toJava())
                },
                if (answer.cursor.isNullOrBlank() || answer.commits.isEmpty()) null else page + 1,
            )
        }
    }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = forgeCall {
        val target = when (val found = conversations.repo(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val commit = when (val found = TangledChanges.commits(xrpc, listOf(target.did), null, sha, limit = 1)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value.firstOrNull() ?: return@forgeCall notFound("No commit $sha")
        }
        // A first commit is diffed against git's empty tree.
        val parent = commit.parents.firstOrNull() ?: EMPTY_TREE
        val files = when (val diff = TangledChanges.diff(knot, target, parent, commit.oid)) {
            is ForgeResult.Failure -> return@forgeCall diff
            is ForgeResult.Success -> TangledChanges.files(xrpc, diff.value.diffs, target.did, parent, target.did, commit.oid)
        }
        ForgeResult.Success(CommitDetails(TangledChanges.commit(commit), files))
    }

    override suspend fun blame(token: String?, repo: RepoId, ref: String, path: String): ForgeResult<List<BlameRange>> = forgeCall {
        val target = when (val found = conversations.repo(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val host = target.knot ?: return@forgeCall ForgeResult.Failure(ForgeError.Unsupported)
        asDid(target.did).then { did ->
            knot.git(host).getBlame(GetBlameQueryParams(did, ref, path.trim('/'))).toResult { answer ->
                val commits = answer.commits.associateBy { it.sha }
                answer.runs.mapNotNull { run ->
                    val commit = commits[run.commit] ?: return@mapNotNull null
                    BlameRange(
                        startLine = run.start.toInt(),
                        endLine = (run.start + run.count - 1).toInt(),
                        commit = Commit(commit.sha, commit.subject, commit.author.name, null, commit.authoredAt.toJava()),
                    )
                }
            }
        }
    }

    private companion object {
        const val SUBMIT_PATCH = "org.tangled.review.submitPatch"
        const val FILES_PAGE = 30
        const val HISTORY_PAGE = 30

        /** The tree git gives a repository with nothing in it, which a first commit is diffed against. */
        const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    }
}
