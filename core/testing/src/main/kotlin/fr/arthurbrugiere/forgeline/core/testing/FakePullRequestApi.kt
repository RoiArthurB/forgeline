package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
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

    /** How long a write takes to be answered, off the caller's thread as a real forge's is; recorded once it is. */
    var writeLatencyMillis: Long = 0

    /** How long a read takes to be answered, off the caller's thread as a real forge's is. */
    var readLatencyMillis: Long = 0

    /** Each entry is a page. */
    var files: List<List<ChangedFile>> = emptyList()
    var commits: List<Commit> = emptyList()
    var checks: List<Check> = emptyList()
    val checkReports = mutableMapOf<Long, CheckReport>()
    var mergeInfo = MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH))

    /** Each entry is a page. */
    var history: List<List<Commit>> = emptyList()
    val commitDetails = mutableMapOf<String, CommitDetails>()
    var created = 100

    data class SentReview(val verdict: ReviewVerdict, val body: String, val comments: List<LineComment>)

    val reviews = mutableListOf<SentReview>()
    val opened = mutableListOf<NewPullRequest>()

    private fun IssueRef.name() = "${repo.fullName}#$number"

    private suspend fun <T> read(call: String, value: () -> T): ForgeResult<T> {
        answerLate(readLatencyMillis)
        calls += call
        return failure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(value())
    }

    private suspend fun <T> write(call: String, value: () -> T): ForgeResult<T> {
        answerLate(writeLatencyMillis)
        calls += call
        return writeFailure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(value())
    }

    override suspend fun files(token: String?, ref: IssueRef, page: Int) =
        read("files:${ref.name()}:$page") { ChangedFiles(files.getOrElse(page - 1) { emptyList() }, (page + 1).takeIf { it <= files.size }) }

    override suspend fun commits(token: String?, ref: IssueRef) = read("commits:${ref.name()}") { commits }

    override suspend fun checks(token: String?, ref: IssueRef) = read("checks:${ref.name()}") { checks }

    override suspend fun checkReport(token: String?, repo: RepoId, checkId: Long): ForgeResult<CheckReport> {
        calls += "checkReport:${repo.fullName}:$checkId"
        failure?.let { return ForgeResult.Failure(it) }
        return checkReports[checkId]?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    override suspend fun mergeInfo(token: String, ref: IssueRef) = read("mergeInfo:${ref.name()}") { mergeInfo }

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean) =
        write("merge:${ref.name()}:$method${if (deleteBranch) ":delete-branch" else ""}${if (whenChecksPass) ":when-checks-pass" else ""}") {
            if (whenChecksPass) mergeInfo = mergeInfo.copy(mergesWhenChecksPass = true)
        }

    /** The threads on the change, as the forge would answer them. */
    var reviewThreads: List<ReviewThread> = emptyList()

    override var resolvesThreads: Boolean = true

    override var dismissesReviews: Boolean = true

    override suspend fun threads(token: String?, ref: IssueRef) = read("threads:${ref.name()}") { reviewThreads }

    override suspend fun reply(token: String, ref: IssueRef, thread: ReviewThread, body: String) = write("reply:${ref.name()}:${thread.id}:$body") {
        reviewThreads = reviewThreads.map {
            if (it.id == thread.id) it.copy(comments = it.comments + ThreadComment(it.comments.size + 1000L, ForgeUser("octocat", null, null), body, null)) else it
        }
    }

    override var reactsToThreadComments: Boolean = true

    /** Counts the reaction once, and takes it back the second time, as the same reader. */
    override suspend fun toggleThreadReaction(token: String, login: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, reaction: Reaction) =
        write("threadReaction:${ref.name()}:${comment.id}:$reaction:$login") {
            val mine = "${comment.id}:$reaction:$login"
            val had = mine in givenReactions
            if (had) givenReactions -= mine else givenReactions += mine
            var counts = emptyMap<Reaction, Int>()
            reviewThreads = reviewThreads.map { t ->
                t.copy(
                    comments = t.comments.map { c ->
                        if (c.id != comment.id) return@map c
                        val now = (c.reactions[reaction] ?: 0) + if (had) -1 else 1
                        c.copy(reactions = if (now > 0) c.reactions + (reaction to now) else c.reactions - reaction).also { counts = it.reactions }
                    },
                )
            }
            counts
        }

    private val givenReactions = mutableSetOf<String>()

    override var commentsOnRanges: Boolean = true

    override var commentsOnFiles: Boolean = true

    override var editsThreadComments: Boolean = true

    override var deletesThreadComments: Boolean = true

    override suspend fun editThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment, body: String) =
        write("editThreadComment:${ref.name()}:${thread.id}:${comment.id}:$body") {
            reviewThreads = reviewThreads.map { t ->
                if (t.id == thread.id) t.copy(comments = t.comments.map { if (it.id == comment.id) it.copy(body = body) else it }) else t
            }
        }

    /** A thread goes with its last remark, as on a forge. */
    override suspend fun deleteThreadComment(token: String, ref: IssueRef, thread: ReviewThread, comment: ThreadComment) =
        write("deleteThreadComment:${ref.name()}:${thread.id}:${comment.id}") {
            reviewThreads = reviewThreads.mapNotNull { t ->
                if (t.id == thread.id) t.copy(comments = t.comments.filterNot { it.id == comment.id }).takeIf { it.comments.isNotEmpty() } else t
            }
        }

    override suspend fun setResolved(token: String, ref: IssueRef, thread: ReviewThread, resolved: Boolean) = write("resolved:${ref.name()}:${thread.id}:$resolved") {
        reviewThreads = reviewThreads.map { if (it.id == thread.id) it.copy(resolved = resolved) else it }
    }

    override var appliesSuggestions: Boolean = true

    /** A suggestion committed leaves its thread on code that has moved on, as on a forge. */
    override suspend fun applySuggestion(token: String, ref: IssueRef, thread: ReviewThread, suggestion: List<String>, message: String) =
        write("suggestion:${ref.name()}:${thread.id}:${suggestion.joinToString("|")}:$message") {
            reviewThreads = reviewThreads.map { if (it.id == thread.id) it.copy(outdated = true) else it }
        }

    override suspend fun dismissReview(token: String, ref: IssueRef, reviewId: Long, message: String) = write("dismiss:${ref.name()}:$reviewId:$message") { }

    override suspend fun updateBranch(token: String, ref: IssueRef) = write("updateBranch:${ref.name()}") {
        mergeInfo = mergeInfo.copy(behind = false)
    }

    override suspend fun cancelMergeWhenChecksPass(token: String, ref: IssueRef) = write("cancelMergeWhenChecksPass:${ref.name()}") {
        mergeInfo = mergeInfo.copy(mergesWhenChecksPass = false)
    }

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>) =
        write("review:${ref.name()}:$verdict") { reviews += SentReview(verdict, body, comments) }

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest) =
        write("create:${repo.fullName}:${request.head}->${request.base}") {
            opened += request
            IssueRef(repo, created, isPullRequest = true)
        }

    /** The pull request templates every repository offers; listed without their text where [templateTexts] has it, as GitLab does. */
    var templates = emptyList<IssueTemplate>()
    var templateTexts = emptyMap<String, String>()

    override suspend fun pullRequestTemplates(token: String, repo: RepoId, branch: String) = read("templates:${repo.fullName}@$branch") { templates }

    override suspend fun pullRequestTemplate(token: String, repo: RepoId, template: IssueTemplate) =
        read("template:${template.key}") { template.copy(body = template.body ?: templateTexts[template.key]) }

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
