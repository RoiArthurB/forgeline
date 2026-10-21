package fr.arthurbrugiere.forgeline.core.data.repo

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What each account may do where, as the forge said this session; keyed by account, then repository. Shared: opening a
 * repository learns it with its details, so its conversations and its settings gear don't ask again.
 */
@Singleton
class RepoRightsCache @Inject constructor() {
    private val rights = ConcurrentHashMap<Pair<String, RepoId>, RepoRights>()

    operator fun get(accountId: String, repo: RepoId): RepoRights? = rights[accountId to repo]

    operator fun set(accountId: String, repo: RepoId, value: RepoRights) {
        rights[accountId to repo] = value
    }

    fun forget(forge: ForgeInstance) {
        rights.keys.removeAll { it.second.forge == forge }
    }
}
