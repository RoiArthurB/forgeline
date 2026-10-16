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

/** Which of one's repositories the You tab shows: all, one owner's, or those other people shared. */
sealed interface RepoFilter {
    data object All : RepoFilter

    /** Owned by neither one's accounts nor an organisation: people who added one to their repository. */
    data object Shared : RepoFilter

    data class Of(val owner: RepoOwner) : RepoFilter
}

/** Every repository the signed-in accounts can reach, most recently pushed first, and whose they are. */
data class YourRepos(
    val repos: List<RepoSummary>,
    /** One's accounts first, then organisations by their latest push; those with nothing reachable last. */
    val owners: List<RepoOwner>,
    /** When the oldest account's list was read; null before any. */
    val syncedAtMillis: Long?,
) {
    /** Whether [repo] belongs to none of [owners]: someone shared it. */
    fun isShared(repo: RepoSummary) = owners.none { it.owns(repo) }

    val sharedCount: Int get() = repos.count(::isShared)

    fun shows(repo: RepoSummary, filter: RepoFilter): Boolean = when (filter) {
        RepoFilter.All -> true
        RepoFilter.Shared -> isShared(repo)
        is RepoFilter.Of -> filter.owner.owns(repo)
    }

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
            // The forge's list of organisations can leave some out (GitHub's, without the read:org permission, keeps
            // to public memberships): every organisation owning a repository one reaches counts too.
            val owning = sorted.filter { it.ownerIsOrganization == true }
                .map { UserSummary(it.id.owner, it.ownerAvatarUrl, isOrganization = true, forge = it.id.forge) }
            val orgs = (organizations + owning).distinctBy { it.forge to it.login.lowercase() }
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
