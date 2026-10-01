package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.FeedApi
import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A Forgejo account's Feed. Forgejo's own feed only holds your actions, the repositories you watch and your
 * organizations: the people you follow aren't in it. So the first page also reads each followed person's activity, at
 * most [FOLLOWED_PER_REFRESH] of them per refresh, those read longest ago first, and a full round takes a few
 * refreshes. The same action seen in two feeds shows once. Stars never show: Forgejo records none.
 */
class ForgejoFeedApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance,
    private val clock: Clock = Clock.systemUTC(),
) : FeedApi {

    /** When each followed person's activity was last read, for the rotation. */
    private val lastRead = ConcurrentHashMap<String, Long>()

    override suspend fun receivedEvents(token: String?, login: String, page: Int, ifModifiedSince: String?): ForgeResult<FeedPage> = forgejoCall {
        coroutineScope {
            // Followed people only join the newest page: older pages follow your own feed back in time. Both are asked
            // at once, not one after the other.
            val followed = if (page == 1 && token != null) async { followedActivity(token) } else null
            val own = httpClient.forgejoApi(forge, token, "users", login, "activities", "feeds", query = mapOf("limit" to "$PAGE_SIZE", "page" to page.toString()))
            if (own.status != HttpStatusCode.OK) {
                followed?.cancel()
                return@coroutineScope own.failure()
            }
            feedPage(own, page, followed?.await().orEmpty())
        }
    }

    private suspend fun feedPage(own: io.ktor.client.statement.HttpResponse, page: Int, followed: List<ActivityJson>): ForgeResult<FeedPage> {
        val activities = own.body<List<ActivityJson>>().toMutableList()
        val hasMore = own.nextPage() != null || (own.totalCount()?.let { it > page * PAGE_SIZE } ?: (activities.size == PAGE_SIZE))
        activities += followed
        val events = activities.distinctBy { it.sameAction() }.mapNotNull { it.toModel() }.sortedByDescending { it.createdAt }
        return ForgeResult.Success(FeedPage(events, nextPage = (page + 1).takeIf { hasMore }))
    }

    private suspend fun followedActivity(token: String): List<ActivityJson> {
        val following = httpClient.forgejoApi(forge, token, "user", "following", query = mapOf("limit" to "50"))
        if (following.status != HttpStatusCode.OK) return emptyList()
        val people = following.body<List<UserJson>>().map { it.login }
            .sortedBy { lastRead[it] ?: 0L }
            .take(FOLLOWED_PER_REFRESH)
        val gate = Semaphore(CONCURRENCY)
        return coroutineScope {
            people.map { person ->
                async {
                    gate.withPermit {
                        val response = httpClient.forgejoApi(
                            forge, token, "users", person, "activities", "feeds",
                            query = mapOf("only-performed-by" to "true", "limit" to "$PER_PERSON"),
                        )
                        lastRead[person] = clock.millis()
                        if (response.status == HttpStatusCode.OK) response.body<List<ActivityJson>>() else emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Forgejo's feed says nothing about starred repositories either, and it has no query for "latest release of each":
     * the stars are listed, then each repository that could have a recent release is asked for its latest one, all at
     * once. Repositories with no release, archived, or untouched since [since] are skipped unasked.
     */
    override suspend fun starredActivity(token: String, since: Instant): ForgeResult<List<FeedEvent>> = forgejoCall {
        val starred = mutableListOf<RepoJson>()
        for (page in 1..MAX_STARRED_PAGES) {
            val response = httpClient.forgejoApi(forge, token, "user", "starred", query = mapOf("limit" to "$STARRED_PAGE_SIZE", "page" to page.toString()))
            if (response.status != HttpStatusCode.OK) {
                if (page == 1) return@forgejoCall response.failure()
                break
            }
            val repos = response.body<List<RepoJson>>()
            starred += repos
            if (repos.size < STARRED_PAGE_SIZE) break
        }
        val candidates = starred.filter { repo ->
            !repo.archived && repo.releaseCounter != 0 && (instant(repo.updatedAt)?.let { it >= since } ?: true)
        }
        val gate = Semaphore(RELEASES_AT_ONCE)
        val events = coroutineScope {
            candidates.map { repo ->
                async {
                    gate.withPermit {
                        val response = httpClient.forgejoApi(forge, token, "repos", repo.owner.login, repo.name, "releases", query = mapOf("limit" to "1"))
                        if (response.status != HttpStatusCode.OK) return@withPermit null
                        val release = response.body<List<ReleaseJson>>().firstOrNull()?.takeIf { !it.draft } ?: return@withPermit null
                        val at = instant(release.publishedAt)?.takeIf { it >= since } ?: return@withPermit null
                        val id = repo.id(forge)
                        FeedEvent(
                            "starred-release:${id.fullName}:${release.tag}",
                            // A release made by automation has no author: the repository's owner signs it.
                            (release.author ?: repo.owner).toModel(),
                            id,
                            FeedAction.Released(release.tag, release.name?.ifBlank { null }, release.prerelease),
                            at,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
        ForgeResult.Success(events)
    }

    private fun ActivityJson.toModel(): FeedEvent? {
        val repo = repo ?: return null
        val actor = actUser?.toModel() ?: return null
        val id = RepoId(repo.owner.login, repo.name, forge)
        // Issue and pull request activity carries a JSON array: the number, then the title (or the comment's text).
        val parts = runCatching { Json.parseToJsonElement(content.orEmpty()) as? JsonArray }.getOrNull()?.map { it.jsonPrimitive.content }
        val number = parts?.getOrNull(0)?.toIntOrNull()
        val title = parts?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val ref = refName.orEmpty()
        val action: FeedAction = when (opType) {
            "create_repo" -> if (repo.fork && repo.parent != null) FeedAction.Forked(id) else FeedAction.CreatedRepo(repo.description?.ifBlank { null })
            "create_issue" -> number?.let { FeedAction.Issue(IssueAction.OPENED, it, title.orEmpty()) }
            "close_issue" -> number?.let { FeedAction.Issue(IssueAction.CLOSED, it, title.orEmpty()) }
            "reopen_issue" -> number?.let { FeedAction.Issue(IssueAction.REOPENED, it, title.orEmpty()) }
            "create_pull_request" -> number?.let { FeedAction.PullRequest(PullRequestAction.OPENED, it, title) }
            "close_pull_request" -> number?.let { FeedAction.PullRequest(PullRequestAction.CLOSED, it, title) }
            "reopen_pull_request" -> number?.let { FeedAction.PullRequest(PullRequestAction.REOPENED, it, title) }
            "merge_pull_request", "auto_merge_pull_request" -> number?.let { FeedAction.PullRequest(PullRequestAction.MERGED, it, title) }
            "comment_issue" -> number?.let { FeedAction.Commented(it, null, isPullRequest = false) }
            "comment_pull" -> number?.let { FeedAction.Commented(it, null, isPullRequest = true) }
            "approve_pull_request" -> number?.let { FeedAction.Reviewed(it, ReviewState.APPROVED) }
            "reject_pull_request" -> number?.let { FeedAction.Reviewed(it, ReviewState.CHANGES_REQUESTED) }
            "pull_review_dismissed" -> number?.let { FeedAction.Reviewed(it, ReviewState.DISMISSED) }
            "commit_repo" -> FeedAction.Pushed(ref.removePrefix("refs/heads/"))
            "push_tag" -> FeedAction.Branch(ref.removePrefix("refs/tags/"), isTag = true, deleted = false)
            "delete_tag" -> FeedAction.Branch(ref.removePrefix("refs/tags/"), isTag = true, deleted = true)
            "delete_branch" -> FeedAction.Branch(ref.removePrefix("refs/heads/"), isTag = false, deleted = true)
            "publish_release" -> FeedAction.Released(ref.removePrefix("refs/tags/").ifBlank { title.orEmpty() }, null, prerelease = false)
            // Mirror syncs, renames, transfers, stars and watches aren't Feed news.
            else -> null
        } ?: return null
        // A fork is news about the repository it came from.
        val subject = if (action is FeedAction.Forked) repo.parent!!.let { RepoId(it.owner.login, it.name, forge) } else id
        return FeedEvent(this.id.toString(), actor, subject, action, instant(created) ?: return null)
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val PER_PERSON = 20
        const val FOLLOWED_PER_REFRESH = 20
        const val STARRED_PAGE_SIZE = 50

        /** 500 starred repositories at most. */
        const val MAX_STARRED_PAGES = 10

        /** Latest-release requests in flight; the HTTP engine caps what really goes out per forge. */
        const val RELEASES_AT_ONCE = 16
        /**
         * Followed people read at once: all of a refresh's, in one wave. Far from the forge each feed takes seconds
         * (Codeberg: about 3 s, measured from Vietnam on 2026-10-01), so a second wave doubled the wait. The HTTP
         * engine still caps what really goes out per forge.
         */
        const val CONCURRENCY = FOLLOWED_PER_REFRESH
    }
}

@Serializable
private data class ActivityRepoJson(
    val name: String,
    val owner: UserJson,
    val description: String? = null,
    val fork: Boolean = false,
    val parent: ActivityRepoJson? = null,
)

@Serializable
private data class ActivityJson(
    val id: Long,
    @SerialName("op_type") val opType: String,
    @SerialName("act_user") val actUser: UserJson? = null,
    @SerialName("act_user_id") val actUserId: Long = 0,
    @SerialName("repo_id") val repoId: Long = 0,
    val repo: ActivityRepoJson? = null,
    @SerialName("ref_name") val refName: String? = null,
    val content: String? = null,
    val created: String? = null,
) {
    /** Forgejo stores one row per receiver, so one action has several ids: this is what makes it the same. */
    fun sameAction() = listOf(actUserId, opType, repoId, refName, content, created)
}
