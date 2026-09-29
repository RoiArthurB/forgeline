package fr.arthurbrugiere.forgeline.forge.forgejo

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/** Real codeberg.org answers captured on 2026-09-29 (some lists trimmed), served by path. */
internal class Codeberg {
    val requests = mutableListOf<HttpRequestData>()

    fun fixture(name: String) = requireNotNull(javaClass.getResource("/codeberg/$name")) { name }.readText()

    fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        forgejoHttpClient(MockEngine { requests += it; handler(it) })

    fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, headers: Map<String, String> = emptyMap()) =
        respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))

    fun MockRequestHandleScope.text(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))

    fun MockRequestHandleScope.status(status: HttpStatusCode) = respond("", status)
}
