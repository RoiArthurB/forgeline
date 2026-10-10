package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.countChanges
import java.time.Instant

/** Records every call as "method:owner/name#number:extra". Pages of files and of history are numbered from 1. */
class FakePullRequestApi(
    override val verdicts: Set<ReviewVerdict> = ReviewVerdict.entries.toSet(),
    override val supportsBlame: Boolean = true,
) : PullRequestApi {
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    /** When set, every read fails with it. */
    var failure: ForgeError? = null

    /** When set, every write fails with it. */
    var writeFailure: ForgeError? = null

    /** Each entry is a page. */
    var files: List<List<ChangedFile>> = emptyList()
    var commits: List<Commit> = emptyList()
    var checks: List<Check> = emptyList()
    var mergeInfo = MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH))

    /** Each entry is a page. */
    var history: List<List<Commit>> = emptyList()
    val commitDetails = mutableMapOf<String, CommitDetails>()
    var created = 100

    data class SentReview(val verdict: ReviewVerdict, val body: String, val comments: List<LineComment>)

    val reviews = mutableListOf<SentReview>()
    val opened = mutableListOf<NewPullRequest>()

    private fun IssueRef.name() = "${repo.fullName}#$number"

    private fun <T> read(call: String, value: () -> T): ForgeResult<T> {
        calls += call
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(value())
    }

    private fun <T> write(call: String, value: () -> T): ForgeResult<T> {
        calls += call
        return writeFailure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(value())
    }

    override suspend fun files(token: String?, ref: IssueRef, page: Int) =
        read("files:${ref.name()}:$page") { ChangedFiles(files.getOrElse(page - 1) { emptyList() }, (page + 1).takeIf { it <= files.size }) }

    override suspend fun commits(token: String?, ref: IssueRef) = read("commits:${ref.name()}") { commits }

    override suspend fun checks(token: String?, ref: IssueRef) = read("checks:${ref.name()}") { checks }

    override suspend fun mergeInfo(token: String, ref: IssueRef) = read("mergeInfo:${ref.name()}") { mergeInfo }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod) = write("merge:${ref.name()}:$method") { }

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>) =
        write("review:${ref.name()}:$verdict") { reviews += SentReview(verdict, body, comments) }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest) =
        write("create:${repo.fullName}:${request.head}->${request.base}") {
            opened += request
            IssueRef(repo, created, isPullRequest = true)
        }

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int) =
        read("history:${repo.fullName}:${ref.orEmpty()}:${path.orEmpty()}:$page") {
            CommitPage(history.getOrElse(page - 1) { emptyList() }, (page + 1).takeIf { it <= history.size })
        }

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> {
        calls += "commit:${repo.fullName}:$sha"
        failure?.let { return ForgeResult.Failure(it) }
        return commitDetails[sha]?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    var blame: List<BlameRange> = emptyList()

    override suspend fun blame(token: String?, repo: RepoId, ref: String, path: String) = read("blame:${repo.fullName}:$ref:$path") { blame }
}

fun commit(sha: String, message: String = "Commit $sha", author: String = "octocat", date: String = "2026-09-26T08:00:00Z") =
    Commit(sha, message, author, fr.arthurbrugiere.forgeline.core.model.ForgeUser(author, null, null), Instant.parse(date))

/** A changed file whose counts are those of its [patch]. */
fun changedFile(path: String, patch: String? = "@@ -1,2 +1,2 @@\n context\n-old\n+new", change: FileChange = FileChange.MODIFIED, previousPath: String? = null): ChangedFile {
    val (added, removed) = patch?.let(::countChanges) ?: (0 to 0)
    return ChangedFile(path, previousPath, change, added, removed, patch)
}
