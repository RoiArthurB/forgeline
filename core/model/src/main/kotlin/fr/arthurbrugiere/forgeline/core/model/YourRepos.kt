package fr.arthurbrugiere.forgeline.core.model

/** Someone whose repositories one can reach: oneself or an organisation one belongs to, on a forge. */
data class RepoOwner(
    val login: String,
    val avatarUrl: String?,
    val forge: ForgeInstance,
    val isOrganization: Boolean,
    /** One of the signed-in accounts itself. */
    val isYou: Boolean,
    /** How many of its repositories one can reach. */
    val repoCount: Int,
) {
    fun owns(repo: RepoSummary) = repo.id.forge == forge && repo.id.owner.equals(login, ignoreCase = true)
}

/** Every repository the signed-in accounts can reach, most recently pushed first, and whose they are. */
data class YourRepos(
    val repos: List<RepoSummary>,
    /** One's accounts first, then organisations by their latest push; those with nothing reachable last. */
    val owners: List<RepoOwner>,
    /** When the oldest account's list was read; null before any. */
    val syncedAtMillis: Long?,
) {
    companion object {
        /**
         * Builds the lists of several accounts into one: repositories by last push (a repository reached from two
         * accounts once), owners as described on [owners].
         */
        fun of(accounts: List<Pair<ForgeUser, ForgeInstance>>, repos: List<RepoSummary>, organizations: List<UserSummary>, syncedAtMillis: Long?): YourRepos {
            val sorted = repos.distinctBy { it.id }.sortedByDescending { it.pushedAt ?: it.updatedAt }
            fun count(login: String, forge: ForgeInstance) = sorted.count { it.id.forge == forge && it.id.owner.equals(login, ignoreCase = true) }
            fun latest(login: String, forge: ForgeInstance) = sorted.indexOfFirst { it.id.forge == forge && it.id.owner.equals(login, ignoreCase = true) }
            val you = accounts.map { (user, forge) -> RepoOwner(user.login, user.avatarUrl, forge, isOrganization = false, isYou = true, repoCount = count(user.login, forge)) }
            val orgs = organizations.distinctBy { it.forge to it.login.lowercase() }
                .map { RepoOwner(it.login, it.avatarUrl, it.forge, isOrganization = true, isYou = false, repoCount = count(it.login, it.forge)) }
                // Most recently active first; one with nothing reachable after every other.
                .sortedWith(compareBy({ latest(it.login, it.forge).let { at -> if (at < 0) Int.MAX_VALUE else at } }, { it.login.lowercase() }))
            return YourRepos(sorted, you + orgs, syncedAtMillis)
        }
    }
}

/** [query] matched against a repository's owner, name and description, ignoring case; blank matches everything. */
fun RepoSummary.matches(query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val text = "${id.fullName} ${description.orEmpty()}".lowercase()
    return words.all { it in text }
}
