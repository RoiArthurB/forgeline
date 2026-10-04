package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URLEncoder
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime

internal val GitLabJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun gitlabHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(GitLabJson) }
    install(HttpTimeout)
    defaultRequest {
        header(HttpHeaders.UserAgent, "Forgeline (+https://github.com/RoiArthurB/forgeline)")
    }
}

/** URL-encodes a path (e.g. project path with namespace "gitlab-org/gitlab" -> "gitlab-org%2Fgitlab"). */
internal fun encodePath(path: String): String =
    URLEncoder.encode(path, "UTF-8").replace("+", "%20")

/** A call to a GitLab instance's API (`https://host/api/v4/...`). */
internal suspend fun HttpClient.gitlabApi(
    forge: ForgeInstance,
    token: String?,
    vararg segments: String,
    method: HttpMethod = HttpMethod.Get,
    query: Map<String, String> = emptyMap(),
    body: JsonObject? = null,
    patienceMillis: Long? = null,
): HttpResponse = request {
    this.method = method
    if (patienceMillis != null) timeout { socketTimeoutMillis = patienceMillis }
    url {
        val combinedSegments = segments.joinToString("/")
        takeFrom("${forge.webUrl}/api/v4" + (if (combinedSegments.isNotEmpty()) "/$combinedSegments" else ""))
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    header(HttpHeaders.Accept, "application/json")
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

internal suspend inline fun <T> gitlabCall(block: () -> ForgeResult<T>): ForgeResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ForgeResult.Failure(ForgeError.Network)
} catch (e: Exception) {
    if (e.isUnreadableAnswer()) ForgeResult.Failure(ForgeError.Unreadable) else throw e
}

@PublishedApi
internal fun Exception.isUnreadableAnswer(): Boolean =
    this is ContentConvertException || this is NoTransformationFoundException ||
        this is IllegalArgumentException || this is DateTimeException || this is NoSuchElementException

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
        else -> {
            val body = runCatching { bodyAsText() }.getOrNull()
            ForgeError.Http(status.value, body)
        }
    }
    return ForgeResult.Failure(error)
}

/** Parses ISO timestamps with offset or Z. */
internal fun gitlabInstant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(it) }.getOrNull()
}

private val nextPageRegex = Regex("""<[^>]*[?&]page=(\d+)[^>]*>;\s*rel="next"""")

internal fun HttpResponse.nextPage(): Int? =
    headers["X-Next-Page"]?.takeIf { it.isNotBlank() }?.toIntOrNull()
        ?: headers[HttpHeaders.Link]?.let { nextPageRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** The last page there is, which GitLab says with every paged answer (`X-Total-Pages`). */
internal fun HttpResponse.lastPage(): Int? = headers["X-Total-Pages"]?.toIntOrNull()

internal fun HttpResponse.totalCount(): Int? =
    headers["X-Total"]?.toIntOrNull() ?: headers["X-Total-Count"]?.toIntOrNull()
