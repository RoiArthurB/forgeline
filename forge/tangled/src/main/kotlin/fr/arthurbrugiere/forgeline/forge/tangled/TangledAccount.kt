package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Clock
import java.util.Base64

/**
 * A person's own AT Protocol server (their PDS), where what they do on Tangled is kept as records: a star, a follow.
 * Signing in is an AT Protocol session made with the account's handle and an app password
 * (`com.atproto.server.createSession`): its access token is what the app keeps as the account's token, renewed with its
 * refresh token (`refreshSession`) as any forge's OAuth token is. Who the token is for, and so which server to write
 * to, is read from the token itself: an AT Protocol access token is a JWT whose `sub` is the account's DID.
 */
class TangledAccount(
    private val httpClient: HttpClient,
    private val identity: TangledIdentity,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Signs in as [identifier] (a handle, `@` or not, or a DID, or the account's email) with [password], an app password. */
    suspend fun createSession(identifier: String, password: String): ForgeResult<OAuthTokens> = forgeCall {
        val name = identifier.trim().removePrefix("@")
        // An email says nothing of where the account lives: it is asked of Bluesky's, which most accounts are on.
        val server = if ('@' in name) ForgeResult.Success(ENTRYWAY) else authServerOf(name)
        val base = when (server) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        httpClient.post("$base/xrpc/com.atproto.server.createSession") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json")
            setBody(CreateSessionJson(name, password))
        }.toResult { body<SessionJson>().toTokens() }
    }

    /** A new session for the one [refreshToken] belongs to; AT Protocol servers hand a new refresh token each time. */
    suspend fun refreshSession(refreshToken: String): ForgeResult<OAuthTokens> = forgeCall {
        val did = subjectOf(refreshToken) ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val base = when (val server = authServerOf(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        httpClient.post("$base/xrpc/com.atproto.server.refreshSession") {
            bearerAuth(refreshToken)
            header(HttpHeaders.Accept, "application/json")
        }.toResult { body<SessionJson>().toTokens() }
    }

    /** Who [token] signs in: their handle, asked of their own server, which says at once whether it still takes the token. */
    suspend fun user(token: String): ForgeResult<ForgeUser> = forgeCall {
        val did = subjectOf(token) ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val pds = when (val server = identity.pds(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        httpClient.get("$pds/xrpc/com.atproto.server.getSession") {
            bearerAuth(token)
            header(HttpHeaders.Accept, "application/json")
        }.toResult {
            val session = body<SessionJson>()
            identity.remember(session.handle, session.did)
            ForgeUser(session.handle, null, null)
        }
    }

    /** The DID [token] is for. */
    fun did(token: String): ForgeResult<String> = subjectOf(token)?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unauthorized)

    /** Creates a record in the signed-in person's [collection], under a key their server picks; answers its AT URI. */
    suspend fun createRecord(token: String, collection: String, record: JsonObject): ForgeResult<String> =
        write(token, "com.atproto.repo.createRecord") { did -> WriteJson(did, collection, record = record) }

    /** Writes the record [rkey] of [collection], replacing what was there. */
    suspend fun putRecord(token: String, collection: String, rkey: String, record: JsonObject): ForgeResult<String> =
        write(token, "com.atproto.repo.putRecord") { did -> WriteJson(did, collection, rkey, record) }

    /** Deletes the record at [uri] (`at://did/collection/rkey`), which must be the signed-in person's; gone already is done. */
    suspend fun deleteRecord(token: String, uri: String): ForgeResult<Unit> {
        val parts = uri.removePrefix("at://").split('/')
        if (parts.size != 3) return ForgeResult.Failure(ForgeError.Unreadable)
        val (_, collection, rkey) = parts
        return when (val result = write(token, "com.atproto.repo.deleteRecord") { did -> WriteJson(did, collection, rkey) }) {
            is ForgeResult.Failure -> result
            is ForgeResult.Success -> ForgeResult.Success(Unit)
        }
    }

    /** The signed-in person's records of [collection], a page at a time, as their own server keeps them. */
    internal suspend fun listRecords(did: String, collection: String, cursor: String?): ForgeResult<RecordListJson<JsonObject>> = forgeCall {
        val pds = when (val server = identity.pds(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        httpClient.xrpc("com.atproto.repo.listRecords", "repo" to did, "collection" to collection, "limit" to "100", "cursor" to cursor, base = pds)
            .toResult { body<ListRecordsJson>().let { RecordListJson(it.records, it.cursor) } }
    }

    private suspend fun write(token: String, nsid: String, body: (did: String) -> WriteJson): ForgeResult<String> = forgeCall {
        val did = subjectOf(token) ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val pds = when (val server = identity.pds(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        val response: HttpResponse = httpClient.post {
            url { takeFrom("$pds/xrpc/$nsid") }
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json")
            setBody(body(did))
        }
        if (!response.status.isSuccess()) return@forgeCall response.failure()
        // deleteRecord answers no URI (an empty object, or nothing on older servers).
        ForgeResult.Success(runCatching { response.body<WrittenJson>().uri }.getOrNull().orEmpty())
    }

    /**
     * Where [actor] signs in. Accounts on Bluesky's own servers (`*.host.bsky.network`) sign in at its entryway,
     * bsky.social, which issued their tokens; any other server signs in its accounts itself.
     */
    private suspend fun authServerOf(actor: String): ForgeResult<String> {
        val did = when (val result = identity.did(actor)) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        return when (val pds = identity.pds(did)) {
            is ForgeResult.Failure -> pds
            is ForgeResult.Success -> ForgeResult.Success(if (pds.value.removeSuffix("/").endsWith(".host.bsky.network")) ENTRYWAY else pds.value)
        }
    }

    private fun SessionJson.toTokens(): OAuthTokens {
        identity.remember(handle, did)
        val expiresAt = claims(accessJwt)?.get("exp")?.jsonPrimitive?.longOrNull
        return OAuthTokens(accessJwt, refreshJwt, expiresAt?.let { (it - clock.instant().epochSecond).coerceAtLeast(0) })
    }

    internal companion object {
        /** Bluesky's entryway, where the accounts it hosts sign in. */
        const val ENTRYWAY = "https://bsky.social"

        /** The `sub` of a JWT: for an AT Protocol session, the DID of the account. */
        fun subjectOf(jwt: String): String? = claims(jwt)?.get("sub")?.jsonPrimitive?.content?.takeIf { it.startsWith("did:") }

        private fun claims(jwt: String): JsonObject? = runCatching {
            val payload = jwt.split('.')[1]
            ForgeJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload.trimEnd('=')))).jsonObject
        }.getOrNull()
    }
}

@Serializable
private data class CreateSessionJson(val identifier: String, val password: String)

@Serializable
internal data class SessionJson(val did: String, val handle: String, val accessJwt: String = "", val refreshJwt: String? = null)

@Serializable
private data class WriteJson(val repo: String, val collection: String, val rkey: String? = null, val record: JsonObject? = null)

@Serializable
private data class WrittenJson(val uri: String? = null)

@Serializable
private data class ListRecordsJson(val records: List<RecordJson<JsonObject>> = emptyList(), val cursor: String? = null)
