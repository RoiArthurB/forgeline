package fr.arthurbrugiere.forgeline.forge.tangled

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * A fake Tangled: each XRPC method answers what [answers] holds for it, shaped as Tangled's lexicons describe (read
 * 2026-11-02; the session that wrote these couldn't reach api.tangled.org, so they aren't captured answers: the
 * nightly TangledLiveTest checks the real ones). An unknown method answers XRPC's own 400.
 */
internal class FakeTangled(
    private val answers: Map<String, (HttpRequestData) -> String?>,
    /** A method that fails with an HTTP status of its own instead: a refused sign-in's 401. */
    private val status: (String) -> Int? = { null },
) {
    val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    val client = tangledHttpClient(
        MockEngine { request ->
            requests += request
            val method = request.key
            status(method)?.let { code -> return@MockEngine json("""{"error":"AuthenticationRequired","message":"Invalid identifier or password"}""", HttpStatusCode.fromValue(code)) }
            val answer = answers[method]?.invoke(request)
            if (answer == null) json("""{"error":"RepoNotFound","message":"repository not found"}""", HttpStatusCode.BadRequest) else respondFor(answer)
        },
    )

    /** The XRPC clients on this fake. */
    val xrpc by lazy { TangledXrpc(client) }

    /** The methods asked, in order, with their parameters as `name=value` joined by `&`. */
    val asked: List<String>
        get() = requests.map { r -> r.key + "?" + r.url.parameters.entries().sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" } }

    /** The requests made to [method], as [asked] names it. */
    fun to(method: String): List<HttpRequestData> = requests.filter { it.key == method }

    private fun MockRequestHandleScope.respondFor(answer: String): HttpResponseData =
        if (answer.trimStart().startsWith("{")) json(answer) else respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

/** An XRPC method by its name; anything else (a DID document) by host and path, `plc.directory/did:plc:…`. */
private val HttpRequestData.key: String
    get() = if ("/xrpc/" in url.encodedPath) url.encodedPath.substringAfter("/xrpc/") else url.host + url.encodedPath

/** What a request sent, as text: a JSON body. */
internal val HttpRequestData.text: String
    get() = (body as? io.ktor.http.content.TextContent)?.text ?: (body as? io.ktor.http.content.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()

internal const val OWNER_DID = "did:plc:wshs7t2adsemcrrd4snkeqli"
internal const val REPO_DID = "did:plc:j5hmlfdrwkvtxm7cjmu7j2is"

/** A git signature, as `org.tangled.git.defs#signature` has it. */
internal const val SIGNATURE = """{"name":"Akshay","email":"akshay@example.com","when":"2026-10-30T09:00:00Z"}"""

internal val resolved: (HttpRequestData) -> String? = { """{"did":"$OWNER_DID"}""" }

internal val coreRepo: (HttpRequestData) -> String? = {
    """
    {"uri":"at://$OWNER_DID/sh.tangled.repo/3lmrm7gu5ky22","cid":"bafyrei","knot":"knot1.tangled.sh",
     "value":{"${'$'}type":"sh.tangled.repo","name":"core","knot":"knot1.tangled.sh","description":"Monorepo for Tangled",
              "website":"https://tangled.org","topics":["atproto","git"],"repoDid":"$REPO_DID","createdAt":"2025-03-01T10:00:00.000Z"},
     "git":{"defaultBranch":"master","isEmpty":false,"languages":[{"name":"Go","size":900000},{"name":"HTML","size":100000}]}}
    """.trimIndent()
}
