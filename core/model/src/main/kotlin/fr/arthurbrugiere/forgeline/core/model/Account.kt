package fr.arthurbrugiere.forgeline.core.model

enum class ForgeType {
    GITHUB,
    FORGEJO,
    GITLAB,

    /** Gitea, which Forgejo forked from: the same API, with a few calls one has and the other lacks. */
    GITEA,

    /** Tangled (tangled.org), built on the AT Protocol: one forge, whose git servers ("knots") anyone can run. */
    TANGLED,

    /** SourceHut (git.sr.ht): repositories read through its GraphQL API, which asks for a token on every call. */
    SOURCEHUT;

    /** Whether merge requests have their own numbering, so that one can share its number with an issue. */
    val numbersMergeRequestsApart: Boolean get() = this == GITLAB

    /** Whether the forge answers Forgejo's API (`/api/v1`) and lays out its web pages the same way: Forgejo and Gitea. */
    val speaksForgejo: Boolean get() = this == FORGEJO || this == GITEA

    /**
     * Whether the forge picks its own Trending: Tangled's is a handful of repositories its own ranking chose, shown as it
     * gives them, while the other lists are every repository that gained stars and go through the page's star floor.
     */
    val curatesTrending: Boolean get() = this == TANGLED

    /** Whether people star, watch and fork repositories and follow each other there: SourceHut has none of it. */
    val isSocial: Boolean get() = this != SOURCEHUT

    /** Whether the forge keeps notifications for an account to read: Tangled and SourceHut have none an app can. */
    val hasNotifications: Boolean get() = this != TANGLED && this != SOURCEHUT
}

/**
 * A forge server: github.com, codeberg.org, gitlab.com, gitea.com, tangled.org, git.sr.ht, or a self-hosted Forgejo, Gitea or GitLab. [host]
 * is lowercase.
 */
data class ForgeInstance(val type: ForgeType, val host: String) {
    /** Where the forge's web pages live, without a trailing `/`. */
    val webUrl: String get() = "https://$host"

    /**
     * The server's address alone. A forge installed under a path of its server (Forgejo's `ROOT_URL`, GitLab's relative
     * URL root) keeps that path in [host], as `example.org/git`; this is `example.org`.
     */
    val server: String get() = host.substringBefore('/')

    /**
     * The path of a page of this forge from where the forge starts, `/owner/name` for `example.org/git/owner/name`
     * when [host] is `example.org/git`; null when a URL at [urlHost] with [path] isn't one of its pages. [path] is a
     * URL's own, raw or decoded, starting with `/` (or empty).
     */
    fun pathOf(urlHost: String?, path: String): String? {
        if (urlHost?.lowercase()?.removePrefix("www.") != server) return null
        val base = host.substringAfter('/', "")
        if (base.isEmpty()) return path
        val prefix = "/$base"
        return when {
            path.trimEnd('/').equals(prefix, ignoreCase = true) -> path.substring(prefix.length)
            path.startsWith("$prefix/", ignoreCase = true) -> path.substring(prefix.length)
            else -> null
        }
    }

    /** How people call it: GitHub, Codeberg, or a self-hosted instance's host. */
    val displayName: String
        get() = when (this) {
            GitHub -> "GitHub"
            Codeberg -> "Codeberg"
            GitLab -> "GitLab"
            Gitea -> "Gitea"
            Tangled -> "Tangled"
            SourceHut -> "SourceHut"
            else -> host
        }

    /** Whether the app can sign in to the forge and open what's on it. */
    val isBrowsable: Boolean get() = true

    companion object {
        val GitHub = ForgeInstance(ForgeType.GITHUB, "github.com")
        val Codeberg = ForgeInstance(ForgeType.FORGEJO, "codeberg.org")
        val GitLab = ForgeInstance(ForgeType.GITLAB, "gitlab.com")
        val Gitea = ForgeInstance(ForgeType.GITEA, "gitea.com")
        val Tangled = ForgeInstance(ForgeType.TANGLED, "tangled.org")
        val SourceHut = ForgeInstance(ForgeType.SOURCEHUT, "git.sr.ht")

        /**
         * The forge at [host]: GitHub for github.com, GitLab for gitlab.com, Codeberg for codeberg.org, Gitea for gitea.com, Tangled for tangled.org, SourceHut for git.sr.ht, and for any
         * other host what [KnownForges] was told it runs, Forgejo otherwise (a self-hosted server never signed in to).
         */
        fun of(host: String): ForgeInstance {
            val lower = host.lowercase().removePrefix("www.")
            return when (lower) {
                GitHub.host -> GitHub
                GitLab.host -> GitLab
                Codeberg.host -> Codeberg
                Gitea.host -> Gitea
                // Tangled's former address.
                Tangled.host, "tangled.sh" -> Tangled
                SourceHut.host, "sr.ht" -> SourceHut
                else -> ForgeInstance(KnownForges.typeOf(lower) ?: ForgeType.FORGEJO, lower)
            }
        }
    }
}

data class ForgeUser(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
)

data class Account(
    val id: String,
    val forge: ForgeInstance,
    val user: ForgeUser,
) {
    companion object {
        // Forge logins are case-insensitive, so the id must be too.
        fun idFor(forge: ForgeInstance, login: String): String =
            "${forge.type.name.lowercase()}:${forge.host}:${login.lowercase()}"
    }
}
