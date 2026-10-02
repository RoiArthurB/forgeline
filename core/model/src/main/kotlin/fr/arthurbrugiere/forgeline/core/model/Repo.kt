package fr.arthurbrugiere.forgeline.core.model

/**
 * A repository on a forge. Two forges can each have an `alice/tool`, so the forge is part of its identity.
 * [forge] defaults to GitHub only until the app's caches and routes carry it; forge implementations always pass it.
 */
data class RepoId(val owner: String, val name: String, val forge: ForgeInstance = ForgeInstance.GitHub) {
    val fullName: String get() = "$owner/$name"

    /** The repository's page on its forge. */
    val webUrl: String get() = "${forge.webUrl}/$fullName"

    /** A stable text key for caches and routes, forge included: `github.com/owner/name`. */
    val key: String get() = "${forge.host}/$fullName"

    companion object {
        /** Reads a [key]; null when it isn't one. */
        fun fromKey(key: String): RepoId? {
            val parts = key.split('/')
            if (parts.size < 3 || parts.any { it.isEmpty() }) return null
            // GitLab owners can be nested groups (`group/subgroup`): everything between the host and the name.
            return RepoId(parts.subList(1, parts.size - 1).joinToString("/"), parts.last(), ForgeInstance.of(parts[0]))
        }
    }
}

enum class TrendingPeriod { DAILY, WEEKLY, MONTHLY }

data class TrendingRepo(
    val id: RepoId,
    val description: String?,
    val language: String?,
    /** Hex color like `#3178c6`, as the forge shows it. */
    val languageColor: String?,
    val stars: Int,
    val forks: Int,
    /** Stars gained during the trending period. */
    val periodStars: Int,
    val builtBy: List<ForgeUser>,
    /** The owner's (user or organisation) avatar. */
    val ownerAvatarUrl: String? = null,
)

data class RepoDetails(
    val id: RepoId,
    val description: String?,
    val homepage: String?,
    val topics: List<String>,
    val stars: Int,
    val forks: Int,
    val watchers: Int,
    val language: String?,
    /** SPDX id when known (e.g. `MIT`), else the license name. */
    val license: String?,
    val defaultBranch: String,
    val ownerAvatarUrl: String?,
    val isFork: Boolean,
    val isArchived: Boolean,
    val pushedAt: java.time.Instant?,
    /** Whether the repository uses the forge's CI; Forgejo repositories can switch it off. */
    val hasActions: Boolean = true,
    /** Whether the repository takes issues; its owner can switch them off. */
    val hasIssues: Boolean = true,
)

data class Readme(val path: String, val markdown: String)

/** Where a repository can be browsed: branches by name, tags newest version first. */
data class GitRefs(val branches: List<String>, val tags: List<String>)

enum class RepoFileType { FILE, DIR, SYMLINK, SUBMODULE }

data class RepoFile(val path: String, val name: String, val type: RepoFileType, val size: Long)

/** [color] is a hex color without `#`, as forges serve it. */
data class Label(val name: String, val color: String?)

enum class IssueState { OPEN, CLOSED, MERGED }

/** Which of a repository's issues or pull requests to list: the open or the closed ones, and words they must hold. */
data class IssueQuery(val open: Boolean = true, val text: String = "") {
    /** What a list shows before anyone asks for anything else. */
    val isDefault: Boolean get() = open && text.isBlank()
}

data class IssueSummary(
    val number: Int,
    val title: String,
    val state: IssueState,
    val author: ForgeUser?,
    /** Null when the forge doesn't report it in lists (e.g. GitHub pull requests). */
    val comments: Int?,
    val createdAt: java.time.Instant,
    val labels: List<Label>,
    val isPullRequest: Boolean,
    val isDraft: Boolean,
)

data class Release(
    val tag: String,
    val name: String?,
    val body: String?,
    val publishedAt: java.time.Instant?,
    val isPrerelease: Boolean,
    val author: ForgeUser?,
)

enum class RunStatus { QUEUED, IN_PROGRESS, COMPLETED, OTHER }

enum class RunConclusion { SUCCESS, FAILURE, CANCELLED, SKIPPED, NEUTRAL, TIMED_OUT, ACTION_REQUIRED, OTHER }

data class WorkflowRun(
    val id: Long,
    val workflowName: String,
    val title: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val branch: String?,
    val event: String,
    val runNumber: Int,
    val createdAt: java.time.Instant,
    val actor: ForgeUser?,
    val workflowId: Long? = null,
    /** 1 for the first run, then 2, 3... after each re-run. */
    val attempt: Int = 1,
    val startedAt: java.time.Instant? = null,
    val updatedAt: java.time.Instant? = null,
    /** The run's page, when the forge says it: Forgejo's pages count runs per repository, not by [id]. */
    val webUrl: String? = null,
)

data class RunStep(
    val number: Int,
    val name: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val startedAt: java.time.Instant? = null,
    val completedAt: java.time.Instant? = null,
)

data class RunJob(
    val id: Long,
    val name: String,
    val status: RunStatus,
    val conclusion: RunConclusion?,
    val startedAt: java.time.Instant?,
    val completedAt: java.time.Instant?,
    val steps: List<RunStep>,
)

data class Workflow(val id: Long, val name: String, val path: String)

enum class DispatchInputType { STRING, CHOICE, BOOLEAN, NUMBER, ENVIRONMENT }

/** An input a workflow asks for when started by hand. */
data class DispatchInput(
    val name: String,
    val description: String?,
    val type: DispatchInputType,
    val required: Boolean,
    val default: String?,
    val options: List<String>,
)

enum class LogLineKind { PLAIN, COMMAND, ERROR, WARNING, NOTICE, DEBUG }

/** A job's log: lines, some folded into named groups. Text may carry ANSI color codes. */
data class JobLog(val entries: List<LogEntry>)

sealed interface LogEntry {
    data class Line(val text: String, val kind: LogLineKind = LogLineKind.PLAIN) : LogEntry

    data class Group(val title: String, val lines: List<Line>) : LogEntry
}
