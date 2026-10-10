package fr.arthurbrugiere.forgeline.core.data.pull

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.model.BlameRange
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
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

    suspend fun mergeInfo(ref: IssueRef): ForgeResult<MergeInfo>

    suspend fun merge(ref: IssueRef, method: MergeMethod): ForgeResult<Unit>

    /** The verdicts a review can give on [forge]. */
    fun verdicts(forge: ForgeInstance): Set<ReviewVerdict>

    suspend fun review(ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment> = emptyList()): ForgeResult<Unit>

    suspend fun create(repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef>

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

    override suspend fun mergeInfo(ref: IssueRef) = signedIn(ref.repo.forge) { mergeInfo(it, ref) }

    override suspend fun merge(ref: IssueRef, method: MergeMethod) = signedIn(ref.repo.forge) { merge(it, ref, method) }

    override fun verdicts(forge: ForgeInstance) = clients.pulls(forge).verdicts

    override suspend fun review(ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>) =
        signedIn(ref.repo.forge) { review(it, ref, verdict, body, comments) }

    override suspend fun create(repo: RepoId, request: NewPullRequest) = signedIn(repo.forge) { create(it, repo, request) }

    override suspend fun history(repo: RepoId, ref: String?, path: String?, page: Int) = read(repo.forge) { history(it, repo, ref, path, page) }

    override suspend fun commit(repo: RepoId, sha: String) = read(repo.forge) { commit(it, repo, sha) }

    override fun supportsBlame(forge: ForgeInstance) = clients.pulls(forge).supportsBlame

    override suspend fun blame(repo: RepoId, ref: String, path: String) = read(repo.forge) { blame(it, repo, ref, path) }
}
