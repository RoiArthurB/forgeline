package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Error
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeHttpClient
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
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

/** A client for Bitbucket's API on [engine]: the one every forge's client is (see `forgeHttpClient`). */
fun bitbucketHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)

/** Where Bitbucket Cloud's REST API lives: every call Forgeline makes is under it. */
internal const val BITBUCKET_API = "https://api.bitbucket.org/2.0"

/**
 * The credential Forgeline keeps for a Bitbucket sign-in: an Atlassian account's email and one of its API tokens, sent
 * as HTTP Basic, which is how Bitbucket takes API tokens (an app password, which it retired in 2026, was sent the same
 * way). Kept as the header's value, so that the rest of the app passes it along like any other forge's token.
 */
fun bitbucketBasicCredential(email: String, apiToken: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$email:$apiToken".toByteArray(Charsets.UTF_8))

/**
 * The `Authorization` header for [token]: an email and API token are sent as they are kept ([bitbucketBasicCredential]),
 * and anything else (a repository's, project's or workspace's access token) as a bearer token.
 */
fun bitbucketAuthorization(token: String): String = if (token.startsWith("Basic ")) token else "Bearer $token"

/**
 * A call to Bitbucket's API, [segments] encoded one by one (a file's path is passed as its own segments, split on `/`
 * by the caller). [query] values may repeat a name ([Pair]s), as `state=MERGED&state=DECLINED` does.
 */
internal suspend fun HttpClient.bitbucket(
    token: String?,
    vararg segments: String,
    method: HttpMethod = HttpMethod.Get,
    query: List<Pair<String, String>> = emptyList(),
    body: JsonElement? = null,
    accept: String = "application/json",
): HttpResponse = request { bitbucketRequest(token, segments.toList(), method, query, body, accept) }

/** The same call, at a whole address Bitbucket gave (a page's `next`). */
internal suspend fun HttpClient.bitbucketAt(token: String?, url: String): HttpResponse = request {
    method = HttpMethod.Get
    url { takeFrom(url) }
    header(HttpHeaders.Accept, "application/json")
    if (token != null) header(HttpHeaders.Authorization, bitbucketAuthorization(token))
}

/** Like [bitbucket], for an answer read as it comes (a step's log): run it with `execute`. */
internal suspend fun HttpClient.bitbucketStatement(token: String?, vararg segments: String): HttpStatement = prepareRequest {
    bitbucketRequest(token, segments.toList(), HttpMethod.Get, emptyList(), null, "*/*")
    // Read as it comes (a log runs to tens of thousands of lines): never kept with the answers.
    header(HttpHeaders.CacheControl, "no-store")
}

private fun HttpRequestBuilder.bitbucketRequest(
    token: String?,
    segments: List<String>,
    method: HttpMethod,
    query: List<Pair<String, String>>,
    body: JsonElement?,
    accept: String,
) {
    this.method = method
    url {
        takeFrom(BITBUCKET_API)
        appendPathSegments(segments, encodeSlash = true)
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    header(HttpHeaders.Accept, accept)
    if (token != null) header(HttpHeaders.Authorization, bitbucketAuthorization(token))
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/** The segments of a repository's address in the API: `repositories/<workspace>/<slug>`, then [more]. */
internal fun RepoId.api(vararg more: String): Array<String> = arrayOf("repositories", owner, name, *more)

/** A file's path as segments of its own, so that its slashes stay slashes. */
internal fun pathSegments(path: String): Array<String> = path.split('/').filter { it.isNotEmpty() }.toTypedArray()

internal suspend inline fun <T> HttpResponse.toResult(parse: HttpResponse.() -> T): ForgeResult<T> =
    if (status.isSuccess()) ForgeResult.Success(parse()) else failure()

internal suspend fun HttpResponse.failure(): ForgeResult.Failure {
    val error = when (status) {
        // Bitbucket answers a refused credential with 401, and a repository the reader can't see with 403 or 404.
        HttpStatusCode.Unauthorized -> ForgeError.Unauthorized
        HttpStatusCode.TooManyRequests -> ForgeError.RateLimited(headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { Instant.now().epochSecond + it })
        else -> ForgeError.Http(status.value, runCatching { errorMessage(bodyAsText()) }.getOrNull())
    }
    return ForgeResult.Failure(error)
}

/** What Bitbucket says went wrong: `{"type":"error","error":{"message":…,"detail":…}}`. */
internal fun errorMessage(text: String): String? {
    val error = ForgeJson.decodeFromString<Error>(text).error ?: return null
    return listOfNotNull(error.message?.takeIf { it.isNotBlank() }, error.detail?.takeIf { it.isNotBlank() }).joinToString(": ").ifEmpty { null }
}

/** Bitbucket's times: ISO 8601 with an offset, to the microsecond (`2026-11-13T00:31:12.559888+00:00`), or to the nanosecond with Z. */
internal fun bitbucketInstant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull()
}

/** The page after the one Bitbucket answered, from the `page=` of its `next` address; null on the last. */
internal fun nextPageOf(next: String?): Int? = next?.let { Regex("""[?&]page=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

/** BBQL's string literal: [text] quoted, with its own quotes and backslashes escaped. */
internal fun bbql(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * What a list of pull requests leaves out of each: its description, twice rendered, and its links. A Renovate pull
 * request's description runs to a hundred kilobytes, and a page of thirty to megabytes without this (seen 2026-11-18).
 */
internal val PULL_LIST_FIELDS = "fields" to "-values.summary,-values.rendered,-values.description,-values.links"
