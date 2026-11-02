package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Who a handle is. Tangled addresses people by handle in its pages (`tangled.org/oppi.li/…`), and Forgeline's routes do
 * too, but its API asks for their DID: the handle is resolved once, and remembered. A DID given instead of a handle is
 * taken as it is. A DID's document says the rest: the handle it goes by, and the server that keeps its records.
 */
class TangledIdentity(private val httpClient: HttpClient) {
    private val dids = ConcurrentHashMap<String, String>()
    private val documents = ConcurrentHashMap<String, DidDocument>()

    suspend fun did(handle: String): ForgeResult<String> {
        val name = handle.removePrefix("@").lowercase()
        if (name.startsWith("did:")) return ForgeResult.Success(name)
        dids[name]?.let { return ForgeResult.Success(it) }
        return forgeCall {
            httpClient.xrpc("com.atproto.identity.resolveHandle", "handle" to name, base = HANDLE_RESOLVER)
                .toResult { body<DidJson>().did.also { dids[name] = it } }
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

/** Where `did:plc` documents are kept. */
internal const val PLC_DIRECTORY = "https://plc.directory"

@Serializable
private data class DidJson(val did: String)

@Serializable
internal data class DidDocument(val alsoKnownAs: List<String> = emptyList(), val service: List<DidService> = emptyList())

@Serializable
internal data class DidService(val id: String = "", val type: String = "", val serviceEndpoint: String? = null)
