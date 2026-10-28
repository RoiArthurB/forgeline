package fr.arthurbrugiere.forgeline.core.model

/**
 * The labels maintainers put on issues meant for someone's first contribution. Projects name them every way:
 * GitHub suggests "good first issue", Forgejo writes "Kind/Good First Issue", GitLab projects "easy-fix" or
 * "quick win::first-time contribution".
 */
object FirstIssueLabels {
    /** The names GitHub's search is asked for, which has to name them: its own label and the spellings seen beside it. */
    val searched = listOf("good first issue", "good-first-issue", "first-timers-only", "beginner", "beginner-friendly", "easy fix", "easy-fix")

    private val words = listOf("good first", "first timer", "first time", "first contribution", "beginner", "newcomer", "starter", "easy fix", "easy pick", "low hanging")

    /** Whether a label named [name] marks an issue for a first contribution. */
    fun matches(name: String): Boolean {
        val plain = name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return plain == "easy" || words.any { it in plain }
    }
}

/** Where a first issue was found: a repository one starred, or one trending today. */
enum class FirstIssueSource { STARRED, TRENDING }

/** An open issue marked for a first contribution, in a repository one starred or that is trending. */
data class FirstIssue(val repo: RepoId, val issue: IssueSummary, val source: FirstIssueSource)
