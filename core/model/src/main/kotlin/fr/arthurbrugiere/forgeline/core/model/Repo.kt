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
