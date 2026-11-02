package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Who a handle is. Tangled addresses people by handle in its pages (`tangled.org/oppi.li/…`), and Forgeline's routes do
 * too, but its API asks for their DID: the handle is resolved once, and remembered. A DID given instead of a handle is
 * taken as it is.
 */
class TangledIdentity(private val httpClient: HttpClient) {
    private val dids = ConcurrentHashMap<String, String>()

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
}

@Serializable
private data class DidJson(val did: String)
