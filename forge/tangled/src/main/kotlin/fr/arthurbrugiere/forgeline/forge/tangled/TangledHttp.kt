package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

fun tangledHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)

/**
 * Tangled's index (bobbin), which answers the AT Protocol queries (XRPC) the app reads: repositories, their git trees and
 * files, stars, profiles, Trending. Everything it serves is public: no call here needs an account.
 */
internal const val TANGLED_API = "https://api.tangled.org"

/** Where handles are turned into DIDs: Bluesky's public AppView, which resolves any AT Protocol handle, DNS or HTTPS. */
internal const val HANDLE_RESOLVER = "https://public.api.bsky.app"

/** An XRPC query: `GET <base>/xrpc/<nsid>?…`. A list parameter is repeated, as XRPC wants (`repos=a&repos=b`). */
internal suspend fun HttpClient.xrpc(nsid: String, vararg params: Pair<String, String?>, base: String = TANGLED_API): HttpResponse = get {
    url {
        takeFrom("$base/xrpc/$nsid")
        params.forEach { (key, value) -> if (value != null) parameters.append(key, value) }
    }
    header(HttpHeaders.Accept, "application/json")
}

internal suspend inline fun <T> HttpResponse.toResult(parse: HttpResponse.() -> T): ForgeResult<T> =
    if (status.isSuccess()) ForgeResult.Success(parse()) else failure()

/**
 * XRPC answers a failure as 400 with `{"error": "RepoNotFound", "message": …}`: an error named `…NotFound` is a 404 to
 * the app, as any forge's missing repository or file is.
 */
internal suspend fun HttpResponse.failure(): ForgeResult.Failure {
    val error = runCatching { ForgeJson.decodeFromString<XrpcError>(bodyAsText()) }.getOrNull()
    return ForgeResult.Failure(
        when {
            status == HttpStatusCode.Unauthorized -> ForgeError.Unauthorized
            status == HttpStatusCode.TooManyRequests -> ForgeError.RateLimited(headers["RateLimit-Reset"]?.toLongOrNull())
            error?.error?.endsWith("NotFound") == true -> ForgeError.Http(HttpStatusCode.NotFound.value, error.message ?: error.error)
            else -> ForgeError.Http(status.value, error?.message ?: error?.error)
        },
    )
}

@Serializable
private data class XrpcError(val error: String? = null, val message: String? = null)

/** AT Protocol times are RFC 3339, with `Z` or an offset. */
internal fun instant(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

internal fun notFound(what: String): ForgeResult.Failure = ForgeResult.Failure(ForgeError.Http(HttpStatusCode.NotFound.value, what))
