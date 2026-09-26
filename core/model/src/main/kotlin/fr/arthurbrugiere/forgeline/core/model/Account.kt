package fr.arthurbrugiere.forgeline.core.model

enum class ForgeType { GITHUB }

/** A forge server: github.com today, self-hosted GitLab/Forgejo instances later. */
data class ForgeInstance(val type: ForgeType, val host: String) {
    companion object {
        val GitHub = ForgeInstance(ForgeType.GITHUB, "github.com")
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
