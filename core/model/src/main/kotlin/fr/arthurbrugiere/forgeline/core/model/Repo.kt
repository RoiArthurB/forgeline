package fr.arthurbrugiere.forgeline.core.model

data class RepoId(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"
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
)

data class Readme(val path: String, val markdown: String)

enum class RepoFileType { FILE, DIR, SYMLINK, SUBMODULE }

data class RepoFile(val path: String, val name: String, val type: RepoFileType, val size: Long)

/** [color] is a hex color without `#`, as forges serve it. */
data class Label(val name: String, val color: String?)

enum class IssueState { OPEN, CLOSED, MERGED }

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
)
