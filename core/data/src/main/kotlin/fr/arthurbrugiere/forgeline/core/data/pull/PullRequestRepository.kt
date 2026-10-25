package fr.arthurbrugiere.forgeline.core.data.pull

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.accountOn
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What stands behind a pull request (its files, commits and checks), what the signed-in user can do to one, and a
 * repository's history. Reads go out with the account signed in on the forge, when there is one; writes need it.
 * Nothing is kept: a change is read again each time it is opened, since it moves with every push.
 */
interface PullRequestRepository {
    suspend fun files(ref: IssueRef, page: Int = 1): ForgeResult<ChangedFiles>

    suspend fun commits(ref: IssueRef): ForgeResult<List<Commit>>

    suspend fun checks(ref: IssueRef): ForgeResult<List<Check>>

    /** What one of those checks reported, by its [Check.checkId]. */
    suspend fun checkReport(repo: RepoId, checkId: Long): ForgeResult<CheckReport>

    suspend fun mergeInfo(ref: IssueRef): ForgeResult<MergeInfo>

    suspend fun merge(ref: IssueRef, method: MergeMethod, deleteBranch: Boolean = false, whenChecksPass: Boolean = false): ForgeResult<Unit>

    suspend fun cancelMergeWhenChecksPass(ref: IssueRef): ForgeResult<Unit>

    suspend fun updateBranch(ref: IssueRef): ForgeResult<Unit>

    suspend fun threads(ref: IssueRef): ForgeResult<List<ReviewThread>>

    suspend fun reply(ref: IssueRef, thread: ReviewThread, body: String): ForgeResult<Unit>

    /** Whether threads can be marked resolved on [forge]. */
    fun resolvesThreads(forge: ForgeInstance): Boolean

    /** Whether a remark can cover several lines, and be on a whole file, on [forge]. */
    fun commentsOnRanges(forge: ForgeInstance): Boolean

    fun commentsOnFiles(forge: ForgeInstance): Boolean

    /** Whether remarks on lines take reactions on [forge]. */
    fun reactsToThreadComments(forge: ForgeInstance): Boolean

    /** Gives [reaction] to [comment], or takes it back, as the account signed in on the forge. */
    suspend fun toggleThreadReaction(ref: IssueRef, thread: ReviewThread, comment: ThreadComment, reaction: Reaction): ForgeResult<Map<Reaction, Int>>

    /** Whether the reader's own remarks on lines can be rewritten, and taken back, on [forge]. */
    fun editsThreadComments(forge: ForgeInstance): Boolean

    fun deletesThreadComments(forge: ForgeInstance): Boolean

    suspend fun editThreadComment(ref: IssueRef, thread: ReviewThread, comment: ThreadComment, body: String): ForgeResult<Unit>

    suspend fun deleteThreadComment(ref: IssueRef, thread: ReviewThread, comment: ThreadComment): ForgeResult<Unit>

    suspend fun setResolved(ref: IssueRef, thread: ReviewThread, resolved: Boolean): ForgeResult<Unit>

    /** Whether a reviewer's suggestion can be committed from here on [forge]. */
    fun appliesSuggestions(forge: ForgeInstance): Boolean

    /** Commits [suggestion] in place of the lines [thread] is on, to [ref]'s branch, saying [message]. */
    suspend fun applySuggestion(ref: IssueRef, thread: ReviewThread, suggestion: List<String>, message: String): ForgeResult<Unit>

    /** Whether a review can be dismissed on [forge]. */
    fun dismissesReviews(forge: ForgeInstance): Boolean

    suspend fun dismissReview(ref: IssueRef, reviewId: Long, message: String): ForgeResult<Unit>

    /** The verdicts a review can give on [forge]. */
    fun verdicts(forge: ForgeInstance): Set<ReviewVerdict>

    suspend fun review(ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment> = emptyList()): ForgeResult<Unit>

    suspend fun create(repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef>

    /** The templates [repo] offers for a pull request's description, kept on [branch], its default one; Unauthorized signed out. */
    suspend fun templates(repo: RepoId, branch: String): ForgeResult<List<IssueTemplate>>

    /** [template] with its text, asked for where the forge lists its templates without it. */
    suspend fun template(repo: RepoId, template: IssueTemplate): ForgeResult<IssueTemplate>

    suspend fun history(repo: RepoId, ref: String?, path: String?, page: Int = 1): ForgeResult<CommitPage>

    suspend fun commit(repo: RepoId, sha: String): ForgeResult<CommitDetails>

    fun supportsBlame(forge: ForgeInstance): Boolean

    suspend fun blame(repo: RepoId, ref: String, path: String): ForgeResult<List<BlameRange>>
}

@Singleton
class DefaultPullRequestRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) : PullRequestRepository {

    private suspend fun <T> read(forge: ForgeInstance, call: suspend PullRequestApi.(token: String?) -> ForgeResult<T>): ForgeResult<T> =
        clients.pulls(forge).call(accounts.tokenOn(forge))

    private suspend fun <T> signedIn(forge: ForgeInstance, call: suspend PullRequestApi.(token: String) -> ForgeResult<T>): ForgeResult<T> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.pulls(forge).call(token)
    }

    override suspend fun files(ref: IssueRef, page: Int) = read(ref.repo.forge) { files(it, ref, page) }

    override suspend fun commits(ref: IssueRef) = read(ref.repo.forge) { commits(it, ref) }

    override suspend fun checks(ref: IssueRef) = read(ref.repo.forge) { checks(it, ref) }

    override suspend fun checkReport(repo: RepoId, checkId: Long) = read(repo.forge) { checkReport(it, repo, checkId) }

    override suspend fun mergeInfo(ref: IssueRef) = signedIn(ref.repo.forge) { mergeInfo(it, ref) }

    override suspend fun merge(ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean) =
        signedIn(ref.repo.forge) { merge(it, ref, method, deleteBranch, whenChecksPass) }

    override suspend fun cancelMergeWhenChecksPass(ref: IssueRef) = signedIn(ref.repo.forge) { cancelMergeWhenChecksPass(it, ref) }

    override suspend fun updateBranch(ref: IssueRef) = signedIn(ref.repo.forge) { updateBranch(it, ref) }

    override suspend fun threads(ref: IssueRef) = read(ref.repo.forge) { threads(it, ref) }

    override suspend fun reply(ref: IssueRef, thread: ReviewThread, body: String) = signedIn(ref.repo.forge) { reply(it, ref, thread, body) }

    override fun resolvesThreads(forge: ForgeInstance) = clients.pulls(forge).resolvesThreads

    override fun reactsToThreadComments(forge: ForgeInstance) = clients.pulls(forge).reactsToThreadComments

    override fun commentsOnRanges(forge: ForgeInstance) = clients.pulls(forge).commentsOnRanges

    override fun commentsOnFiles(forge: ForgeInstance) = clients.pulls(forge).commentsOnFiles

    override suspend fun toggleThreadReaction(ref: IssueRef, thread: ReviewThread, comment: ThreadComment, reaction: Reaction): ForgeResult<Map<Reaction, Int>> {
        val account = accounts.accountOn(ref.repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return signedIn(ref.repo.forge) { toggleThreadReaction(it, account.user.login, ref, thread, comment, reaction) }
    }

    override fun editsThreadComments(forge: ForgeInstance) = clients.pulls(forge).editsThreadComments

    override fun deletesThreadComments(forge: ForgeInstance) = clients.pulls(forge).deletesThreadComments

    override suspend fun editThreadComment(ref: IssueRef, thread: ReviewThread, comment: ThreadComment, body: String) =
        signedIn(ref.repo.forge) { editThreadComment(it, ref, thread, comment, body) }

    override suspend fun deleteThreadComment(ref: IssueRef, thread: ReviewThread, comment: ThreadComment) =
        signedIn(ref.repo.forge) { deleteThreadComment(it, ref, thread, comment) }

    override suspend fun setResolved(ref: IssueRef, thread: ReviewThread, resolved: Boolean) = signedIn(ref.repo.forge) { setResolved(it, ref, thread, resolved) }

    override fun appliesSuggestions(forge: ForgeInstance) = clients.pulls(forge).appliesSuggestions

    override suspend fun applySuggestion(ref: IssueRef, thread: ReviewThread, suggestion: List<String>, message: String) =
        signedIn(ref.repo.forge) { applySuggestion(it, ref, thread, suggestion, message) }

    override fun dismissesReviews(forge: ForgeInstance) = clients.pulls(forge).dismissesReviews

    override suspend fun dismissReview(ref: IssueRef, reviewId: Long, message: String) = signedIn(ref.repo.forge) { dismissReview(it, ref, reviewId, message) }

    override fun verdicts(forge: ForgeInstance) = clients.pulls(forge).verdicts

    override suspend fun review(ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>) =
        signedIn(ref.repo.forge) { review(it, ref, verdict, body, comments) }

    override suspend fun create(repo: RepoId, request: NewPullRequest) = signedIn(repo.forge) { create(it, repo, request) }

    override suspend fun templates(repo: RepoId, branch: String) = signedIn(repo.forge) { pullRequestTemplates(it, repo, branch) }

    override suspend fun template(repo: RepoId, template: IssueTemplate) = signedIn(repo.forge) { pullRequestTemplate(it, repo, template) }

    override suspend fun history(repo: RepoId, ref: String?, path: String?, page: Int) = read(repo.forge) { history(it, repo, ref, path, page) }

    override suspend fun commit(repo: RepoId, sha: String) = read(repo.forge) { commit(it, repo, sha) }

    override fun supportsBlame(forge: ForgeInstance) = clients.pulls(forge).supportsBlame

    override suspend fun blame(repo: RepoId, ref: String, path: String) = read(repo.forge) { blame(it, repo, ref, path) }
}
