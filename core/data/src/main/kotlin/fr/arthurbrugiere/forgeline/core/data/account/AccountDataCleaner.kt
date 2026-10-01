package fr.arthurbrugiere.forgeline.core.data.account

import fr.arthurbrugiere.forgeline.core.data.feed.FeedDao
import fr.arthurbrugiere.forgeline.core.data.feed.FeedPreviewDao
import fr.arthurbrugiere.forgeline.core.data.feed.STARRED_PREFIX
import fr.arthurbrugiere.forgeline.core.data.inbox.DoneDao
import fr.arthurbrugiere.forgeline.core.data.inbox.InboxDao
import fr.arthurbrugiere.forgeline.core.data.issue.IssueRepository
import fr.arthurbrugiere.forgeline.core.data.repo.RepoDao
import fr.arthurbrugiere.forgeline.core.data.user.UserRepository
import fr.arthurbrugiere.forgeline.core.model.Account
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Deletes what the app kept for an account once it has signed out. */
fun interface SignedOutData {
    /** Call after [account] signed out. */
    suspend fun forget(account: Account)
}

/**
 * What goes when an account signs out: its own rows (notifications, Feed, starred activity), and what was kept from
 * its forge whoever asked (conversations, repositories, previews), since some of it was read with that account and
 * may be private. Anything still wanted from the forge is fetched again.
 */
@Singleton
class AccountDataCleaner @Inject constructor(
    private val accounts: AccountRepository,
    private val inbox: InboxDao,
    private val done: DoneDao,
    private val feed: FeedDao,
    private val repos: RepoDao,
    private val previews: FeedPreviewDao,
    private val conversations: IssueRepository,
    private val users: UserRepository,
) : SignedOutData {

    override suspend fun forget(account: Account) {
        sweep()
        val host = account.forge.host
        inbox.clearStates(host)
        repos.clear(host)
        previews.clear(host)
        conversations.forget(account.forge)
        users.forget(account.forge)
    }

    /**
     * Deletes the rows of every account no longer signed in. Also run when the app starts: a sync still running at
     * sign-out can write after [forget], and versions before this one deleted nothing.
     */
    suspend fun sweep() {
        val ids = accounts.accounts.first().map { it.id }
        inbox.keepOnly(ids)
        done.keepOnly(ids)
        feed.keepOnly(ids + ids.map { STARRED_PREFIX + it })
    }
}
