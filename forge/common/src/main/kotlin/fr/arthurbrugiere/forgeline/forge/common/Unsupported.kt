package fr.arthurbrugiere.forgeline.forge.common

import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsApi
import fr.arthurbrugiere.forgeline.core.forge.NotificationsSync
import fr.arthurbrugiere.forgeline.core.forge.PullRequestApi
import fr.arthurbrugiere.forgeline.core.forge.RepoSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.SearchApi
import fr.arthurbrugiere.forgeline.core.forge.StarApi
import fr.arthurbrugiere.forgeline.core.forge.UserApi
import fr.arthurbrugiere.forgeline.core.model.ChangedFiles
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.CommitDetails
import fr.arthurbrugiere.forgeline.core.model.CommitPage
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.RepoSettings
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.SubjectState
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkKind

// What a forge whose API reaches only part of Forgeline's (Tangled, SourceHut) answers for the rest: Unsupported, which
// the app shows as "this forge doesn't offer this" rather than as a failure. A client extends these and overrides what
// its forge does have.

private fun <T> unsupported(): ForgeResult<T> = ForgeResult.Failure(ForgeError.Unsupported)

open class UnsupportedIssueApi : IssueApi {
    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = unsupported()

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = unsupported()

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = unsupported()

    override suspend fun create(token: String, repo: RepoId, title: String, body: String, labels: List<String>): ForgeResult<IssueDetails> = unsupported()

    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> = unsupported()

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = unsupported()
}

open class UnsupportedPullRequestApi : PullRequestApi {
    override suspend fun files(token: String?, ref: IssueRef, page: Int): ForgeResult<ChangedFiles> = unsupported()

    override suspend fun commits(token: String?, ref: IssueRef): ForgeResult<List<Commit>> = unsupported()

    override suspend fun checks(token: String?, ref: IssueRef): ForgeResult<List<Check>> = unsupported()

    override suspend fun mergeInfo(token: String, ref: IssueRef): ForgeResult<MergeInfo> = unsupported()

    override suspend fun merge(token: String, ref: IssueRef, method: MergeMethod, deleteBranch: Boolean, whenChecksPass: Boolean): ForgeResult<Unit> = unsupported()

    override suspend fun review(token: String, ref: IssueRef, verdict: ReviewVerdict, body: String, comments: List<LineComment>): ForgeResult<Unit> = unsupported()

    override suspend fun create(token: String, repo: RepoId, request: NewPullRequest): ForgeResult<IssueRef> = unsupported()

    override suspend fun history(token: String?, repo: RepoId, ref: String?, path: String?, page: Int): ForgeResult<CommitPage> = unsupported()

    override suspend fun commit(token: String?, repo: RepoId, sha: String): ForgeResult<CommitDetails> = unsupported()
}

open class UnsupportedRepoSettingsApi : RepoSettingsApi {
    override suspend fun settings(token: String, repo: RepoId): ForgeResult<RepoSettings> = unsupported()

    override suspend fun change(token: String, repo: RepoId, change: RepoSettingsChange): ForgeResult<RepoSettings> = unsupported()

    override suspend fun rename(token: String, repo: RepoId, name: String): ForgeResult<RepoId> = unsupported()

    override suspend fun delete(token: String, repo: RepoId): ForgeResult<RepoDeletion> = unsupported()
}

open class UnsupportedNotificationsApi : NotificationsApi {
    override suspend fun threads(token: String, ifModifiedSince: String?, maxPages: Int): ForgeResult<NotificationsSync> = unsupported()

    override suspend fun markRead(token: String, threadId: String): ForgeResult<Unit> = unsupported()

    override suspend fun markDone(token: String, threadId: String): ForgeResult<Unit> = unsupported()

    override suspend fun unsubscribe(token: String, threadId: String): ForgeResult<Unit> = unsupported()

    override suspend fun subjectStates(token: String, subjects: List<IssueRef>): ForgeResult<Map<IssueRef, SubjectState>> = unsupported()
}

open class UnsupportedSearchApi : SearchApi {
    override suspend fun repositories(token: String?, query: String, page: Int): ForgeResult<SearchPage<RepoSummary>> = unsupported()

    override suspend fun issues(token: String?, query: String, page: Int): ForgeResult<SearchPage<IssueSearchResult>> = unsupported()

    override suspend fun users(token: String?, query: String, page: Int): ForgeResult<SearchPage<UserSummary>> = unsupported()

    override suspend fun firstIssues(token: String?, repos: List<RepoId>): ForgeResult<List<IssueSearchResult>> = unsupported()

    override suspend fun topic(token: String?, topic: String, page: Int): ForgeResult<SearchPage<RepoSummary>> = unsupported()

    override suspend fun work(token: String, login: String, kind: WorkKind): ForgeResult<List<IssueSearchResult>> = unsupported()
}

open class UnsupportedStarApi : StarApi {
    override suspend fun starredStatus(token: String, repos: List<RepoId>): ForgeResult<Map<RepoId, Boolean>> = unsupported()

    override suspend fun setStarred(token: String, repo: RepoId, starred: Boolean): ForgeResult<Unit> = unsupported()
}

open class UnsupportedFeedApi : FeedApi {
    override suspend fun receivedEvents(token: String?, login: String, page: Int, ifModifiedSince: String?): ForgeResult<FeedPage> = unsupported()
}

open class UnsupportedUserApi : UserApi {
    override suspend fun user(token: String?, login: String): ForgeResult<UserProfile> = unsupported()

    override suspend fun repos(token: String?, login: String, mine: Boolean, organization: Boolean): ForgeResult<List<RepoSummary>> = unsupported()

    override suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>> = unsupported()

    override suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean> = unsupported()

    override suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit> = unsupported()
}
