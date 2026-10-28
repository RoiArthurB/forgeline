package fr.arthurbrugiere.forgeline.di

import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A forge signed in to, and the token that reads its private repositories. */
data class PictureAccess(val forge: ForgeInstance, val token: String)

/** How a picture of a private repository is read: from where, with which header. */
data class AuthenticatedPicture(val url: String, val header: String, val value: String)

/**
 * How to read the picture at [url] with one of [access]'s tokens, or null when it isn't a forge's file there.
 *
 * GitHub's raw host and Forgejo's raw addresses take the token as it is (checked 2026-10-28 on a private repository
 * of each: 404 without it, 200 with it). GitLab's `/-/raw/` doesn't: it sends a token's request to its sign-in page
 * (302), so the file is read from its API instead, which does. The token goes to no other host.
 */
fun authenticatedPicture(url: String, access: List<PictureAccess>): AuthenticatedPicture? {
    val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme == "https" } ?: return null
    val host = uri.host?.lowercase() ?: return null
    val path = uri.rawPath.orEmpty()
    for ((forge, token) in access) {
        when (forge.type) {
            ForgeType.GITHUB -> if (host == GITHUB_RAW_HOST) return AuthenticatedPicture(url, AUTHORIZATION, "token $token")
            // /owner/name/raw/branch/<ref>/<path>, and the same under /media/ for files kept in LFS.
            ForgeType.FORGEJO -> if (host == forge.host && path.split('/').getOrNull(3) in setOf("raw", "media")) {
                return AuthenticatedPicture(url, AUTHORIZATION, "token $token")
            }
            ForgeType.GITLAB -> if (host == forge.host) gitLabApiFile(forge, path)?.let { return AuthenticatedPicture(it, AUTHORIZATION, "Bearer $token") }
        }
    }
    return null
}

/**
 * GitLab's API address of the file at the web address [path] (`/group/project/-/raw/<ref>/<file>`). A branch with a
 * slash in its name can't be told from the folders after it: the first segment is taken as the ref, which holds for
 * every default branch.
 */
private fun gitLabApiFile(forge: ForgeInstance, path: String): String? {
    val (project, rest) = path.removePrefix("/").split("/-/raw/", limit = 2).takeIf { it.size == 2 } ?: return null
    val ref = rest.substringBefore('/').takeIf { it.isNotEmpty() } ?: return null
    val file = rest.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
    fun encoded(raw: String) = URLEncoder.encode(URLDecoder.decode(raw, "UTF-8"), "UTF-8").replace("+", "%20")
    return "${forge.webUrl}/api/v4/projects/${encoded(project)}/repository/files/${encoded(file)}/raw?ref=${encoded(ref)}"
}

/**
 * A picture that didn't come is asked again with the reader's token when it is a file of a forge they're signed in
 * to: a private repository's pictures (in its README or its code browser) are refused to anyone else. Asked without
 * first, so a public picture costs nothing more and is cached as everyone gets it; kept under the address asked, so
 * the next time it comes from the cache at once.
 */
class PrivatePictureInterceptor(private val access: suspend () -> List<PictureAccess>) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is ErrorResult) return result
        val url = when (val data = chain.request.data) {
            is String -> data
            is coil3.Uri -> data.toString()
            else -> return result
        }
        val private = authenticatedPicture(url, access()) ?: return result
        val retry = chain.request.newBuilder()
            .data(private.url)
            .httpHeaders(NetworkHeaders.Builder().set(private.header, private.value).build())
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .build()
        return chain.withRequest(retry).proceed()
    }
}

private const val GITHUB_RAW_HOST = "raw.githubusercontent.com"
private const val AUTHORIZATION = "Authorization"
