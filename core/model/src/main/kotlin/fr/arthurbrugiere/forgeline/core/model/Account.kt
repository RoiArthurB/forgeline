package fr.arthurbrugiere.forgeline.core.model

enum class ForgeType {
    GITHUB,
    FORGEJO,
    GITLAB;

    /** Whether merge requests have their own numbering, so that one can share its number with an issue. */
    val numbersMergeRequestsApart: Boolean get() = this == GITLAB
}

/**
 * A forge server: github.com, codeberg.org, a self-hosted Forgejo instance, or gitlab.com. [host]
 * is lowercase.
 */
data class ForgeInstance(val type: ForgeType, val host: String) {
    /** Where the forge's web pages live, without a trailing `/`. */
    val webUrl: String get() = "https://$host"

    /** How people call it: GitHub, Codeberg, or a self-hosted instance's host. */
    val displayName: String
        get() = when (this) {
            GitHub -> "GitHub"
            Codeberg -> "Codeberg"
            GitLab -> "GitLab"
            else -> host
        }

    /** Whether the app can sign in to the forge and open what's on it. */
    val isBrowsable: Boolean get() = true

    companion object {
        val GitHub = ForgeInstance(ForgeType.GITHUB, "github.com")
        val Codeberg = ForgeInstance(ForgeType.FORGEJO, "codeberg.org")
        val GitLab = ForgeInstance(ForgeType.GITLAB, "gitlab.com")

        /**
         * The forge at [host]: GitHub for github.com, GitLab for gitlab.com, and for any other host what
         * [KnownForges] was told it runs, Forgejo otherwise (Codeberg, or a self-hosted server never signed in to).
         */
        fun of(host: String): ForgeInstance {
            val lower = host.lowercase().removePrefix("www.")
            return when (lower) {
                GitHub.host -> GitHub
                GitLab.host -> GitLab
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
