package fr.arthurbrugiere.forgeline.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * What self-hosted servers run, by host. A host alone doesn't say whether it is a Forgejo or a GitLab, and most of the
 * app carries only the host (routes, kept data): the kind is learnt when signing in to the server, remembered here, and
 * told again at each launch before anything is opened.
 */
object KnownForges {
    private val types = ConcurrentHashMap<String, ForgeType>()

    /** Remembers what [instance] runs. github.com, gitlab.com and codeberg.org are known without being told. */
    fun remember(instance: ForgeInstance) {
        if (instance != ForgeInstance.GitHub && instance != ForgeInstance.GitLab) types[instance.host.lowercase()] = instance.type
    }

    fun typeOf(host: String): ForgeType? = types[host.lowercase()]

    /** For tests: what one remembered must not reach the next. */
    fun clear() = types.clear()
}
