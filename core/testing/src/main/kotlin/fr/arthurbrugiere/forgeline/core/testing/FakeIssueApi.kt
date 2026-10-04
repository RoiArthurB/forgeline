package fr.arthurbrugiere.forgeline.core.testing

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
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

    /** Timeline pages the forge fails to serve, while the others come. */
    val failingPages = mutableSetOf<Int>()

    override var issueOnly: Set<ConversationAction> = emptySet()
    // Conversations are loaded ahead several at a time, on background threads: what records them takes that.
    val calls: MutableList<String> = CopyOnWriteArrayList()
    val tokens: MutableList<String?> = CopyOnWriteArrayList()

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
        if (page in failingPages) return ForgeResult.Failure(ForgeError.Network)
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

    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> {
        calls += "setOpen:${ref.repo.fullName}#${ref.number}"
        gate?.await()
        tokens += token
        stateFailure?.let { return ForgeResult.Failure(it) }
        stateChanges += "${ref.repo.fullName}#${ref.number}: ${if (open) "open" else "closed"}" + reason?.let { " as ${it.name.lowercase()}" }.orEmpty()
        issues[ref]?.let { issues[ref] = it.copy(state = if (open) IssueState.OPEN else IssueState.CLOSED, stateReason = reason?.name?.lowercase()) }
        return ForgeResult.Success(Unit)
    }

    /** What this forge can do; everything unless a test says otherwise. */
    override var actions: Set<ConversationAction> = ConversationAction.entries.toSet()

    var repoLabels = listOf(Label("bug", "d73a4a"), Label("enhancement", "a2eeef"), Label("question", "d876e3"))
    var assignableUsers = listOf(ForgeUser("octocat", null, null), ForgeUser("hubot", null, null))
    var repoMilestones = listOf(Milestone(4, "2026.10"), Milestone(5, "2026.11"))
    val pinned = mutableSetOf<IssueRef>()

    /** Where a transferred issue lands: the same number, unless said otherwise. */
    var transferredNumber: Int? = null

    /** Everything done through the calls below, as "labels octo/repo#7: bug, question". */
    val managed = mutableListOf<String>()

    /** What the calls below fail with, apart from [failure], which is for reading. */
    var manageFailure: ForgeError? = null

    private suspend fun <T> manage(token: String, what: String, change: () -> T): ForgeResult<T> {
        calls += "manage:$what"
        gate?.await()
        tokens += token
        manageFailure?.let { return ForgeResult.Failure(it) }
        managed += what
        return ForgeResult.Success(change())
    }

    private fun <T> read(token: String, what: String, value: T): ForgeResult<T> {
        calls += what
        tokens += token
        manageFailure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(value)
    }

    private val IssueRef.label get() = "${repo.fullName}#$number"

    override suspend fun labels(token: String, repo: RepoId) = read(token, "labels:${repo.fullName}", repoLabels)

    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>) = manage(token, "labels ${ref.label}: ${names.joinToString()}") {
        issues[ref]?.let { issue -> issues[ref] = issue.copy(labels = names.map { name -> repoLabels.firstOrNull { it.name == name } ?: Label(name, null) }) }
        Unit
    }

    override suspend fun assignable(token: String, repo: RepoId) = read(token, "assignable:${repo.fullName}", assignableUsers)

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>) = manage(token, "assignees ${ref.label}: ${logins.joinToString()}") {
        issues[ref]?.let { issues[ref] = it.copy(assignees = logins.map { login -> ForgeUser(login, null, null) }) }
        Unit
    }

    override suspend fun milestones(token: String, repo: RepoId) = read(token, "milestones:${repo.fullName}", repoMilestones)

    override suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?) = manage(token, "milestone ${ref.label}: ${milestone?.title}") {
        issues[ref]?.let { issues[ref] = it.copy(milestone = milestone) }
        Unit
    }

    override suspend fun setLocked(token: String, ref: IssueRef, locked: Boolean) = manage(token, "${if (locked) "lock" else "unlock"} ${ref.label}") {
        issues[ref]?.let { issues[ref] = it.copy(isLocked = locked) }
        Unit
    }

    override suspend fun isPinned(token: String, ref: IssueRef) = read(token, "isPinned:${ref.label}", ref in pinned)

    override suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean) = manage(token, "${if (pinned) "pin" else "unpin"} ${ref.label}") {
        if (pinned) this.pinned += ref else this.pinned -= ref
        Unit
    }

    override suspend fun transfer(token: String, ref: IssueRef, to: RepoId) = manage(token, "transfer ${ref.label} to ${to.fullName}") {
        val moved = IssueRef(to, transferredNumber ?: ref.number)
        issues.remove(ref)?.let { issues[moved] = it.copy(ref = moved) }
        pages.remove(ref to 1)?.let { pages[moved to 1] = it }
        moved
    }

    override suspend fun delete(token: String, ref: IssueRef) = manage(token, "delete ${ref.label}") {
        issues.remove(ref)
        Unit
    }

    override suspend fun setDueDate(token: String, ref: IssueRef, date: java.time.LocalDate?) = manage(token, "due ${ref.label}: $date") {
        issues[ref]?.let { issues[ref] = it.copy(dueDate = date) }
        Unit
    }

    /** Seconds spent on each conversation, and the ones the signed-in user's timer runs on. */
    val spent = mutableMapOf<IssueRef, Long>()
    val timers = mutableMapOf<IssueRef, Instant>()

    /** How long a timer ran when it is stopped. */
    var timerSeconds = 600L

    override suspend fun timeTracking(token: String, ref: IssueRef) = read(token, "tracking:${ref.label}", TimeTracking(spent[ref] ?: 0, timers[ref]))

    override suspend fun setTimerRunning(token: String, ref: IssueRef, running: Boolean) = manage(token, "${if (running) "start" else "stop"} timer ${ref.label}") {
        if (running) {
            timers[ref] = Instant.parse("2026-10-02T08:00:00Z")
        } else if (timers.remove(ref) != null) {
            spent[ref] = (spent[ref] ?: 0) + timerSeconds
        }
        Unit
    }

    override suspend fun addTime(token: String, ref: IssueRef, seconds: Long) = manage(token, "time ${ref.label}: $seconds") {
        spent[ref] = (spent[ref] ?: 0) + seconds
        Unit
    }

    val dependsOn = mutableMapOf<IssueRef, List<LinkedIssue>>()

    override suspend fun dependencies(token: String, ref: IssueRef) = read(token, "dependencies:${ref.label}", dependsOn[ref].orEmpty())

    override suspend fun addDependency(token: String, ref: IssueRef, on: IssueRef) = manage(token, "depend ${ref.label} on ${on.label}") {
        dependsOn[ref] = dependsOn[ref].orEmpty() + LinkedIssue(on, issues[on]?.title ?: "Issue ${on.number}", issues[on]?.state ?: IssueState.OPEN)
    }

    override suspend fun removeDependency(token: String, ref: IssueRef, on: IssueRef) = manage(token, "undepend ${ref.label} on ${on.label}") {
        dependsOn[ref] = dependsOn[ref].orEmpty().filterNot { it.ref == on }
    }

    /** What the signed-in user may do in each repository; nothing where not said. */
    val access = mutableMapOf<RepoId, RepoAccess>()

    /** What asking for it fails with. */
    var accessFailure: ForgeError? = null

    /** What each repository switches off; nothing where not said. */
    val switchedOff = mutableMapOf<RepoId, Set<ConversationAction>>()

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> {
        calls += "access:${repo.fullName}"
        accessFailure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(RepoRights(access[repo] ?: RepoAccess.NONE, switchedOff[repo].orEmpty()))
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
