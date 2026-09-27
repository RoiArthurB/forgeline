package fr.arthurbrugiere.forgeline.navigation

import androidx.navigation3.runtime.NavKey
import java.net.URI

/** Maps forge web URLs to in-app destinations; anything unrecognized stays on the web. */
object ForgeLinks {
    private val gitHubHosts = setOf("github.com", "www.github.com")

    // First path segments that are GitHub pages, not user or organization names.
    private val reserved = setOf(
        "about", "apps", "codespaces", "collections", "contact", "customer-stories", "enterprise", "events", "explore",
        "features", "issues", "login", "marketplace", "new", "notifications", "orgs", "organizations", "pricing", "pulls",
        "readme", "search", "security", "settings", "site", "sponsors", "topics", "trending", "user-attachments",
    )

    fun routeFor(url: String): NavKey? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host?.lowercase() !in gitHubHosts) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments[0].lowercase() in reserved) return null
        if (segments.size == 1) return UserRoute(segments[0])
        val owner = segments[0]
        val name = segments[1].removeSuffix(".git")
        val number = segments.getOrNull(3)?.toIntOrNull()
        if (number != null && segments[2] in setOf("issues", "pull", "pulls")) return IssueRoute(owner, name, number)
        return RepoRoute(owner, name)
    }
}
