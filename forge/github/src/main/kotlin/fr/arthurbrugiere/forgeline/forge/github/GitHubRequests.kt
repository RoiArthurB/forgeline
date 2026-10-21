package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
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
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
