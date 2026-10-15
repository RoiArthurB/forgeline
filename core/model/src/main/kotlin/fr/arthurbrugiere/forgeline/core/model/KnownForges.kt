package fr.arthurbrugiere.forgeline.core.model

import java.util.concurrent.ConcurrentHashMap

/**
 * What self-hosted servers run, by host. A host alone doesn't say whether it is a Forgejo or a GitLab, and most of the
 * app carries only the host (routes, kept data): the kind is learnt when signing in to the server, remembered here, and
 * read again after each launch from where [readFrom] says it is kept.
 */
object KnownForges {
    private val types = ConcurrentHashMap<String, ForgeType>()

    @Volatile
    private var kept: Lazy<Unit> = lazyOf(Unit)

    /**
     * Where the servers of earlier launches are kept. They are read once, by the first to ask about a self-hosted
     * server, so telling this at launch reads nothing on the way to the first screen.
     */
    fun readFrom(read: () -> List<ForgeInstance>) {
        kept = lazy { read().forEach(::remember) }
    }

    /** Reads the kept servers now if no one has yet: off the main thread, so a page opened later doesn't wait for it. */
    fun readKept() = kept.value

    /** Remembers what [instance] runs. github.com, gitlab.com and codeberg.org are known without being told. */
    fun remember(instance: ForgeInstance) {
        val host = instance.host.lowercase()
        if (host !in KnownByName) types[host] = instance.type
    }

    fun typeOf(host: String): ForgeType? {
        readKept()
        return types[host.lowercase()]
    }

    /** Every self-hosted server remembered. */
    fun all(): List<ForgeInstance> {
        readKept()
        return types.map { (host, type) -> ForgeInstance(type, host) }
    }

    /** For tests: what one remembered must not reach the next. */
    fun clear() {
        types.clear()
        kept = lazyOf(Unit)
    }

    private val KnownByName = setOf(ForgeInstance.GitHub.host, ForgeInstance.GitLab.host, ForgeInstance.Codeberg.host)
}
