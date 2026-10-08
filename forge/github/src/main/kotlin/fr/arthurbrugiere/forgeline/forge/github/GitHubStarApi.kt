package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubStarApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : StarApi {

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> = gitHubCall {
        val url = "$apiBaseUrl/user/starred/${repo.owner}/${repo.name}"
        val response = if (starred) {
            httpClient.put(url) { authenticated(token) }
        } else {
            httpClient.delete(url) { authenticated(token) }
        }
        response.toResult { }
    }

    /** GitHub answers 404 for a repository the account has no subscription to: that is a no, not a failure. */
    override suspend fun isWatching(token: String, repo: RepoId): ForgeResult<Boolean> = gitHubCall {
        val response = httpClient.get("$apiBaseUrl/repos/${repo.owner}/${repo.name}/subscription") { authenticated(token) }
        if (response.status == HttpStatusCode.NotFound) ForgeResult.Success(false) else response.toResult { body<SubscriptionJson>().subscribed }
    }

    override suspend fun setWatching(token: String, repo: RepoId, watching: Boolean): ForgeResult<Unit> = gitHubCall {
        val url = "$apiBaseUrl/repos/${repo.owner}/${repo.name}/subscription"
        val response = if (watching) {
            httpClient.put(url) {
                authenticated(token)
                contentType(ContentType.Application.Json)
                setBody(SubscriptionJson(subscribed = true))
            }
        } else {
            httpClient.delete(url) { authenticated(token) }
        }
        response.toResult { }
    }

    override suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> = gitHubCall {
        httpClient.post("$apiBaseUrl/repos/${repo.owner}/${repo.name}/forks") { authenticated(token) }
            .toResult { body<ForkJson>().let { RepoId(it.owner.login, it.name, repo.forge) } }
    }

    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> {
        if (repos.isEmpty()) return ForgeResult.Success(emptyMap())
        return gitHubCall {
            val response = httpClient.post("$apiBaseUrl/graphql") {
                authenticated(token)
                contentType(ContentType.Application.Json)
                setBody(starredStatusQuery(repos))
            }
            response.toResult {
                val data = body<GraphQlResponse>().data
                repos.withIndex().mapNotNull { (index, repo) ->
                    val node = data?.get("r$index")?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                    repo to node.jsonObject.getValue("viewerHasStarred").jsonPrimitive.boolean
                }.toMap()
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticated(token: String) {
        bearerAuth(token)
        header("X-GitHub-Api-Version", GitHubAuthApi.API_VERSION)
    }

    private fun starredStatusQuery(repos: List<RepoId>): GraphQlRequest {
        val params = repos.indices.joinToString(", ") { "\$o$it: String!, \$n$it: String!" }
        val fields = repos.indices.joinToString(" ") { "r$it: repository(owner: \$o$it, name: \$n$it) { viewerHasStarred }" }
        val variables = repos.withIndex().flatMap { (i, repo) -> listOf("o$i" to repo.owner, "n$i" to repo.name) }.toMap()
        return GraphQlRequest(query = "query StarredStatus($params) { $fields }", variables = variables)
    }
}

@Serializable
private data class SubscriptionJson(val subscribed: Boolean = false)

@Serializable
private data class ForkJson(val name: String, val owner: ForkOwnerJson)

@Serializable
private data class ForkOwnerJson(val login: String)

@Serializable
private data class GraphQlRequest(val query: String, val variables: Map<String, String>)

@Serializable
private data class GraphQlResponse(val data: JsonObject? = null, val errors: List<JsonElement>? = null)
