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
        if (segments.size < 2 || segments[0].lowercase() in reserved) return null
        return RepoRoute(segments[0], segments[1].removeSuffix(".git"))
    }
}
