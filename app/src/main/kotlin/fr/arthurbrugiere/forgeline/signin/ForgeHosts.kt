package fr.arthurbrugiere.forgeline.signin

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.KnownForges
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-hosted servers signed in to: what each runs, and its OAuth application's ID when one was given. The kind
 * feeds [KnownForges], which is how a host alone is told from a Forgejo.
 */
interface ForgeHosts {
    /** Tells [KnownForges] where the servers remembered are kept, reading nothing yet. Called at launch. */
    fun load()

    fun remember(instance: ForgeInstance, oauthClientId: String? = null)

    /** The ID of the OAuth application on [host], blank without one. */
    fun oauthClientId(host: String): String
}

/**
 * Kept in a small preferences file rather than with the accounts: it is read whole and at once, by the first to ask
 * about a self-hosted server, where the accounts' store is read in a flow that a link opened at launch can't wait for.
 */
@Singleton
class StoredForgeHosts @Inject constructor(@ApplicationContext context: Context) : ForgeHosts {
    // Opened on first use: opening it touches the disk, and this is built on the main thread at launch.
    private val preferences by lazy { context.getSharedPreferences("forge_hosts", Context.MODE_PRIVATE) }

    override fun load() = KnownForges.readFrom {
        preferences.all.mapNotNull { (host, value) ->
            val type = ForgeType.entries.firstOrNull { it.name == (value as? String)?.substringBefore('|') } ?: return@mapNotNull null
            ForgeInstance(type, host)
        }
    }

    override fun remember(instance: ForgeInstance, oauthClientId: String?) {
        KnownForges.remember(instance)
        // An ID given before is kept when none is given now: signing in again with a token doesn't forget it.
        val clientId = oauthClientId?.trim()?.ifEmpty { null } ?: oauthClientId(instance.host)
        preferences.edit { putString(instance.host, "${instance.type.name}|$clientId") }
    }

    override fun oauthClientId(host: String): String = preferences.getString(host.lowercase(), null)?.substringAfter('|', "").orEmpty()
}
