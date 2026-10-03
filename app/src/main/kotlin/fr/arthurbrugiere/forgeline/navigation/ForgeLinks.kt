package fr.arthurbrugiere.forgeline.navigation

import androidx.navigation3.runtime.NavKey
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import java.net.URI

/** Maps forge web URLs to in-app destinations; anything unrecognized stays on the web. */
object ForgeLinks {
    private val forges = listOf(ForgeInstance.GitHub, ForgeInstance.Codeberg)

    // First path segments that are the forge's own pages, not user or organization names.
    private val reserved = mapOf(
        ForgeType.GITHUB to setOf(
            "about", "apps", "codespaces", "collections", "contact", "customer-stories", "enterprise", "events", "explore",
            "features", "issues", "login", "marketplace", "new", "notifications", "orgs", "organizations", "pricing", "pulls",
            "readme", "search", "security", "settings", "site", "sponsors", "topics", "trending", "user-attachments",
        ),
        ForgeType.FORGEJO to setOf(
            "-", "admin", "api", "assets", "attachments", "avatars", "captcha", "explore", "issues", "login", "milestones",
            "notifications", "org", "pulls", "repo", "user", "users", ".well-known",
        ),
    )

    /** The forge a URL's host belongs to, among those Forgeline opens links for. */
    fun forgeOf(host: String?): ForgeInstance? {
        val lower = host?.lowercase()?.removePrefix("www.") ?: return null
        return forges.firstOrNull { it.host == lower }
    }

    fun routeFor(url: String): NavKey? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https")) return null
        val forge = forgeOf(uri.host) ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments[0].lowercase() in reserved.getValue(forge.type)) return null
        if (segments.size == 1) return UserRoute(forge.host, segments[0])
        val owner = segments[0]
        val name = segments[1].removeSuffix(".git")
        val number = segments.getOrNull(3)?.toIntOrNull()
        if (number != null && segments[2] in setOf("issues", "pull", "pulls")) return IssueRoute(forge.host, owner, name, number)
        // A release's page, on both forges: /owner/name/releases/tag/<tag>, where the tag may hold slashes.
        if (segments.size > 4 && segments[2] == "releases" && segments[3] == "tag") {
            return ReleaseRoute(forge.host, owner, name, segments.drop(4).joinToString("/"))
        }
        // A job's page opens its run, which lists the job.
        val runId = segments.getOrNull(4)?.toLongOrNull()
        if (runId != null && segments[2] == "actions" && segments[3] == "runs") {
            // Forgejo's run pages count runs per repository, not by the API's id: those stay in the browser.
            return if (forge.type == ForgeType.GITHUB) RunRoute(forge.host, owner, name, runId) else null
        }
        return RepoRoute(forge.host, owner, name)
    }
}

/**
 * Where a link opens. One tapped on a notification of an [unread] thread opens its conversation at what is new since
 * [lastReadAtMillis]; any other link opens what it names from the top.
 */
fun linkRoute(url: String, unread: Boolean = false, lastReadAtMillis: Long? = null): NavKey? {
    val route = ForgeLinks.routeFor(url)
    return if (route is IssueRoute && unread) route.copy(unread = true, lastReadAtMillis = lastReadAtMillis) else route
}
