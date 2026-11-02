package fr.arthurbrugiere.forgeline.forge.sourcehut

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A fake git.sr.ht: each query is answered by the first of [answers] whose key it holds (a field it asks for), with the
 * request's variables, shaped as git.sr.ht's schema and its resolvers (read 2026-11-02 from its source) answer. Content
 * addresses (`/query/blob/…`) answer [blobs]. Every request's token is kept in [tokens].
 */
internal class FakeSourceHut(
    private val answers: List<Pair<String, (Request) -> String>>,
    private val blobs: Map<String, String> = emptyMap(),
) {
    data class Request(val query: String, val variables: JsonObject) {
        fun string(name: String): String? = variables[name]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
    }

    val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
    val tokens = java.util.concurrent.CopyOnWriteArrayList<String?>()

    val client = sourceHutHttpClient(
        MockEngine { request ->
            tokens += request.headers[HttpHeaders.Authorization]
            if (request.url.encodedPath.startsWith("/query/blob/")) {
                return@MockEngine respond(blobs[request.url.toString()] ?: "", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
            }
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val asked = Request(body["query"]!!.jsonPrimitive.content, body["variables"]?.jsonObject ?: JsonObject(emptyMap()))
            requests += asked
            val answer = answers.firstOrNull { (key, _) -> key in asked.query }?.second?.invoke(asked)
                ?: """{"errors":[{"message":"Resource not found","extensions":{"code":"ERR_NOT_FOUND"}}],"data":null}"""
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
}

internal const val TOKEN = "srht-token"

/** `{ user { repository { … } } }`, around [repository]'s JSON. */
internal fun inRepository(repository: String) = """{"data":{"user":{"repository":$repository}}}"""
