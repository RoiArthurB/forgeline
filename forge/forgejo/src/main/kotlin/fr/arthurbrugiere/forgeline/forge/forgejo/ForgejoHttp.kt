package fr.arthurbrugiere.forgeline.forge.forgejo

import io.ktor.client.plugins.timeout
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeHttpClient
import fr.arthurbrugiere.forgeline.forge.common.nextLinkedPage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime

fun forgejoHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)

/** A call to a Forgejo instance's API (`https://host/api/v1/...`); path segments are encoded one by one. */
internal suspend fun HttpClient.forgejoApi(
    forge: ForgeInstance,
    token: String?,
    vararg segments: String,
    method: HttpMethod = HttpMethod.Get,
    query: Map<String, String> = emptyMap(),
    body: JsonObject? = null,
    /** How long this call may go without a byte from the server; null keeps the engine's limit (10 s). */
    patienceMillis: Long? = null,
): HttpResponse = request { forgejo(forge, token, segments, method, query, body, patienceMillis) }

/** Like [forgejoApi], for an answer read as it comes (a log): run it with `execute`. */
internal suspend fun HttpClient.forgejoStatement(
    forge: ForgeInstance,
    token: String?,
    vararg segments: String,
    more: HttpRequestBuilder.() -> Unit = {},
): HttpStatement = prepareRequest {
    forgejo(forge, token, segments, HttpMethod.Get, emptyMap(), null, null)
    // Read as it comes (a log runs to tens of thousands of lines): never kept with the answers.
    header(HttpHeaders.CacheControl, "no-store")
    more()
}

private fun HttpRequestBuilder.forgejo(
    forge: ForgeInstance,
    token: String?,
    segments: Array<out String>,
    method: HttpMethod,
    query: Map<String, String>,
    body: JsonObject?,
    patienceMillis: Long?,
) {
    this.method = method
    if (patienceMillis != null) timeout { socketTimeoutMillis = patienceMillis }
    url {
        takeFrom("${forge.webUrl}/api/v1")
        // A segment stays one even with a slash in it (a branch like v16.0/forgejo); a path comes split by its caller.
        appendPathSegments(segments.toList(), encodeSlash = true)
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    header(HttpHeaders.Accept, "application/json")
    if (token != null) header(HttpHeaders.Authorization, "token $token")
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

internal suspend inline fun <T> HttpResponse.toResult(parse: HttpResponse.() -> T): ForgeResult<T> =
    if (status.isSuccess()) ForgeResult.Success(parse()) else failure()

internal suspend fun HttpResponse.failure(): ForgeResult.Failure {
    // Forgejo announces its limit in RateLimit headers (checked on Codeberg, 2026-09-29).
    val exhausted = headers["RateLimit-Remaining"] == "0" || status == HttpStatusCode.TooManyRequests
    val error = when {
        status == HttpStatusCode.Unauthorized -> ForgeError.Unauthorized
        exhausted -> ForgeError.RateLimited(headers["RateLimit-Reset"]?.toLongOrNull()?.let { Instant.now().epochSecond + it })
        else -> ForgeError.Http(status.value, runCatching { ForgeJson.decodeFromString<ErrorResponse>(bodyAsText()).message }.getOrNull())
    }
    return ForgeResult.Failure(error)
}

@Serializable
private data class ErrorResponse(val message: String? = null)

/** Forgejo writes times with an offset (`2026-09-29T15:09:06+02:00`). */
internal fun instant(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/** Forgejo pages with `Link: <...page=2...>; rel="next"`, like GitHub. */
internal fun HttpResponse.nextPage(): Int? = nextLinkedPage()

internal fun HttpResponse.totalCount(): Int? = headers["X-Total-Count"]?.toIntOrNull()
