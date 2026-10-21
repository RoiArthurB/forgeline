package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitHubStarApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : StarApi {

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "user", "starred", repo.owner, repo.name, method = if (starred) HttpMethod.Put else HttpMethod.Delete)
            .toResult { }
    }

    /** GitHub answers 404 for a repository the account has no subscription to: that is a no, not a failure. */
    override suspend fun isWatching(token: String, repo: RepoId): ForgeResult<Boolean> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "subscription")
        if (response.status == HttpStatusCode.NotFound) ForgeResult.Success(false) else response.toResult { body<SubscriptionJson>().subscribed }
    }

    override suspend fun setWatching(token: String, repo: RepoId, watching: Boolean): ForgeResult<Unit> = forgeCall {
        val response = if (watching) {
            httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "subscription", method = HttpMethod.Put, body = buildJsonObject { put("subscribed", true) })
        } else {
            httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "subscription", method = HttpMethod.Delete)
        }
        response.toResult { }
    }

    override suspend fun fork(token: String, repo: RepoId): ForgeResult<RepoId> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "forks", method = HttpMethod.Post)
            .toResult { body<ForkJson>().let { RepoId(it.owner.login, it.name, repo.forge) } }
    }

    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> {
        if (repos.isEmpty()) return ForgeResult.Success(emptyMap())
        val params = repos.indices.joinToString(", ") { "\$o$it: String!, \$n$it: String!" }
        val fields = repos.indices.joinToString(" ") { "r$it: repository(owner: \$o$it, name: \$n$it) { viewerHasStarred }" }
        val variables = buildJsonObject {
            repos.forEachIndexed { i, repo ->
                put("o$i", repo.owner)
                put("n$i", repo.name)
            }
        }
        // A repository deleted since is left out; the others are still known.
        return forgeCall {
            httpClient.gitHubGraphQl(apiBaseUrl, token, "query StarredStatus($params) { $fields }", variables, partial = true).map { data ->
                repos.withIndex().mapNotNull { (index, repo) ->
                    val node = data["r$index"]?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                    repo to node.jsonObject.getValue("viewerHasStarred").jsonPrimitive.boolean
                }.toMap()
            }
        }
    }
}

@Serializable
private data class SubscriptionJson(val subscribed: Boolean = false)

@Serializable
private data class ForkJson(val name: String, val owner: ForkOwnerJson)

@Serializable
private data class ForkOwnerJson(val login: String)

