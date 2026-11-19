package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.FeedPage
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.FeedEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueSearchResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.SearchPage
import fr.arthurbrugiere.forgeline.core.model.SearchScope
import fr.arthurbrugiere.forgeline.core.model.SearchSort
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedFeedApi
import fr.arthurbrugiere.forgeline.forge.common.UnsupportedSearchApi
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.tangled.actor.SearchActorsTypeaheadQueryParams
import org.tangled.feed.GetTimelineQueryParams
import org.tangled.feed.GetTimelineTimelineItemEventUnion
import org.tangled.repo.RepoViewBasic
import org.tangled.search.QueryHit
import org.tangled.search.QueryQueryParams
import org.tangled.search.QuerySort
import sh.christian.ozone.api.Nsid
import java.util.concurrent.ConcurrentHashMap

/**
 * Searching Tangled's index (`org.tangled.search.query`): its tickets, issues and pull requests together, and its
 * repositories by their manifests; people by the start of their handle (`org.tangled.actor.searchActorsTypeahead`,
 * which answers one page). Tangled keeps no topics to browse, no list of what is asked of someone, nor first issues.
 */
class TangledSearchApi internal constructor(
    private val xrpc: TangledXrpc,
    private val identity: TangledIdentity,
    private val conversations: TangledConversations,
) : UnsupportedSearchApi() {

    // The index orders by relevance or by when a record was made; repositories' stars and pushes aren't in it.
    override fun sorts(scope: SearchScope): Set<SearchSort> =
        if (scope == SearchScope.ISSUES) setOf(SearchSort.BEST_MATCH, SearchSort.NEWEST) else setOf(SearchSort.BEST_MATCH)

    override suspend fun repositories(token: String?, query: String, page: Int, sort: SearchSort): ForgeResult<SearchPage<RepoSummary>> = forgeCall {
        search(query, MANIFEST, page) { hits ->
            coroutineScope {
                hits.map { hit ->
                    async {
                        val uri = hit.uri.atUri
                        val record = hit.value.decodeAs<RepoRecordJson>()
                        val owner = (identity.handle(record.ownerDid(uri)) as? ForgeResult.Success)?.value ?: return@async null
                        RepoSummary(
                            id = RepoId(owner, record.name(uri), ForgeInstance.Tangled),
                            description = record.description?.takeIf { it.isNotBlank() },
                            language = null,
                            stars = 0,
                            forks = 0,
                            isFork = record.source != null,
                            updatedAt = instant(record.createdAt),
                            starsKnown = false,
                        )
                    }
                }.awaitAll().filterNotNull()
            }
        }
    }

    override suspend fun issues(token: String?, query: String, page: Int, sort: SearchSort): ForgeResult<SearchPage<IssueSearchResult>> = forgeCall {
        search(query, TICKET, page, if (sort == SearchSort.NEWEST) QuerySort.CreatedDesc else null) { hits ->
            coroutineScope {
                hits.groupBy { it.uri.atUri.authority }.map { (repoDid, tickets) ->
                    async {
                        val repo = conversations.repoId(repoDid) ?: return@async emptyList()
                        val rows = tickets.map { TangledConversations.TicketRow(it.uri.atUri, it.value, it.author?.did, it.state, it.commentCount ?: 0) }
                        conversations.summaries(repoDid, rows, pulls = null).map { IssueSearchResult(repo, it) }
                    }
                }.awaitAll().flatten()
            }
        }
    }

    override suspend fun users(token: String?, query: String, page: Int): ForgeResult<SearchPage<UserSummary>> = forgeCall {
        val words = query.trim().removePrefix("@")
        if (words.isEmpty() || page > 1) return@forgeCall ForgeResult.Success(SearchPage(emptyList(), 0, null))
        xrpc.index.searchActorsTypeahead(SearchActorsTypeaheadQueryParams(words.take(MAX_HANDLE), limit = PAGE.toLong())).toResult { answer ->
            answer.actors
        }.let { result ->
            when (result) {
                is ForgeResult.Failure -> result
                is ForgeResult.Success -> {
                    val users = coroutineScope {
                        result.value.map { actor ->
                            async { UserSummary(actor.handle.handle, identity.avatar(actor.did.did), isOrganization = false, forge = ForgeInstance.Tangled) }
                        }.awaitAll()
                    }
                    ForgeResult.Success(SearchPage(users, users.size, null))
                }
            }
        }
    }

    /** One [page] of the records of [collection] holding [query]'s words, as [read] makes them. */
    private suspend fun <T> search(query: String, collection: String, page: Int, sort: QuerySort? = null, read: suspend (List<QueryHit>) -> List<T>): ForgeResult<SearchPage<T>> {
        val words = query.trim()
        if (words.isEmpty()) return ForgeResult.Success(SearchPage(emptyList(), 0, null))
        val answer = when (
            val result = xrpc.index.query(
                QueryQueryParams(q = words, nsid = Nsid(collection), sort = sort, offset = ((page - 1) * PAGE).toLong(), limit = PAGE.toLong()),
            ).toResult { it }
        ) {
            is ForgeResult.Failure -> return result
            is ForgeResult.Success -> result.value
        }
        val hits = answer.hits.filter { it.nsid.nsid == collection }
        val total = answer.total?.toInt() ?: hits.size
        val more = answer.hits.size == PAGE && page * PAGE < total
        return ForgeResult.Success(SearchPage(read(hits), total, if (more) page + 1 else null))
    }

    private companion object {
        const val PAGE = 30
        const val MANIFEST = "org.tangled.repo.manifest"
        const val MAX_HANDLE = 200
    }
}

/**
 * The Feed, from Tangled's timeline (`org.tangled.feed.getTimeline`) as the person [login] sees it, of those they
 * follow only: who starred what, and the repositories made and forked. Follows have no row in the Feed, so they are
 * left out. The index answers the timeline of those followed only to them, signed in: each page is asked with a
 * service-auth token their own server signs for that one call ([TangledAccount.serviceToken]), as Tangled's web app
 * does. The timeline pages by a cursor, kept for each page once it was read.
 */
class TangledFeedApi internal constructor(
    private val xrpc: TangledXrpc,
    private val identity: TangledIdentity,
    private val account: TangledAccount,
    private val conversations: TangledConversations,
) : UnsupportedFeedApi() {
    private val cursors = ConcurrentHashMap<Pair<String, Int>, String>()

    override suspend fun receivedEvents(token: String?, login: String, page: Int, ifModifiedSince: String?): ForgeResult<FeedPage> = forgeCall {
        val viewer = when (val did = identity.did(login).then(::asDid)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        val cursor = if (page > 1) cursors[viewer.did to page] ?: return@forgeCall ForgeResult.Success(FeedPage(emptyList(), null)) else null
        val index = when (val signed = account.serviceToken(token ?: return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized), TANGLED_API_DID, TIMELINE)) {
            is ForgeResult.Failure -> return@forgeCall signed
            is ForgeResult.Success -> xrpc.service(TANGLED_API, signed.value)
        }
        val answer = when (
            val result = index.getTimeline(GetTimelineQueryParams(viewer = viewer, followingOnly = true, limit = PAGE.toLong(), cursor = cursor)).toResult { it }
        ) {
            is ForgeResult.Failure -> return@forgeCall result
            is ForgeResult.Success -> result.value
        }
        val events = coroutineScope { answer.feed.map { item -> async { event(item.event, item.eventAt.toJava()) } }.awaitAll().filterNotNull() }
        val next = answer.cursor?.takeIf { it.isNotEmpty() && answer.feed.isNotEmpty() }?.let { page + 1 }
        if (next != null) cursors[viewer.did to next] = answer.cursor
        ForgeResult.Success(FeedPage(events, next))
    }

    private suspend fun event(event: GetTimelineTimelineItemEventUnion, at: java.time.Instant): FeedEvent? = when (event) {
        is GetTimelineTimelineItemEventUnion.StarEvent -> {
            val star = event.value
            FeedEvent(
                id = star.uri.atUri,
                actor = conversations.user(star.actor.did.did, star.actor.handle.handle, star.actor.avatarCid?.cid),
                repo = star.repo.id(),
                action = FeedAction.Starred,
                createdAt = at,
            )
        }
        is GetTimelineTimelineItemEventUnion.RepoEvent -> {
            val made = event.value
            val owner = made.repo.owner
            val source = made.source
            FeedEvent(
                id = made.uri.atUri,
                actor = conversations.user(owner.did.did, owner.handle.handle, owner.avatarCid?.cid),
                repo = source?.id() ?: made.repo.id(),
                action = if (source != null) FeedAction.Forked(made.repo.id()) else FeedAction.CreatedRepo(made.repo.description?.ifBlank { null }),
                createdAt = at,
            )
        }
        else -> null
    }

    private fun RepoViewBasic.id(): RepoId = RepoId(owner.handle.handle, slug, ForgeInstance.Tangled)

    private companion object {
        const val PAGE = 50
        const val TIMELINE = "org.tangled.feed.getTimeline"
    }
}
