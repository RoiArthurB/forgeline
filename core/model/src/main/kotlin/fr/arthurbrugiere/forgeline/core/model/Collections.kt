package fr.arthurbrugiere.forgeline.core.model

/**
 * A list of repositories kept on the phone, from any forge: "Matrix tools", "Used on my Android". Made in the app,
 * or brought over from a GitHub list ([importedFrom] then names it, so importing again updates it).
 */
data class RepoCollection(
    val id: String,
    val name: String,
    val repoCount: Int,
    val importedFrom: String? = null,
)

/** One of the lists a GitHub user sorts their stars into, with what it holds. */
data class StarList(val id: String, val name: String, val description: String?, val repos: List<RepoSummary>)
