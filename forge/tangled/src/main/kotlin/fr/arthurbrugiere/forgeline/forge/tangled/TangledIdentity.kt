package fr.arthurbrugiere.forgeline.forge.tangled

import com.atproto.identity.ResolveHandleQueryParams
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.tangled.actor.GetProfileQueryParams
import sh.christian.ozone.api.AtUri
import java.util.concurrent.ConcurrentHashMap

/**
 * Who a handle is. Tangled addresses people by handle in its pages (`tangled.org/oppi.li/…`), and Forgeline's routes do
 * too, but its API asks for their DID: the handle is resolved once, and remembered. A DID given instead of a handle is
 * taken as it is. A DID's document says the rest: the handle it goes by, and the server that keeps its records.
 */
class TangledIdentity(private val httpClient: HttpClient, private val xrpc: TangledXrpc = TangledXrpc(httpClient)) {
    private val dids = ConcurrentHashMap<String, String>()
    private val documents = ConcurrentHashMap<String, DidDocument>()

    /** Pictures found, by DID; "" for someone known to have none. */
    private val avatars = ConcurrentHashMap<String, String>()

    suspend fun did(handle: String): ForgeResult<String> {
        val name = handle.removePrefix("@").lowercase()
        if (name.startsWith("did:")) return ForgeResult.Success(name)
        dids[name]?.let { return ForgeResult.Success(it) }
        val valid = when (val result = asHandle(name)) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        return forgeCall {
            xrpc.handles.resolveHandle(ResolveHandleQueryParams(valid)).toResult { it.did.did.also { did -> dids[name] = did } }
        }
    }

    /** What an answer already told: a profile view carries both. */
    fun remember(handle: String, did: String) {
        dids[handle.removePrefix("@").lowercase()] = did
    }

    /** The handle [did] goes by, as its document claims it; the DID itself when it claims none. */
    suspend fun handle(did: String): ForgeResult<String> = when (val document = document(did)) {
        is ForgeResult.Failure -> document
        is ForgeResult.Success -> ForgeResult.Success(
            document.value.alsoKnownAs.firstOrNull { it.startsWith("at://") }?.removePrefix("at://")?.also { remember(it, did) } ?: did,
        )
    }

    /** Where [did]'s records are kept: its personal data server, without a trailing `/`. */
    suspend fun pds(did: String): ForgeResult<String> = when (val document = document(did)) {
        is ForgeResult.Failure -> document
        is ForgeResult.Success ->
            document.value.service
                .firstOrNull { it.id.endsWith("#atproto_pds") || it.type == "AtprotoPersonalDataServer" }
                ?.serviceEndpoint?.removeSuffix("/")
                ?.let { ForgeResult.Success(it) }
                ?: notFound("$did names no server")
    }

    /**
     * [did]'s Tangled profile record, as the index serves it. The index asks for the record's own address
     * (`at://<did>/org.tangled.actor.profile/self`): a bare DID is refused.
     */
    internal suspend fun profile(did: String): ForgeResult<ProfileRecordJson> = forgeCall {
        xrpc.index.getProfile(GetProfileQueryParams(AtUri("at://$did/$PROFILE/self"))).toResult { it.value.decodeAs<ProfileRecordJson>() }
    }

    /**
     * [did]'s picture, found as Tangled's own avatar service finds it (`avatar/src/index.js` in tangled.org/core, read
     * 2026-11-05): the blob their Tangled profile names, served by their own server, or else their Bluesky picture. The
     * index names the blob only by its CID, and Tangled's web app serves it through a signed address of its own.
     */
    suspend fun avatar(did: String): String? {
        avatars[did]?.let { return it.ifEmpty { null } }
        return avatarOf(did, (profile(did) as? ForgeResult.Success)?.value?.avatar?.link)
    }

    /** As [avatar], when the profile's blob is already known: [cid], or null for a profile that names none. */
    suspend fun avatarOf(did: String, cid: String?): String? {
        avatars[did]?.let { return it.ifEmpty { null } }
        val blob = cid?.let { (pds(did) as? ForgeResult.Success)?.value?.let { server -> "$server/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid" } }
        if (blob != null) return blob.also { avatars[did] = it }
        return when (val bluesky = blueskyAvatar(did)) {
            is ForgeResult.Success -> bluesky.value.also { avatars[did] = it.orEmpty() }
            // Bluesky doesn't know them (400): they have none. Anything else may pass: asked again next time.
            is ForgeResult.Failure -> null.also { if ((bluesky.error as? ForgeError.Http)?.status == HttpStatusCode.BadRequest.value) avatars[did] = "" }
        }
    }

    private suspend fun blueskyAvatar(did: String): ForgeResult<String?> = forgeCall {
        httpClient.get("$HANDLE_RESOLVER/xrpc/app.bsky.actor.getProfile") {
            parameter("actor", did)
            header(HttpHeaders.Accept, "application/json")
        }.toResult { body<BlueskyProfileJson>().avatar?.takeIf { it.isNotBlank() } }
    }

    /** `did:plc` from the PLC directory, `did:web` from the host's `/.well-known/did.json`. */
    private suspend fun document(did: String): ForgeResult<DidDocument> {
        documents[did]?.let { return ForgeResult.Success(it) }
        val url = when {
            did.startsWith("did:plc:") -> "$PLC_DIRECTORY/$did"
            did.startsWith("did:web:") -> "https://${did.removePrefix("did:web:").replace("%3A", ":")}/.well-known/did.json"
            else -> return notFound("unknown DID method: $did")
        }
        return forgeCall {
            httpClient.get(url) { header(HttpHeaders.Accept, "application/json") }
                .toResult { body<DidDocument>().also { documents[did] = it } }
        }
    }
}

/** The collection a Tangled profile is kept in, under the key `self`. */
internal const val PROFILE = "org.tangled.actor.profile"

/** Where `did:plc` documents are kept. */
internal const val PLC_DIRECTORY = "https://plc.directory"

@Serializable
internal data class DidDocument(val alsoKnownAs: List<String> = emptyList(), val service: List<DidService> = emptyList())

@Serializable
internal data class DidService(val id: String = "", val type: String = "", val serviceEndpoint: String? = null)
