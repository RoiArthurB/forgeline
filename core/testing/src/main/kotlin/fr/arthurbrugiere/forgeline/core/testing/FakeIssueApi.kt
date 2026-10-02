package fr.arthurbrugiere.forgeline.core.testing

import kotlinx.coroutines.CompletableDeferred
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import java.time.Instant

class FakeIssueApi : IssueApi {
    val issues = mutableMapOf<IssueRef, IssueDetails>()
    val pages = mutableMapOf<Pair<IssueRef, Int>, TimelinePage>()
    var failure: ForgeError? = null
    val calls = mutableListOf<String>()
    val tokens = mutableListOf<String?>()

    /** When set, issues wait for it: a slow forge. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> {
        calls += "issue:${ref.repo.fullName}#${ref.number}"
        gate?.await()
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        return issues[ref]?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> {
        calls += "timeline:${ref.repo.fullName}#${ref.number}@$page"
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(pages[ref to page] ?: TimelinePage(emptyList(), null))
    }

    /** Comments posted, as "owner/name#number: body". */
    val posted = mutableListOf<String>()

    /** What posting a comment fails with, apart from [failure], which is for reading. */
    var commentFailure: ForgeError? = null

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> {
        calls += "comment:${ref.repo.fullName}#${ref.number}"
        gate?.await()
        tokens += token
        commentFailure?.let { return ForgeResult.Failure(it) }
        posted += "${ref.repo.fullName}#${ref.number}: $body"
        return ForgeResult.Success(comment(1_000L + posted.size, body, login = "me"))
    }

    /** Issues opened, as "owner/name: title / body". */
    val opened = mutableListOf<String>()

    /** What opening an issue fails with, apart from [failure], which is for reading. */
    var createFailure: ForgeError? = null

    /** The number the forge gives the next issue opened. */
    var nextNumber = 100

    override suspend fun create(token: String, repo: RepoId, title: String, body: String): ForgeResult<IssueDetails> {
        calls += "create:${repo.fullName}"
        gate?.await()
        tokens += token
        createFailure?.let { return ForgeResult.Failure(it) }
        opened += "${repo.fullName}: $title / $body"
        val created = issueDetails(IssueRef(repo, nextNumber++), title).copy(body = body.ifBlank { null }, author = ForgeUser("me", null, null), comments = 0)
        issues[created.ref] = created
        return ForgeResult.Success(created)
    }

    /** Conversations closed and reopened, as "owner/name#number: closed". */
    val stateChanges = mutableListOf<String>()

    /** What closing or reopening fails with, apart from [failure], which is for reading. */
    var stateFailure: ForgeError? = null

    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean): ForgeResult<Unit> {
        calls += "setOpen:${ref.repo.fullName}#${ref.number}"
        gate?.await()
        tokens += token
        stateFailure?.let { return ForgeResult.Failure(it) }
        stateChanges += "${ref.repo.fullName}#${ref.number}: ${if (open) "open" else "closed"}"
        issues[ref]?.let { issues[ref] = it.copy(state = if (open) IssueState.OPEN else IssueState.CLOSED) }
        return ForgeResult.Success(Unit)
    }

    /** What the signed-in user may do in each repository; nothing where not said. */
    val access = mutableMapOf<RepoId, RepoAccess>()

    /** What asking for it fails with. */
    var accessFailure: ForgeError? = null

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoAccess> {
        calls += "access:${repo.fullName}"
        accessFailure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(access[repo] ?: RepoAccess.NONE)
    }
}

class FakeUserApi : UserApi {
    val users = mutableMapOf<String, UserProfile>()
    val repos = mutableMapOf<String, List<RepoSummary>>()
    val starred = mutableMapOf<String, List<RepoSummary>>()
    val following = mutableSetOf<String>()
    var failure: ForgeError? = null
    val tokens = mutableListOf<String?>()

    private fun <T> answer(token: String?, value: () -> T?): ForgeResult<T> {
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        return value()?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    override suspend fun user(token: String?, login: String) = answer(token) { users[login] }

    override suspend fun repos(token: String?, login: String) = answer(token) { repos[login] ?: emptyList() }

    override suspend fun starred(token: String?, login: String) = answer(token) { starred[login] ?: emptyList() }

    override suspend fun isFollowing(token: String, login: String) = answer(token) { login in following }

    override suspend fun setFollowing(token: String, login: String, follow: Boolean) = answer(token) {
        if (follow) following += login else following -= login
    }
}

fun issueDetails(ref: IssueRef, title: String = "An issue", state: IssueState = IssueState.OPEN) = IssueDetails(
    ref = ref, title = title, body = "Body of #${ref.number}", state = state, stateReason = null,
    author = ForgeUser("octocat", null, null), labels = emptyList(), createdAt = Instant.parse("2026-09-26T08:00:00Z"),
    closedAt = null, comments = 1, reactions = emptyMap(), pullRequest = null,
)

fun comment(id: Long, body: String, login: String = "octocat") = TimelineItem.Comment(
    id = id, author = ForgeUser(login, null, null), body = body, createdAt = Instant.parse("2026-09-26T09:00:00Z"), reactions = emptyMap(),
)

fun userProfile(login: String, name: String? = null) = UserProfile(
    login = login, name = name, avatarUrl = null, bio = "Bio of $login", company = null, location = null, website = null,
    followers = 10, following = 2, publicRepos = 3, isOrganization = false, createdAt = null,
)
