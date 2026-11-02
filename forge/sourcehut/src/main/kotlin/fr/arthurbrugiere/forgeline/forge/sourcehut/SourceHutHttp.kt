package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.composeJsonRequest
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.forgeHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import okio.Buffer
import java.time.Instant
import java.time.OffsetDateTime

fun sourceHutHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)

/** git.sr.ht's GraphQL endpoint: repositories, their git objects and refs, and the people who own them. */
internal const val GIT_SRHT_QUERY = "https://git.sr.ht/query"

/**
 * A GraphQL [operation] to SourceHut, its `data` read as Apollo Kotlin generated it from the operation and the schema
 * (src/main/graphql). SourceHut answers nothing without a token (every call is a person's, with a personal access token
 * or OAuth 2.0), so none is [ForgeError.Unauthorized] without asking. An error in the answer fails the whole call:
 * `ERR_NOT_FOUND` (or a git "not found") is a 404, as any forge's missing repository or file is.
 */
internal suspend fun <D : Operation.Data> HttpClient.srht(token: String?, operation: Operation<D>, endpoint: String = GIT_SRHT_QUERY): ForgeResult<D> {
    if (token.isNullOrBlank()) return ForgeResult.Failure(ForgeError.Unauthorized)
    val response = post(endpoint) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/json")
        contentType(ContentType.Application.Json)
        setBody(buildJsonString { operation.composeJsonRequest(this) })
    }
    val answer = operation.parseResponse(Buffer().writeUtf8(response.bodyAsText()).jsonReader())
    val error = answer.errors?.firstOrNull()
    return when {
        response.status == HttpStatusCode.Unauthorized || error?.code == "ERR_UNAUTHORIZED" -> ForgeResult.Failure(ForgeError.Unauthorized)
        response.status == HttpStatusCode.TooManyRequests -> ForgeResult.Failure(ForgeError.RateLimited(response.headers["Retry-After"]?.toLongOrNull()?.let { Instant.now().epochSecond + it }))
        error != null -> ForgeResult.Failure(error.toForgeError(response.status))
        !response.status.isSuccess() -> ForgeResult.Failure(ForgeError.Http(response.status.value, null))
        // An answer its schema doesn't allow (a field missing, of another type) is read as nothing.
        else -> answer.data?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
    }
}

/** A blob's whole content, from the address its `content` field gives: it asks for the same token as the query did. */
internal suspend fun HttpClient.srhtContent(token: String?, url: String): ForgeResult<String> {
    if (token.isNullOrBlank()) return ForgeResult.Failure(ForgeError.Unauthorized)
    val response = get(url) { header(HttpHeaders.Authorization, "Bearer $token") }
    return when {
        response.status.isSuccess() -> ForgeResult.Success(response.bodyAsText())
        response.status == HttpStatusCode.Unauthorized -> ForgeResult.Failure(ForgeError.Unauthorized)
        else -> ForgeResult.Failure(ForgeError.Http(response.status.value, null))
    }
}

private val Error.code: String? get() = extensions?.get("code") as? String

private fun Error.toForgeError(status: HttpStatusCode): ForgeError = when {
    code == "ERR_NOT_FOUND" || message.contains("not found", ignoreCase = true) || message == "no such object" ->
        ForgeError.Http(HttpStatusCode.NotFound.value, message)
    code == "ERR_ACCESS_DENIED" -> ForgeError.Http(HttpStatusCode.Forbidden.value, message)
    // A repository renamed or moved: its old address no longer names it.
    code == "ERR_REDIRECT" -> ForgeError.Http(HttpStatusCode.NotFound.value, message)
    else -> ForgeError.Http(if (status.isSuccess()) HttpStatusCode.BadRequest.value else status.value, message)
}

/** SourceHut's times are RFC 3339 (`2026-11-02T10:00:00Z`). */
internal fun instant(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

internal fun notFound(what: String): ForgeResult.Failure = ForgeResult.Failure(ForgeError.Http(HttpStatusCode.NotFound.value, what))

/** A SourceHut username as its GraphQL API wants it, without the `~` its addresses carry. */
internal val String.username: String get() = removePrefix("~")

/** A person as SourceHut's addresses name them: `~name`. */
internal val String.canonical: String get() = if (startsWith("~")) this else "~$this"

/** [this], then [next] with its value. */
internal inline fun <A, B> ForgeResult<A>.then(next: (A) -> ForgeResult<B>): ForgeResult<B> = when (this) {
    is ForgeResult.Failure -> this
    is ForgeResult.Success -> next(value)
}
