package fr.arthurbrugiere.forgeline.core.data.issue

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.data.account.accountOn
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.model.IssueState
import java.util.concurrent.ConcurrentHashMap
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
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

    /**
     * Adds a comment to [ref]'s conversation as the account signed in on its forge; Unauthorized without one. The
     * conversation kept gains the comment when all of it was loaded.
     */
    suspend fun comment(ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment>

    /**
     * Opens an issue in [repo] as the account signed in on its forge; Unauthorized without one. The new conversation is
     * kept, so it opens at once, and announced on [changed].
     */
    suspend fun create(repo: RepoId, title: String, body: String): ForgeResult<IssueDetails>

    /**
     * Whether the account signed in on [ref]'s forge may close or reopen it: the one who opened it ([author]) always,
     * else whoever the forge lets manage the repository's conversations. False signed out, or when the forge won't say.
     */
    suspend fun canChangeState(ref: IssueRef, author: String?): Boolean

    /**
     * Closes [ref], or reopens it, as the account signed in on its forge; Unauthorized without one. The conversation
     * kept changes state, and the change is announced on [changed].
     */
    suspend fun setOpen(ref: IssueRef, open: Boolean): ForgeResult<Unit>

    /**
     * Every conversation opened, closed or reopened from this app, as it happens: a list of a repository's open
     * issues or pull requests is out of date by one.
     */
    val changed: Flow<IssueRef>

    /** Deletes every conversation kept from [forge], in this session and on disk. */
    suspend fun forget(forge: ForgeInstance)
}

@Singleton
class DefaultIssueRepository @Inject constructor(
    private val clients: ForgeClients,
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
        val entity = dao.get(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number) ?: return null
        val stored = CachedConversation(entity.issue?.let(::decodeIssue), entity.firstPage?.let(::decodePage))
        if (stored.issue == null && stored.firstPage == null) return null
        // Something loaded meanwhile is newer than the disk.
        return synchronized(cache) { cache[ref] ?: stored.also { cache[ref] = it } }
    }

    override suspend fun prefetch(ref: IssueRef, activityAt: Instant): Boolean {
        val saved = dao.get(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number)
        val complete = saved?.issue != null && saved.firstPage != null
        // Nothing kept at all is a conversation the forge couldn't serve: not asked again until it moves.
        val gone = saved != null && saved.issue == null && saved.firstPage == null
        if (saved != null && (complete || gone) && saved.viewedAtMillis >= activityAt.toEpochMilli()) return false
        // The issue and its first page side by side: one round trip to a far forge instead of two.
        val (result, _) = coroutineScope {
            val issue = async { issue(ref) }
            val timeline = async { timeline(ref, page = 1) }
            issue.await() to timeline.await()
        }
        if (result is ForgeResult.Failure) {
            val error = result.error
            if (error is ForgeError.Http && error.status in setOf(403, 404, 410)) {
                // Whatever the timeline alongside answered, the conversation is gone: nothing kept, not asked again.
                synchronized(cache) { cache.remove(ref) }
                dao.upsert(ConversationEntity(ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, null, null, clock.millis()))
            }
        }
        return true
    }

    override suspend fun issue(ref: IssueRef): ForgeResult<IssueDetails> = clients.issues(ref.repo.forge).issue(accounts.tokenOn(ref.repo.forge), ref).also { result ->
        if (result is ForgeResult.Success) update(ref) { it.copy(issue = result.value) }
    }

    override suspend fun timeline(ref: IssueRef, page: Int): ForgeResult<TimelinePage> = clients.issues(ref.repo.forge).timeline(accounts.tokenOn(ref.repo.forge), ref, page).also { result ->
        if (result is ForgeResult.Success && page == 1) update(ref) { it.copy(firstPage = result.value) }
    }

    override suspend fun comment(ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> {
        val token = accounts.tokenOn(ref.repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.issues(ref.repo.forge).comment(token, ref, body).also { result ->
            // Nothing kept, nothing to add to: an empty entry would read as a conversation the forge couldn't serve.
            if (result is ForgeResult.Success && cached(ref) != null) {
                update(ref) { kept ->
                    kept.copy(
                        issue = kept.issue?.let { it.copy(comments = it.comments + 1) },
                        // Only at the end of a conversation loaded whole: after pages not loaded, it would be misplaced.
                        firstPage = kept.firstPage?.let { page -> if (page.nextPage == null) page.copy(items = page.items + result.value) else page },
                    )
                }
            }
        }
    }

    private val changes = MutableSharedFlow<IssueRef>(extraBufferCapacity = 8)

    override val changed: Flow<IssueRef> = changes.asSharedFlow()

    /** What each account may manage, asked once per repository and session; keyed by account, then repository. */
    private val managed = ConcurrentHashMap<Pair<String, RepoId>, Boolean>()

    override suspend fun canChangeState(ref: IssueRef, author: String?): Boolean {
        val account = accounts.accountOn(ref.repo.forge) ?: return false
        if (author != null && author.equals(account.user.login, ignoreCase = true)) return true
        managed[account.id to ref.repo]?.let { return it }
        val token = accounts.token(account.id) ?: return false
        // A failure isn't remembered: it is asked again the next time the conversation opens.
        val answer = clients.issues(ref.repo.forge).canManage(token, ref.repo) as? ForgeResult.Success ?: return false
        managed[account.id to ref.repo] = answer.value
        return answer.value
    }

    override suspend fun setOpen(ref: IssueRef, open: Boolean): ForgeResult<Unit> {
        val token = accounts.tokenOn(ref.repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.issues(ref.repo.forge).setOpen(token, ref, open).also { result ->
            if (result is ForgeResult.Success) {
                // Nothing kept, nothing to change: an empty entry would read as a conversation the forge couldn't serve.
                if (cached(ref)?.issue != null) {
                    update(ref) { kept ->
                        kept.copy(
                            issue = kept.issue?.copy(
                                state = if (open) IssueState.OPEN else IssueState.CLOSED,
                                closedAt = if (open) null else clock.instant(),
                            ),
                        )
                    }
                }
                changes.tryEmit(ref)
            }
        }
    }

    override suspend fun create(repo: RepoId, title: String, body: String): ForgeResult<IssueDetails> {
        val token = accounts.tokenOn(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.issues(repo.forge).create(token, repo, title, body).also { result ->
            if (result is ForgeResult.Success) {
                // A new issue has no conversation yet: kept whole, it opens without asking the forge first.
                update(result.value.ref) { CachedConversation(result.value, TimelinePage(emptyList(), nextPage = null)) }
                changes.tryEmit(result.value.ref)
            }
        }
    }

    override suspend fun forget(forge: ForgeInstance) {
        managed.keys.removeAll { it.second.forge == forge }
        synchronized(cache) { cache.keys.removeAll { it.repo.forge == forge } }
        dao.clear(forge.host)
    }

    private suspend fun update(ref: IssueRef, change: (CachedConversation) -> CachedConversation) {
        val updated = synchronized(cache) { change(cache[ref] ?: CachedConversation(null, null)).also { cache[ref] = it } }
        dao.upsert(
            ConversationEntity(
                ref.repo.forge.host, ref.repo.owner, ref.repo.name, ref.number, updated.issue?.encode(), updated.firstPage?.encode(), clock.millis(),
            ),
        )
        dao.prune(STORED_CONVERSATIONS)
    }


    companion object {
        const val CACHE_SIZE = 50

        /** Conversations kept on disk: the Inbox reopens the same few constantly. */
        const val STORED_CONVERSATIONS = 300
    }
}
