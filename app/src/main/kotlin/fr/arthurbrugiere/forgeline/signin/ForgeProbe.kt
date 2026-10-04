package fr.arthurbrugiere.forgeline.signin

import fr.arthurbrugiere.forgeline.core.model.ForgeType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Tells what a server at an address runs, so "another server" needs no more than its address. */
fun interface ForgeProbe {
    /** What [host] runs; null when nothing Forgeline knows answers there, or nothing at all. */
    suspend fun typeOf(host: String): ForgeType?
}

/**
 * Asks both APIs for their version, at once. Forgejo (and Gitea) answer `/api/v1/version` to anyone. GitLab keeps
 * `/api/v4/version` for signed-in people but says so in its API's own words (401 with a JSON message), which a web
 * page at that path wouldn't.
 */
class HttpForgeProbe(private val http: HttpClient) : ForgeProbe {
    override suspend fun typeOf(host: String): ForgeType? = coroutineScope {
        val forgejo = async { answers("https://$host/api/v1/version") { status, body -> status.isSuccess() && "\"version\"" in body } }
        val gitlab = async {
            answers("https://$host/api/v4/version") { status, body ->
                (status.isSuccess() && "\"version\"" in body) || (status == HttpStatusCode.Unauthorized && "\"message\"" in body)
            }
        }
        when {
            forgejo.await() -> ForgeType.FORGEJO.also { gitlab.cancel() }
            gitlab.await() -> ForgeType.GITLAB
            else -> null
        }
    }

    private suspend fun answers(url: String, isIt: (HttpStatusCode, String) -> Boolean): Boolean = try {
        val response = http.get(url)
        isIt(response.status, response.bodyAsText().take(2_000))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
