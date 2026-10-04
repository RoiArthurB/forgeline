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
    /** Tells [KnownForges] every server remembered. Called at launch, before anything is opened. */
    fun load()

    fun remember(instance: ForgeInstance, oauthClientId: String? = null)

    /** The ID of the OAuth application on [host], blank without one. */
    fun oauthClientId(host: String): String
}

/**
 * Kept in a small preferences file rather than with the accounts: it is read whole and at once at launch, where the
 * accounts' store is read later and off the main thread, after a link may already have been opened.
 */
@Singleton
class StoredForgeHosts @Inject constructor(@param:ApplicationContext context: Context) : ForgeHosts {
    private val preferences = context.getSharedPreferences("forge_hosts", Context.MODE_PRIVATE)

    override fun load() {
        preferences.all.forEach { (host, value) ->
            val type = ForgeType.entries.firstOrNull { it.name == (value as? String)?.substringBefore('|') } ?: return@forEach
            KnownForges.remember(ForgeInstance(type, host))
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
