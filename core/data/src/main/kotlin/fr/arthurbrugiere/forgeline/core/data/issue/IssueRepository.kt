package fr.arthurbrugiere.forgeline.core.data.issue

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class CachedConversation(val issue: IssueDetails?, val firstPage: TimelinePage?)

interface IssueRepository {
    /** The last loaded state of [ref] in this session, for an instant reopen; null if not loaded this session. */
    fun cached(ref: IssueRef): CachedConversation?

    /** Like [cached], but also looks on disk, where the last conversations viewed are kept across launches. */
    suspend fun stored(ref: IssueRef): CachedConversation?

    suspend fun issue(ref: IssueRef): ForgeResult<IssueDetails>

    /**
     * Loads and keeps [ref] ahead of opening it, unless the copy kept is at least as recent as [activityAt].
     * True when it was fetched.
     */
    suspend fun prefetch(ref: IssueRef, activityAt: Instant): Boolean

    suspend fun timeline(ref: IssueRef, page: Int): ForgeResult<TimelinePage>
}

@Singleton
class DefaultIssueRepository @Inject constructor(
    private val api: IssueApi,
    private val accounts: AccountRepository,
    private val dao: ConversationDao,
    private val clock: Clock,
) : IssueRepository {

    private val cache = object : LinkedHashMap<IssueRef, CachedConversation>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IssueRef, CachedConversation>) = size > CACHE_SIZE
    }

    override fun cached(ref: IssueRef): CachedConversation? = synchronized(cache) { cache[ref] }

    override suspend fun stored(ref: IssueRef): CachedConversation? {
        cached(ref)?.let { return it }
        val entity = dao.get(ref.repo.owner, ref.repo.name, ref.number) ?: return null
        val stored = CachedConversation(entity.issue?.let(::decodeIssue), entity.firstPage?.let(::decodePage))
        if (stored.issue == null && stored.firstPage == null) return null
        // Something loaded meanwhile is newer than the disk.
        return synchronized(cache) { cache[ref] ?: stored.also { cache[ref] = it } }
    }

    override suspend fun prefetch(ref: IssueRef, activityAt: Instant): Boolean {
        val saved = dao.get(ref.repo.owner, ref.repo.name, ref.number)
        val complete = saved?.issue != null && saved.firstPage != null
        // Nothing kept at all is a conversation the forge couldn't serve: not asked again until it moves.
        val gone = saved != null && saved.issue == null && saved.firstPage == null
        if (saved != null && (complete || gone) && saved.viewedAtMillis >= activityAt.toEpochMilli()) return false
        val result = issue(ref)
        if (result is ForgeResult.Failure) {
            val error = result.error
            if (error is ForgeError.Http && error.status in setOf(403, 404, 410)) {
                dao.upsert(ConversationEntity(ref.repo.owner, ref.repo.name, ref.number, null, null, clock.millis()))
            }
            return true
        }
        timeline(ref, page = 1)
        return true
    }

    override suspend fun issue(ref: IssueRef): ForgeResult<IssueDetails> = api.issue(token(), ref).also { result ->
        if (result is ForgeResult.Success) update(ref) { it.copy(issue = result.value) }
    }

    override suspend fun timeline(ref: IssueRef, page: Int): ForgeResult<TimelinePage> = api.timeline(token(), ref, page).also { result ->
        if (result is ForgeResult.Success && page == 1) update(ref) { it.copy(firstPage = result.value) }
    }

    private suspend fun update(ref: IssueRef, change: (CachedConversation) -> CachedConversation) {
        val updated = synchronized(cache) { change(cache[ref] ?: CachedConversation(null, null)).also { cache[ref] = it } }
        dao.upsert(
            ConversationEntity(
                ref.repo.owner, ref.repo.name, ref.number, updated.issue?.encode(), updated.firstPage?.encode(), clock.millis(),
            ),
        )
        dao.prune(STORED_CONVERSATIONS)
    }

    private suspend fun token(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }

    companion object {
        const val CACHE_SIZE = 50

        /** Conversations kept on disk: the Inbox reopens the same few constantly. */
        const val STORED_CONVERSATIONS = 300
    }
}
