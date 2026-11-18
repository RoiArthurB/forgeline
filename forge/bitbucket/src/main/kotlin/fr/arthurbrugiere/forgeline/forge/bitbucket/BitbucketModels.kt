package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.model.ChangedFile
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Account
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.BaseCommit
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Commitstatus
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Diffstat
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Pipeline
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PipelineStep
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Pullrequest
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PullrequestComment
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Repository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// From Bitbucket's answers (their shapes generated in the `api` package) to Forgeline's models.

internal val Bitbucket: ForgeInstance get() = ForgeInstance.Bitbucket

/** A repository's id in the app: its workspace's slug and its own, as its address on bitbucket.org has them. */
internal fun Repository.repoId(): RepoId? {
    val (workspace, slug) = fullName?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
    return RepoId(this.workspace?.slug ?: workspace, this.slug ?: slug, Bitbucket)
}

/** A repository's picture: its own, which Bitbucket gives every repository (its project's, or a generated one). */
private fun Repository.avatar(): String? = links?.avatar?.href

/**
 * A repository as the app shows it. Bitbucket has no stars, no topics and no licence field; it counts forks and
 * watchers on pages of their own ([forks], [watchers], asked apart). Issues and wikis are gone from Bitbucket (2026).
 */
internal fun Repository.toDetails(id: RepoId, forks: Int = 0, watchers: Int = 0, openPulls: Int? = null): RepoDetails = RepoDetails(
    id = id,
    description = description?.takeIf { it.isNotBlank() },
    homepage = website?.takeIf { it.isNotBlank() },
    topics = emptyList(),
    stars = 0,
    forks = forks,
    watchers = watchers,
    language = language?.takeIf { it.isNotBlank() },
    license = null,
    defaultBranch = mainbranch?.name ?: "main",
    ownerAvatarUrl = avatar(),
    isFork = parent != null,
    isArchived = false,
    pushedAt = bitbucketInstant(updatedOn),
    hasActions = true,
    hasIssues = false,
    hasWiki = false,
    openPulls = openPulls,
    // A repository nothing was pushed to has no main branch yet.
    isEmpty = mainbranch == null,
    listsReleases = true,
)

internal fun Repository.toSummary(): RepoSummary? {
    val id = repoId() ?: return null
    return RepoSummary(
        id = id,
        description = description?.takeIf { it.isNotBlank() },
        language = language?.takeIf { it.isNotBlank() },
        stars = 0,
        forks = 0,
        isFork = parent != null,
        updatedAt = bitbucketInstant(updatedOn),
        ownerAvatarUrl = avatar(),
        isPrivate = isPrivate == true,
        ownerIsOrganization = owner?.let { it.nickname == null && it.accountId == null },
        // Bitbucket has no stars: none is a fact here, not a guess.
        starsKnown = true,
    )
}

/** Where a pull request stands: Bitbucket's declined and superseded ones are closed without being merged. */
internal fun Pullrequest.issueState(): IssueState = when (state) {
    "OPEN" -> IssueState.OPEN
    "MERGED" -> IssueState.MERGED
    else -> IssueState.CLOSED
}

internal fun Pullrequest.toSummary(people: BitbucketPeople): IssueSummary = IssueSummary(
    number = id ?: 0,
    title = title.orEmpty(),
    state = issueState(),
    author = people.user(author),
    comments = commentCount,
    createdAt = bitbucketInstant(createdOn) ?: Instant.EPOCH,
    labels = emptyList(),
    isPullRequest = true,
    isDraft = draft == true,
    updatedAt = bitbucketInstant(updatedOn),
)

/** A pull request's repository, as its destination names it (where the pull request lives). */
internal fun Pullrequest.repoId(): RepoId? = destination?.repository?.repoId()

/** How Bitbucket's merge strategies are offered: each as the nearest of the app's three ways. */
internal fun mergeMethodOf(strategy: String): MergeMethod? = when (strategy) {
    "merge_commit" -> MergeMethod.MERGE
    "squash", "squash_fast_forward" -> MergeMethod.SQUASH
    "fast_forward", "rebase_fast_forward", "rebase_merge" -> MergeMethod.REBASE
    else -> null
}

/**
 * The strategy a merge by [method] asks for, among [strategies] the branch allows: the first that is that way,
 * Bitbucket's default for it where it is one of them.
 */
internal fun strategyFor(method: MergeMethod, strategies: List<String>, default: String?): String? =
    default?.takeIf { it in strategies && mergeMethodOf(it) == method } ?: strategies.firstOrNull { mergeMethodOf(it) == method }

internal fun BaseCommit.toCommit(people: BitbucketPeople): Commit = Commit(
    sha = hash.orEmpty(),
    message = message.orEmpty().trimEnd(),
    authorName = author?.raw?.substringBefore('<')?.trim()?.ifEmpty { null },
    author = people.user(author?.user),
    date = bitbucketInstant(date),
)

/** A file of a diffstat, with its change read from the whole diff, where it has one ([patches], by path). */
internal fun Diffstat.toChangedFile(patches: Map<String, ChangedFile>): ChangedFile? {
    val path = (new?.path ?: old?.path) ?: return null
    val change = when (status) {
        "added" -> FileChange.ADDED
        "removed" -> FileChange.REMOVED
        "renamed" -> FileChange.RENAMED
        else -> FileChange.MODIFIED
    }
    val patch = patches[path] ?: old?.path?.let { patches[it] }
    return ChangedFile(
        path = path,
        previousPath = old?.path.takeIf { change == FileChange.RENAMED },
        change = change,
        additions = linesAdded ?: patch?.additions ?: 0,
        deletions = linesRemoved ?: patch?.deletions ?: 0,
        patch = patch?.patch,
    )
}

internal fun Commitstatus.checkState(): CheckState = when (state) {
    "SUCCESSFUL" -> CheckState.SUCCESS
    "FAILED" -> CheckState.FAILURE
    "STOPPED" -> CheckState.SKIPPED
    else -> CheckState.PENDING
}

/** A Pipelines run's build number, read from its page's address, when this status is one of [repo]'s pipelines. */
internal fun Commitstatus.pipelineOf(repo: RepoId): Long? =
    url?.let { Regex("""bitbucket\.org/([^/]+)/([^/]+)/pipelines/results/(\d+)""", RegexOption.IGNORE_CASE).find(it) }
        ?.takeIf { it.groupValues[1].equals(repo.owner, ignoreCase = true) && it.groupValues[2].equals(repo.name, ignoreCase = true) }
        ?.groupValues?.get(3)?.toLongOrNull()

internal fun Commitstatus.toCheck(repo: RepoId): Check = Check(
    name = name?.takeIf { it.isNotBlank() } ?: key.orEmpty(),
    state = checkState(),
    description = description?.takeIf { it.isNotBlank() },
    url = url,
    runId = pipelineOf(repo),
)

/** A commit as an activity names it: its hash alone. */
@Serializable
internal data class StatusCommit(val hash: String? = null)

/** A pipeline's state: pending and in progress (with its stage, paused for an approval), or completed with a result. */
internal fun stateOf(name: String?, stage: String?, result: String?): Pair<RunStatus, RunConclusion?> = when (name) {
    "PENDING" -> RunStatus.QUEUED to null
    "IN_PROGRESS", "RUNNING" -> (if (stage == "PAUSED") RunStatus.OTHER else RunStatus.IN_PROGRESS) to null
    "COMPLETED" -> RunStatus.COMPLETED to when (result) {
        "SUCCESSFUL" -> RunConclusion.SUCCESS
        "FAILED", "ERROR" -> RunConclusion.FAILURE
        "STOPPED" -> RunConclusion.CANCELLED
        "EXPIRED" -> RunConclusion.TIMED_OUT
        "NOT_RUN", "SKIPPED" -> RunConclusion.SKIPPED
        else -> RunConclusion.OTHER
    }
    else -> RunStatus.OTHER to null
}

/**
 * A run of Pipelines. Its id is its build number, which Bitbucket takes in place of its UUID and which its pages are
 * addressed by. What started it is its trigger: a push, by hand, a schedule, or a pull request.
 */
internal fun Pipeline.toRun(id: RepoId, people: BitbucketPeople): WorkflowRun {
    val (status, conclusion) = stateOf(state?.name, state?.stage?.name, state?.result?.name)
    val number = buildNumber ?: 0
    val selector = target?.selector
    val name = when (selector?.type) {
        null, "default" -> "Pipelines"
        "custom" -> "custom: ${selector.pattern}"
        "pull-requests" -> "pull-requests: ${selector.pattern}"
        else -> "${selector.type}: ${selector.pattern}"
    }
    val title = target?.pullrequest?.title ?: target?.commit?.message?.lineSequence()?.firstOrNull()?.trim().orEmpty().ifEmpty { target?.refName ?: target?.source.orEmpty() }
    return WorkflowRun(
        id = number.toLong(),
        workflowName = name,
        title = title,
        status = status,
        conclusion = conclusion,
        branch = target?.refName ?: target?.source,
        event = when {
            target?.pullrequest != null || selector?.type == "pull-requests" -> "pull_request"
            selector?.type == "custom" -> "workflow_dispatch"
            else -> "push"
        },
        runNumber = number,
        createdAt = bitbucketInstant(createdOn) ?: Instant.EPOCH,
        actor = people.user(creator),
        startedAt = bitbucketInstant(createdOn),
        updatedAt = bitbucketInstant(completedOn),
        webUrl = "${id.webUrl}/pipelines/results/$number",
    )
}

/**
 * A step's id in the app: the run's build number and the step's place in it. Steps are named by UUID alone, and a job
 * is asked for by its id without its run ([fr.arthurbrugiere.forgeline.core.forge.ActionsApi.openJobLog]): the id
 * holds both, so a step can be found again from it.
 */
internal fun stepId(runId: Long, index: Int): Long = runId * STEPS_PER_RUN + index

internal fun runOfStep(jobId: Long): Long = jobId / STEPS_PER_RUN

internal fun indexOfStep(jobId: Long): Int = (jobId % STEPS_PER_RUN).toInt()

/** Bitbucket allows a hundred steps in a pipeline. */
internal const val STEPS_PER_RUN = 1000L

internal fun PipelineStep.toJob(runId: Long, index: Int): RunJob {
    val (status, conclusion) = stateOf(state?.name, null, state?.result?.name)
    return RunJob(
        id = stepId(runId, index),
        name = name?.takeIf { it.isNotBlank() } ?: "Step ${index + 1}",
        status = status,
        conclusion = conclusion,
        startedAt = bitbucketInstant(startedOn),
        completedAt = bitbucketInstant(completedOn),
        steps = emptyList(),
    )
}

/** A pull request's activity: what changed (a snapshot of it after each update), an approval, a request for changes, a comment. */
@Serializable
internal data class Activity(
    val update: ActivityUpdate? = null,
    val approval: ActivityReview? = null,
    @SerialName("changes_requested") val changesRequested: ActivityReview? = null,
    val comment: PullrequestComment? = null,
)

@Serializable
internal data class ActivityUpdate(
    val state: String? = null,
    val title: String? = null,
    val description: String? = null,
    val draft: Boolean? = null,
    val date: String? = null,
    val author: Account? = null,
    val source: ActivityEndpoint? = null,
    val destination: ActivityEndpoint? = null,
    val reviewers: List<Account>? = null,
)

@Serializable
internal data class ActivityEndpoint(val branch: ActivityBranch? = null, val commit: StatusCommit? = null)

@Serializable
internal data class ActivityBranch(val name: String? = null)

@Serializable
internal data class ActivityReview(val date: String? = null, val user: Account? = null)

@Serializable
internal data class ActivityPage(val values: List<Activity> = emptyList(), val next: String? = null)
