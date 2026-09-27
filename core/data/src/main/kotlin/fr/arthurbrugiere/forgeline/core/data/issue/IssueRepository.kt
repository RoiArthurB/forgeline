package fr.arthurbrugiere.forgeline.core.data.issue

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class CachedConversation(val issue: IssueDetails?, val firstPage: TimelinePage?)

interface IssueRepository {
    /** The last loaded state of [ref] in this session, for an instant reopen; null if never loaded. */
    fun cached(ref: IssueRef): CachedConversation?

    suspend fun issue(ref: IssueRef): ForgeResult<IssueDetails>

    suspend fun timeline(ref: IssueRef, page: Int): ForgeResult<TimelinePage>
}

@Singleton
class DefaultIssueRepository @Inject constructor(
    private val api: IssueApi,
    private val accounts: AccountRepository,
) : IssueRepository {

    private val cache = object : LinkedHashMap<IssueRef, CachedConversation>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IssueRef, CachedConversation>) = size > CACHE_SIZE
    }

    override fun cached(ref: IssueRef): CachedConversation? = synchronized(cache) { cache[ref] }

    override suspend fun issue(ref: IssueRef): ForgeResult<IssueDetails> = api.issue(token(), ref).also { result ->
        if (result is ForgeResult.Success) update(ref) { it.copy(issue = result.value) }
    }

    override suspend fun timeline(ref: IssueRef, page: Int): ForgeResult<TimelinePage> = api.timeline(token(), ref, page).also { result ->
        if (result is ForgeResult.Success && page == 1) update(ref) { it.copy(firstPage = result.value) }
    }

    private fun update(ref: IssueRef, change: (CachedConversation) -> CachedConversation) = synchronized(cache) {
        cache[ref] = change(cache[ref] ?: CachedConversation(null, null))
    }

    private suspend fun token(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }

    companion object {
        const val CACHE_SIZE = 50
    }
}
