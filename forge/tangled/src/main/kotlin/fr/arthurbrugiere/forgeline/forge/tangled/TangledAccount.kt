package fr.arthurbrugiere.forgeline.forge.tangled

import com.atproto.repo.CreateRecordRequest
import com.atproto.repo.DeleteRecordRequest
import com.atproto.repo.ListRecordsQueryParams
import com.atproto.repo.PutRecordRequest
import com.atproto.server.CreateSessionRequest
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OAuthTokens
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.tangled.xrpc.Atproto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import sh.christian.ozone.api.Did
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.api.RKey
import sh.christian.ozone.api.model.JsonContent
import sh.christian.ozone.api.model.JsonContent.Companion.encodeAsJsonContent
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
    private val xrpc: TangledXrpc,
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
        xrpc.server(base).createSession(CreateSessionRequest(name, password))
            .toResult { tokens(it.handle.handle, it.did.did, it.accessJwt, it.refreshJwt) }
    }

    /** A new session for the one [refreshToken] belongs to; AT Protocol servers hand a new refresh token each time. */
    suspend fun refreshSession(refreshToken: String): ForgeResult<OAuthTokens> = forgeCall {
        val did = subjectOf(refreshToken) ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val base = when (val server = authServerOf(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        xrpc.server(base, refreshToken).refreshSession()
            .toResult { tokens(it.handle.handle, it.did.did, it.accessJwt, it.refreshJwt) }
    }

    /** Who [token] signs in: their handle, asked of their own server, which says at once whether it still takes the token. */
    suspend fun user(token: String): ForgeResult<ForgeUser> = forgeCall {
        val did = subjectOf(token) ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val pds = when (val server = identity.pds(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        xrpc.server(pds, token).getSession().toResult { session ->
            identity.remember(session.handle.handle, session.did.did)
            ForgeUser(session.handle.handle, null, null)
        }
    }

    /** The DID [token] is for. */
    fun did(token: String): ForgeResult<String> = subjectOf(token)?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unauthorized)

    /** Creates a record in the signed-in person's [collection], under a key their server picks; answers its AT URI. */
    suspend fun createRecord(token: String, collection: String, record: JsonObject): ForgeResult<String> = write(token) { server, did ->
        server.createRecord(CreateRecordRequest(did, Nsid(collection), record = record.content())).toResult { it.uri.atUri }
    }

    /** Writes the record [rkey] of [collection], replacing what was there. */
    suspend fun putRecord(token: String, collection: String, rkey: String, record: JsonObject): ForgeResult<String> = write(token) { server, did ->
        server.putRecord(PutRecordRequest(did, Nsid(collection), RKey(rkey), record = record.content())).toResult { it.uri.atUri }
    }

    /** Deletes the record at [uri] (`at://did/collection/rkey`), which must be the signed-in person's; gone already is done. */
    suspend fun deleteRecord(token: String, uri: String): ForgeResult<Unit> {
        val parts = uri.removePrefix("at://").split('/')
        if (parts.size != 3) return ForgeResult.Failure(ForgeError.Unreadable)
        val (_, collection, rkey) = parts
        return write(token) { server, did -> server.deleteRecord(DeleteRecordRequest(did, Nsid(collection), RKey(rkey))).toResult { } }
    }

    /** The signed-in person's records of [collection], a page at a time, as their own server keeps them. */
    internal suspend fun listRecords(did: String, collection: String, cursor: String?): ForgeResult<RecordPage> = forgeCall {
        val pds = when (val server = identity.pds(did)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        val repo = when (val valid = asDid(did)) {
            is ForgeResult.Failure -> return@forgeCall valid
            is ForgeResult.Success -> valid.value
        }
        xrpc.server(pds).listRecords(ListRecordsQueryParams(repo, Nsid(collection), limit = 100, cursor = cursor))
            .toResult { page -> RecordPage(page.records.map { it.uri.atUri to it.value.decodeAs<JsonObject>() }, page.cursor) }
    }

    private suspend fun <T> write(token: String, call: suspend (server: Atproto, did: Did) -> ForgeResult<T>): ForgeResult<T> = forgeCall {
        val me = subjectOf(token) ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        val did = when (val valid = asDid(me)) {
            is ForgeResult.Failure -> return@forgeCall valid
            is ForgeResult.Success -> valid.value
        }
        val pds = when (val server = identity.pds(me)) {
            is ForgeResult.Failure -> return@forgeCall server
            is ForgeResult.Success -> server.value
        }
        call(xrpc.server(pds, token), did)
    }

    private fun JsonObject.content(): JsonContent = XRPC_JSON.run { encodeAsJsonContent(this@content) }

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

    private fun tokens(handle: String, did: String, access: String, refresh: String): OAuthTokens {
        identity.remember(handle, did)
        val expiresAt = claims(access)?.get("exp")?.jsonPrimitive?.longOrNull
        return OAuthTokens(access, refresh, expiresAt?.let { (it - clock.instant().epochSecond).coerceAtLeast(0) })
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

/** A page of someone's records, as their own server lists them: each record's AT URI and its content. */
internal data class RecordPage(val records: List<Pair<String, JsonObject>>, val cursor: String?)

/** How records are written: as ozone reads and writes XRPC, `$type` and all. */
private val XRPC_JSON = sh.christian.ozone.api.runtime.buildXrpcJsonConfiguration()
