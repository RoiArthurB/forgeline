package fr.arthurbrugiere.forgeline.forge.gitlab

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpStatement
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeHttpClient
import fr.arthurbrugiere.forgeline.forge.common.nextLinkedPage
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.timeout
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.OffsetDateTime

fun gitlabHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)

/**
 * A call to a GitLab instance's API (`https://host/api/v4/...`); path segments are encoded one by one, slashes too, so
 * a project's path (`gitlab-org/gitlab`) or a file's is passed as it is.
 */
internal suspend fun HttpClient.gitlabApi(
    forge: ForgeInstance,
    token: String?,
    vararg segments: String,
    method: HttpMethod = HttpMethod.Get,
    query: Map<String, String> = emptyMap(),
    body: JsonObject? = null,
    patienceMillis: Long? = null,
): HttpResponse = request { gitlab(forge, token, segments, method, query, body, patienceMillis) }

/** Like [gitlabApi], for an answer read as it comes (a job's trace): run it with `execute`. */
internal suspend fun HttpClient.gitlabStatement(
    forge: ForgeInstance,
    token: String?,
    vararg segments: String,
    more: HttpRequestBuilder.() -> Unit = {},
): HttpStatement = prepareRequest {
    gitlab(forge, token, segments, HttpMethod.Get, emptyMap(), null, null)
    more()
}

private fun HttpRequestBuilder.gitlab(
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
        takeFrom("${forge.webUrl}/api/v4")
        appendPathSegments(segments.toList(), encodeSlash = true)
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    header(HttpHeaders.Accept, "application/json")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

internal suspend inline fun <T> HttpResponse.toResult(parse: HttpResponse.() -> T): ForgeResult<T> =
    if (status.isSuccess()) ForgeResult.Success(parse()) else failure()

internal suspend fun HttpResponse.failure(): ForgeResult.Failure {
    val exhausted = headers["RateLimit-Remaining"] == "0" || status == HttpStatusCode.TooManyRequests
    val error = when {
        status == HttpStatusCode.Unauthorized -> ForgeError.Unauthorized
        exhausted -> ForgeError.RateLimited(
            headers["Retry-After"]?.toLongOrNull()?.let { Instant.now().epochSecond + it }
                ?: headers["RateLimit-Reset"]?.toLongOrNull()
        )
        else -> ForgeError.Http(status.value, runCatching { errorMessage(ForgeJson.parseToJsonElement(bodyAsText())) }.getOrNull())
    }
    return ForgeResult.Failure(error)
}

/**
 * GitLab says what went wrong under `message` (a line, a list of lines, or lines by field) or under `error`.
 * Regression: the whole answer was the message, so the app showed `{"message":"405 Method Not Allowed"}`.
 */
private fun errorMessage(answer: JsonElement): String? {
    val fields = answer as? JsonObject ?: return null
    return lines(fields["message"])?.takeIf { it.isNotBlank() } ?: (fields["error"] as? JsonPrimitive)?.contentOrNull
}

private fun lines(value: JsonElement?): String? = when (value) {
    is JsonPrimitive -> value.contentOrNull
    is JsonArray -> value.mapNotNull { lines(it) }.joinToString(", ")
    // By field: `{"title":["can't be blank"]}` reads "title can't be blank"; `base` is about the whole thing.
    is JsonObject -> value.entries.mapNotNull { (field, said) -> lines(said)?.let { if (field == "base") it else "$field $it" } }.joinToString(", ")
    else -> null
}

/** Parses ISO timestamps with offset or Z. */
internal fun gitlabInstant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(it) }.getOrNull()
}

internal fun HttpResponse.nextPage(): Int? =
    headers["X-Next-Page"]?.takeIf { it.isNotBlank() }?.toIntOrNull()
        ?: nextLinkedPage()

/** The last page there is, which GitLab says with every paged answer (`X-Total-Pages`). */
internal fun HttpResponse.lastPage(): Int? = headers["X-Total-Pages"]?.toIntOrNull()

internal fun HttpResponse.totalCount(): Int? =
    headers["X-Total"]?.toIntOrNull() ?: headers["X-Total-Count"]?.toIntOrNull()
