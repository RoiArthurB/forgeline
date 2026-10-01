package fr.arthurbrugiere.forgeline.forge.forgejo

import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.HttpTimeout
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime

internal val ForgejoJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun forgejoHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(ForgejoJson) }
    // Lets a single slow call ask for more time (see forgejoApi's patienceMillis); others keep the engine's limits.
    install(HttpTimeout)
    defaultRequest {
        header(HttpHeaders.UserAgent, "Forgeline (+https://github.com/RoiArthurB/forgeline)")
    }
}

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
): HttpResponse = request {
    this.method = method
    if (patienceMillis != null) timeout { socketTimeoutMillis = patienceMillis }
    url {
        takeFrom("${forge.webUrl}/api/v1")
        appendPathSegments(*segments)
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    header(HttpHeaders.Accept, "application/json")
    if (token != null) header(HttpHeaders.Authorization, "token $token")
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

internal suspend inline fun <T> forgejoCall(block: () -> ForgeResult<T>): ForgeResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ForgeResult.Failure(ForgeError.Network)
}

internal suspend inline fun <T> HttpResponse.toResult(parse: HttpResponse.() -> T): ForgeResult<T> =
    if (status.isSuccess()) ForgeResult.Success(parse()) else failure()

internal suspend fun HttpResponse.failure(): ForgeResult.Failure {
    // Forgejo announces its limit in RateLimit headers (checked on Codeberg, 2026-09-29).
    val exhausted = headers["RateLimit-Remaining"] == "0" || status == HttpStatusCode.TooManyRequests
    val error = when {
        status == HttpStatusCode.Unauthorized -> ForgeError.Unauthorized
        exhausted -> ForgeError.RateLimited(headers["RateLimit-Reset"]?.toLongOrNull()?.let { Instant.now().epochSecond + it })
        else -> ForgeError.Http(status.value, runCatching { ForgejoJson.decodeFromString<ErrorResponse>(bodyAsText()).message }.getOrNull())
    }
    return ForgeResult.Failure(error)
}

@Serializable
private data class ErrorResponse(val message: String? = null)

/** Forgejo writes times with an offset (`2026-09-29T15:09:06+02:00`). */
internal fun instant(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/** Forgejo pages with `Link: <...page=2...>; rel="next"`, like GitHub. */
private val nextPage = Regex("""<[^>]*[?&]page=(\d+)[^>]*>;\s*rel="next"""")

internal fun HttpResponse.nextPage(): Int? = headers[HttpHeaders.Link]?.let { nextPage.find(it)?.groupValues?.get(1)?.toIntOrNull() }

internal fun HttpResponse.totalCount(): Int? = headers["X-Total-Count"]?.toIntOrNull()
