package fr.arthurbrugiere.forgeline.forge.github

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom

/** A REST call to the GitHub API; path segments are encoded one by one. Anonymous when [token] is null. */
internal suspend fun HttpClient.gitHubApi(
    apiBaseUrl: String,
    token: String?,
    vararg segments: String,
    method: HttpMethod = HttpMethod.Get,
    query: Map<String, String> = emptyMap(),
): HttpResponse = request {
    this.method = method
    url {
        takeFrom(apiBaseUrl)
        appendPathSegments(*segments)
        query.forEach { (key, value) -> parameters.append(key, value) }
    }
    gitHubHeaders(token)
}

internal fun HttpRequestBuilder.gitHubHeaders(token: String?) {
    if (token != null) bearerAuth(token)
    header("Accept", "application/vnd.github+json")
    header("X-GitHub-Api-Version", GitHubAuthApi.API_VERSION)
}

private val nextPage = Regex("""<[^>]*[?&]page=(\d+)[^>]*>;\s*rel="next"""")

/** GitHub paginates with `Link: <...page=2>; rel="next"`. */
internal fun HttpResponse.nextPage(): Int? = headers["Link"]?.let { nextPage.find(it)?.groupValues?.get(1)?.toIntOrNull() }
