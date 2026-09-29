package fr.arthurbrugiere.forgeline.core.model

enum class ForgeType { GITHUB, FORGEJO }

/** A forge server: github.com, codeberg.org, or a self-hosted Forgejo instance. [host] is lowercase. */
data class ForgeInstance(val type: ForgeType, val host: String) {
    /** Where the forge's web pages live, without a trailing `/`. */
    val webUrl: String get() = "https://$host"

    /** How people call it: GitHub, Codeberg, or a self-hosted instance's host. */
    val displayName: String
        get() = when (this) {
            GitHub -> "GitHub"
            Codeberg -> "Codeberg"
            else -> host
        }

    companion object {
        val GitHub = ForgeInstance(ForgeType.GITHUB, "github.com")
        val Codeberg = ForgeInstance(ForgeType.FORGEJO, "codeberg.org")

        /** The forge at [host]: GitHub for github.com, else a Forgejo instance (Codeberg or self-hosted). */
        fun of(host: String): ForgeInstance {
            val lower = host.lowercase().removePrefix("www.")
            return if (lower == GitHub.host) GitHub else ForgeInstance(ForgeType.FORGEJO, lower)
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
