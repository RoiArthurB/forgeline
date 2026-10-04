package fr.arthurbrugiere.forgeline.signin

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.KnownForges

/** Remembers servers for as long as the test runs. */
class FakeForgeHosts : ForgeHosts {
    val remembered = mutableMapOf<ForgeInstance, String>()

    override fun load() = remembered.keys.forEach(KnownForges::remember)

    override fun remember(instance: ForgeInstance, oauthClientId: String?) {
        KnownForges.remember(instance)
        remembered[instance] = oauthClientId?.trim()?.ifEmpty { null } ?: oauthClientId(instance.host)
    }

    override fun oauthClientId(host: String): String = remembered.entries.firstOrNull { it.key.host == host }?.value.orEmpty()
}
