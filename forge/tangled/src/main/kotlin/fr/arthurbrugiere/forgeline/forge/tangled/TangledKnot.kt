package fr.arthurbrugiere.forgeline.forge.tangled

import com.atproto.repo.CreateRecordRequest
import com.atproto.repo.DeleteRecordRequest
import com.atproto.repo.GetRecordQueryParams
import com.atproto.repo.PutRecordRequest
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.tangled.xrpc.Atproto
import fr.arthurbrugiere.forgeline.forge.tangled.xrpc.TangledIndex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import sh.christian.ozone.api.AtUri
import sh.christian.ozone.api.Cid
import sh.christian.ozone.api.Did
import sh.christian.ozone.api.Nsid
import sh.christian.ozone.api.RKey
import sh.christian.ozone.api.model.JsonContent.Companion.encodeAsJsonContent
import sh.christian.ozone.api.response.AtpResponse
import java.time.Clock
import java.time.temporal.ChronoUnit

/** A record as written, by its address and the content id of the version it is at: what a comment or reaction names. */
internal data class StrongRef(val uri: String, val cid: String)

/** A record read from where it is kept: the version it is at, and its content. */
internal data class StoredRecord(val ref: StrongRef, val value: JsonObject)

/**
 * The repository's knot: the server that keeps its git and, since 2026-11, its "collaborative objects", the records
 * people write together there rather than each on their own server (tickets, which are issues and pull requests,
 * comments, reactions, label definitions, review rounds). As Tangled's web app does (`web/src/lib/api/cob.ts` in
 * tangled.org/core, read 2026-11-17):
 *
 * - a write is one of the AT Protocol's own record calls (`com.atproto.repo.createRecord`, `putRecord`,
 *   `deleteRecord`), sent to the knot with a service-auth token the person's own server signs for them
 *   (`com.atproto.server.getServiceAuth`), its audience the repository's DID and bound to that one call;
 * - an edit replaces the whole record, so it starts from the one the knot keeps (`getRecord`), and names the version
 *   it started from (`swapRecord`): someone else's write in between makes it fail rather than be lost;
 * - each record written is then claimed on the person's own server with an `org.tangled.graph.attestation` naming
 *   it: the knot says who wrote it, the attestation is the writer saying so too.
 */
internal class TangledKnot(
    private val xrpc: TangledXrpc,
    private val account: TangledAccount,
    private val identity: TangledIdentity,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The knot at [host] (`knot1.tangled.sh`, or a `did:web:` naming it), as its base address. */
    fun base(host: String): String = "https://" + host.removePrefix("did:web:").replace("%3A", ":").removePrefix("https://").trimEnd('/')

    /** The knot at [host], read without an account: its records and its git. */
    fun reader(host: String): Atproto = xrpc.server(base(host))

    /** The knot at [host] as Tangled's own calls, read without an account (`org.tangled.git.*`). */
    fun git(host: String): TangledIndex = xrpc.service(base(host))

    /** Makes a record in [collection] of the repository [repoDid] on its knot, under [rkey] or one the knot picks. */
    suspend fun create(token: String, host: String, repoDid: String, collection: String, record: JsonObject, rkey: String? = null): ForgeResult<StrongRef> = forgeCall {
        val repo = when (val did = asDid(repoDid)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        val written = when (val knot = signed(token, host, repoDid, CREATE)) {
            is ForgeResult.Failure -> return@forgeCall knot
            is ForgeResult.Success -> knot.value.createRecord(
                CreateRecordRequest(repo, Nsid(collection), rkey?.let(::RKey), record = record.content()),
            ).toResult { StrongRef(it.uri.atUri, it.cid.cid) }
        }
        written.also { if (it is ForgeResult.Success) attest(token, it.value) }
    }

    /** The record at [uri] as the knot at [host] keeps it now. */
    suspend fun read(host: String, uri: String): ForgeResult<StoredRecord> = forgeCall {
        val (repo, collection, rkey) = when (val parts = partsOf(uri)) {
            is ForgeResult.Failure -> return@forgeCall parts
            is ForgeResult.Success -> parts.value
        }
        reader(host).getRecord(GetRecordQueryParams(repo, Nsid(collection), RKey(rkey))).toResult {
            StoredRecord(StrongRef(it.uri.atUri, it.cid?.cid.orEmpty()), it.value.decodeAs<JsonObject>())
        }
    }

    /**
     * Rewrites the record at [uri] with what [change] makes of the one the knot keeps, which it must still be when it
     * is written: [ForgeError.Conflict] when someone changed it in between.
     */
    suspend fun update(token: String, host: String, uri: String, change: (JsonObject) -> JsonObject): ForgeResult<StrongRef> = forgeCall {
        val current = when (val read = read(host, uri)) {
            is ForgeResult.Failure -> return@forgeCall read
            is ForgeResult.Success -> read.value
        }
        val (repo, collection, rkey) = when (val parts = partsOf(uri)) {
            is ForgeResult.Failure -> return@forgeCall parts
            is ForgeResult.Success -> parts.value
        }
        // The knot adds who wrote a version; it isn't part of what is written.
        val record = change(JsonObject(current.value - EDITOR))
        val written = when (val knot = signed(token, host, repo.did, PUT)) {
            is ForgeResult.Failure -> return@forgeCall knot
            is ForgeResult.Success -> knot.value.putRecord(
                PutRecordRequest(
                    repo,
                    Nsid(collection),
                    RKey(rkey),
                    record = record.content(),
                    swapRecord = current.ref.cid.takeIf { it.isNotEmpty() }?.let(::Cid),
                ),
            ).let { answer ->
                // An AT Protocol server refuses a stale swap as a 400 named InvalidSwap.
                if (answer is AtpResponse.Failure && (answer.error?.error == "InvalidSwap" || answer.statusCode.code == 409)) {
                    ForgeResult.Failure(ForgeError.Conflict)
                } else {
                    answer.toResult { StrongRef(it.uri.atUri, it.cid.cid) }
                }
            }
        }
        written.also { if (it is ForgeResult.Success) attest(token, it.value) }
    }

    /** Deletes the record at [uri] from the knot at [host]. */
    suspend fun delete(token: String, host: String, uri: String): ForgeResult<Unit> = forgeCall {
        val (repo, collection, rkey) = when (val parts = partsOf(uri)) {
            is ForgeResult.Failure -> return@forgeCall parts
            is ForgeResult.Success -> parts.value
        }
        when (val knot = signed(token, host, repo.did, DELETE)) {
            is ForgeResult.Failure -> knot
            is ForgeResult.Success -> knot.value.deleteRecord(DeleteRecordRequest(repo, Nsid(collection), RKey(rkey))).toResult { }
        }
    }

    /** The knot at [host], as the person [token] signs in, for the one call [lxm] on the repository [repoDid]. */
    suspend fun signed(token: String, host: String, repoDid: String, lxm: String): ForgeResult<Atproto> =
        serviceToken(token, repoDid, lxm).then { ForgeResult.Success(xrpc.server(base(host), it)) }

    /** As [signed], for one of Tangled's own calls on the knot (`org.tangled.review.submitPatch`). */
    suspend fun signedService(token: String, host: String, repoDid: String, lxm: String): ForgeResult<TangledIndex> =
        serviceToken(token, repoDid, lxm).then { ForgeResult.Success(xrpc.service(base(host), it)) }

    /** Signed by the person's own server for [lxm] alone, addressed to the repository [repoDid]: the knot checks the repository's DID, not its own. */
    private suspend fun serviceToken(token: String, repoDid: String, lxm: String): ForgeResult<String> = account.serviceToken(token, repoDid, lxm)

    /**
     * Claims [written] as the signed-in person's, on their own server. Tangled's web app keeps trying when that fails;
     * here a failure is let go: the record is written, the knot names its writer, and the claim only seconds it.
     */
    private suspend fun attest(token: String, written: StrongRef) {
        forgeCall {
            account.createRecord(
                token,
                ATTESTATION,
                buildJsonObject {
                    put("\$type", ATTESTATION)
                    putJsonObject("subject") {
                        put("uri", written.uri)
                        put("cid", written.cid)
                    }
                    put("createdAt", clock.instant().truncatedTo(ChronoUnit.MILLIS).toString())
                },
            )
        }
    }

    private fun partsOf(uri: String): ForgeResult<Triple<Did, String, String>> {
        val parts = uri.removePrefix("at://").split('/')
        if (parts.size != 3) return ForgeResult.Failure(ForgeError.Unreadable)
        return asDid(parts[0]).then { ForgeResult.Success(Triple(it, parts[1], parts[2])) }
    }

    private fun JsonObject.content() = XRPC_JSON.run { encodeAsJsonContent(this@content) }

    companion object {
        const val CREATE = "com.atproto.repo.createRecord"
        const val PUT = "com.atproto.repo.putRecord"
        const val DELETE = "com.atproto.repo.deleteRecord"
        const val ATTESTATION = "org.tangled.graph.attestation"

        /** What the knot adds to a record it serves: who wrote that version. */
        const val EDITOR = "x-tngl-editor"
    }
}

/** How records are written: as ozone reads and writes XRPC, `$type` and all. */
private val XRPC_JSON = sh.christian.ozone.api.runtime.buildXrpcJsonConfiguration()
