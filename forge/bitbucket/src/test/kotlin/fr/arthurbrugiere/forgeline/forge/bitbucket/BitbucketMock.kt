package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Bitbucket that answers from [routes]: each matches a request by its method and its path under `/2.0` (query left
 * out), and answers what it gives. A request nothing matches is a 404, as Bitbucket's would be. What was asked is
 * kept in [requests], to say what was sent.
 */
class BitbucketMock {
    val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val routes = mutableListOf<Triple<HttpMethod, String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>>()

    fun on(method: HttpMethod, path: String, answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        routes += Triple(method, path, answer)
    }

    fun get(path: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) = on(HttpMethod.Get, path) { json(body, status) }

    fun write(method: HttpMethod, path: String, body: String = "{}", status: HttpStatusCode = HttpStatusCode.OK) = on(method, path) { json(body, status) }

    val http: HttpClient = bitbucketHttpClient(
        MockEngine { request ->
            requests += request
            val path = request.url.encodedPath.removePrefix("/2.0")
            val route = routes.lastOrNull { (method, at, _) -> method == request.method && at == path }
            route?.third?.invoke(this, request) ?: json("""{"type":"error","error":{"message":"Resource not found"}}""", HttpStatusCode.NotFound)
        },
    )

    /** The requests that changed something, as `METHOD path`. */
    val writes: List<String> get() = requests.filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath.removePrefix("/2.0")}" }

    /** The JSON the request [method] [path] sent. */
    fun sent(method: HttpMethod, path: String): JsonObject =
        Json.parseToJsonElement(requests.last { it.method == method && it.url.encodedPath.removePrefix("/2.0") == path }.text).jsonObject
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

val HttpRequestData.text: String get() = (body as? TextContent)?.text ?: (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

fun fixture(name: String): String = BitbucketMock::class.java.getResource("/bitbucket/$name")!!.readText()

fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

val ace = RepoId("atlassian", "atlassian-connect-express", ForgeInstance.Bitbucket)

const val ACE = "/repositories/atlassian/atlassian-connect-express"

const val TOKEN = "Basic YW5hQGV4YW1wbGUub3JnOnRva2Vu"
