package fr.arthurbrugiere.forgeline.forge.github

import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.composeJsonRequest
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.lastLinkedPage
import fr.arthurbrugiere.forgeline.forge.common.nextLinkedPage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Buffer

/** A REST call to the GitHub API; path segments are encoded one by one. Anonymous when [token] is null. */
internal suspend fun HttpClient.gitHubApi(
    apiBaseUrl: String,
    token: String?,
    vararg segments: String,
    method: HttpMethod = HttpMethod.Get,
    query: Map<String, String> = emptyMap(),
    body: JsonObject? = null,
): HttpResponse = request {
    this.method = method
    url {
        takeFrom(apiBaseUrl)
        appendPathSegments(*segments)
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    gitHubHeaders(token)
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/**
 * One request to GitHub's GraphQL API, answering its `data`. GitHub answers 200 even when it refuses: a refusal is told
 * apart by its errors, FORBIDDEN for a missing permission, NOT_FOUND for what the token can't see, RATE_LIMITED once
 * the hour's points are spent.
 *
 * A request asking after many things at once is [partial]: it keeps what came back, and what couldn't be (a repository
 * deleted since, an organisation that hides its own) is left out instead of failing the whole batch. Spent points fail
 * it all the same.
 */
internal suspend fun HttpClient.gitHubGraphQl(
    apiBaseUrl: String,
    token: String,
    query: String,
    variables: JsonObject = JsonObject(emptyMap()),
    partial: Boolean = false,
): ForgeResult<JsonObject> {
    val response = gitHubApi(
        apiBaseUrl, token, "graphql", method = HttpMethod.Post,
        body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        },
    )
    if (!response.status.isSuccess()) return response.failure()
    val answer = response.body<GraphQlAnswer>()
    val error = answer.errors.firstOrNull { it.type == "RATE_LIMITED" } ?: answer.errors.firstOrNull()
    return when {
        error == null -> ForgeResult.Success(answer.data ?: JsonObject(emptyMap()))
        partial && answer.data != null && error.type != "RATE_LIMITED" -> ForgeResult.Success(answer.data)
        else -> ForgeResult.Failure(
            when (error.type) {
                "RATE_LIMITED" -> ForgeError.RateLimited(response.headers["x-ratelimit-reset"]?.toLongOrNull())
                "FORBIDDEN" -> ForgeError.Http(403, error.message)
                "NOT_FOUND" -> ForgeError.Http(404, error.message)
                else -> ForgeError.Http(422, error.message)
            },
        )
    }
}

/**
 * A GraphQL [operation] written in src/main/graphql, checked against GitHub's schema when the app builds, its answer
 * read as Apollo Kotlin generated it. It fails and keeps what came back as [gitHubGraphQl] does; an answer the schema
 * doesn't allow (a field missing, of another type) is [ForgeError.Unreadable].
 */
internal suspend fun <D : Operation.Data> HttpClient.gitHubOperation(
    apiBaseUrl: String,
    token: String,
    operation: Operation<D>,
    partial: Boolean = false,
): ForgeResult<D> {
    val request = ForgeJson.parseToJsonElement(buildJsonString { operation.composeJsonRequest(this) }).jsonObject
    val response = gitHubApi(apiBaseUrl, token, "graphql", method = HttpMethod.Post, body = request)
    if (!response.status.isSuccess()) return response.failure()
    val answer = operation.parseResponse(Buffer().writeUtf8(response.bodyAsText()).jsonReader())
    val errors = answer.errors.orEmpty()
    val error = errors.firstOrNull { it.type == "RATE_LIMITED" } ?: errors.firstOrNull()
    val data = answer.data
    return when {
        error == null -> data?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
        partial && data != null && error.type != "RATE_LIMITED" -> ForgeResult.Success(data)
        else -> ForgeResult.Failure(
            when (error.type) {
                "RATE_LIMITED" -> ForgeError.RateLimited(response.headers["x-ratelimit-reset"]?.toLongOrNull())
                "FORBIDDEN" -> ForgeError.Http(403, error.message)
                "NOT_FOUND" -> ForgeError.Http(404, error.message)
                else -> ForgeError.Http(422, error.message)
            },
        )
    }
}

/** GitHub names what went wrong in a `type` beside the message, outside GraphQL's own fields. */
private val Error.type: String? get() = nonStandardFields?.get("type") as? String

@Serializable
private data class GraphQlErrorJson(val type: String? = null, val message: String? = null)

@Serializable
private data class GraphQlAnswer(val data: JsonObject? = null, val errors: List<GraphQlErrorJson> = emptyList())

internal fun HttpRequestBuilder.gitHubHeaders(token: String?) {
    if (token != null) bearerAuth(token)
    header("Accept", "application/vnd.github+json")
    header("X-GitHub-Api-Version", GitHubAuthApi.API_VERSION)
}

/** GitHub paginates with `Link: <...page=2>; rel="next"`. */
internal fun HttpResponse.nextPage(): Int? = nextLinkedPage()

/** The last page GitHub announces (`rel="last"`), so the pages in between can be asked all at once. */
internal fun HttpResponse.lastPage(): Int? = lastLinkedPage()
