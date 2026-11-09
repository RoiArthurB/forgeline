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
 * Asks both APIs for their version, at once. Forgejo and Gitea answer `/api/v1/version` to anyone, and the version
 * tells them apart ([forgejoOrGitea]). GitLab keeps `/api/v4/version` for signed-in people but says so in its API's own
 * words (401 with a JSON message), which a web page at that path wouldn't.
 */
class HttpForgeProbe(private val http: HttpClient) : ForgeProbe {
    override suspend fun typeOf(host: String): ForgeType? = coroutineScope {
        val forgejo = async {
            var version: String? = null
            val answered = answers("https://$host/api/v1/version") { status, body ->
                version = VERSION.find(body)?.groupValues?.get(1)
                status.isSuccess() && "\"version\"" in body
            }
            if (answered) forgejoOrGitea(version) else null
        }
        val gitlab = async {
            answers("https://$host/api/v4/version") { status, body ->
                (status.isSuccess() && "\"version\"" in body) || (status == HttpStatusCode.Unauthorized && "\"message\"" in body)
            }
        }
        forgejo.await()?.also { gitlab.cancel() } ?: ForgeType.GITLAB.takeIf { gitlab.await() }
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

private val VERSION = Regex(""""version"\s*:\s*"([^"]*)"""")

/**
 * Forgejo or Gitea, from what `/api/v1/version` says. Forgejo since v7 numbers itself on its own and adds the Gitea it
 * is compatible with (`16.0.0-dev-753-6bcc6da0+gitea-1.22.0`, codeberg.org); its 1.x releases ended with a release
 * number (`1.21.11-1`). Gitea has neither, whatever its number: `1.26.1`, `1.27.0+dev-12-gabcdef` for a build of main,
 * and past 1.x since gitea.com answered `29.0.0+dev-160-g55186ca39b` (2026-11-09).
 */
internal fun forgejoOrGitea(version: String?): ForgeType {
    val v = version.orEmpty()
    val gitea = "+gitea-" !in v && Regex("""^\d+\.\d+\.\d+(\+.*)?$""").matches(v)
    return if (gitea) ForgeType.GITEA else ForgeType.FORGEJO
}
