package fr.arthurbrugiere.forgeline.forge.sourcehut

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

fun sourceHutHttpClient(engine: HttpClientEngine): HttpClient = forgeHttpClient(engine)

/** git.sr.ht's GraphQL endpoint: repositories, their git objects and refs, and the people who own them. */
internal const val GIT_SRHT_QUERY = "https://git.sr.ht/query"

/**
 * A GraphQL [query] to SourceHut with [variables], its `data` read as [T]. SourceHut answers nothing without a token
 * (every call is a person's, with a personal access token or OAuth 2.0), so none is [ForgeError.Unauthorized] without
 * asking. An error in the answer fails the whole call: `ERR_NOT_FOUND` (or a git "not found") is a 404, as any forge's
 * missing repository or file is.
 */
internal suspend fun <T> HttpClient.srht(
    token: String?,
    query: String,
    variables: Map<String, Any?>,
    deserializer: KSerializer<T>,
    endpoint: String = GIT_SRHT_QUERY,
): ForgeResult<T> {
    if (token.isNullOrBlank()) return ForgeResult.Failure(ForgeError.Unauthorized)
    val response = post(endpoint) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/json")
        contentType(ContentType.Application.Json)
        setBody(
            buildJsonObject {
                put("query", query)
                put("variables", JsonObject(variables.mapValues { (_, value) -> value.toJson() }))
            },
        )
    }
    val text = response.bodyAsText()
    val answer = runCatching { ForgeJson.decodeFromString(GraphQlAnswer.serializer(), text) }.getOrNull()
    val error = answer?.errors?.firstOrNull()
    return when {
        response.status == HttpStatusCode.Unauthorized || error?.code == "ERR_UNAUTHORIZED" -> ForgeResult.Failure(ForgeError.Unauthorized)
        response.status == HttpStatusCode.TooManyRequests -> ForgeResult.Failure(ForgeError.RateLimited(response.headers["Retry-After"]?.toLongOrNull()?.let { Instant.now().epochSecond + it }))
        error != null -> ForgeResult.Failure(error.toForgeError(response.status))
        !response.status.isSuccess() -> ForgeResult.Failure(ForgeError.Http(response.status.value, null))
        answer?.data == null || answer.data is JsonNull -> ForgeResult.Failure(ForgeError.Unreadable)
        else -> ForgeResult.Success(ForgeJson.decodeFromJsonElement(deserializer, answer.data))
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

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is JsonElement -> this
    else -> error("Not a GraphQL variable: $this")
}

@Serializable
private data class GraphQlAnswer(val data: JsonElement? = null, val errors: List<GraphQlError>? = null)

@Serializable
private data class GraphQlError(val message: String? = null, val extensions: JsonObject? = null) {
    val code: String? get() = extensions?.get("code")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun toForgeError(status: HttpStatusCode): ForgeError = when {
        code == "ERR_NOT_FOUND" || message?.contains("not found", ignoreCase = true) == true || message == "no such object" ->
            ForgeError.Http(HttpStatusCode.NotFound.value, message)
        code == "ERR_ACCESS_DENIED" -> ForgeError.Http(HttpStatusCode.Forbidden.value, message)
        // A repository renamed or moved: its old address no longer names it.
        code == "ERR_REDIRECT" -> ForgeError.Http(HttpStatusCode.NotFound.value, message)
        else -> ForgeError.Http(if (status.isSuccess()) HttpStatusCode.BadRequest.value else status.value, message)
    }
}

/** SourceHut's times are RFC 3339 (`2026-11-02T10:00:00Z`). */
internal fun instant(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

internal fun notFound(what: String): ForgeResult.Failure = ForgeResult.Failure(ForgeError.Http(HttpStatusCode.NotFound.value, what))

/** A SourceHut username as its GraphQL API wants it, without the `~` its addresses carry. */
internal val String.username: String get() = removePrefix("~")

/** A person as SourceHut's addresses name them: `~name`. */
internal val String.canonical: String get() = if (startsWith("~")) this else "~$this"
