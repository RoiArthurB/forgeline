package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.tangled.feed.ListReactionsQueryParams
import org.tangled.label.ListDefinitionsQueryParams
import org.tangled.repo.GetRepoByRepoDidQueryParams
import org.tangled.repo.ListCollaboratorsQueryParams
import org.tangled.repo.ListIssuesOrder
import org.tangled.repo.ListIssuesQueryParams
import org.tangled.repo.ListIssuesState
import org.tangled.repo.ListPullsOrder
import org.tangled.repo.ListPullsQueryParams
import org.tangled.repo.ListPullsStatus
import org.tangled.search.QueryKind
import org.tangled.search.QueryQueryParams
import sh.christian.ozone.api.AtUri
import sh.christian.ozone.api.model.JsonContent
import sh.christian.ozone.api.model.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A repository as its conversations need it: its own DID, its owner's, and its knot, where they are written. */
internal data class TicketRepo(val id: RepoId, val did: String, val ownerDid: String, val knot: String?, val spindle: String?, val defaultBranch: String) {
    /** The knot, for a write: Unsupported for a repository the index names none for. */
    val writableKnot: ForgeResult<String> get() = knot?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unsupported)
}

/**
 * What Tangled's issues and pull requests share, read from its index: whose they are, the labels they wear, the
 * reactions they got, a pull request's patches. People are named by their DID in tickets and comments: each is
 * turned into a handle (and a picture) once, and remembered.
 */
internal class TangledConversations(
    private val xrpc: TangledXrpc,
    private val identity: TangledIdentity,
    private val repos: TangledRepoApi,
) {
    private val users = ConcurrentHashMap<String, ForgeUser>()
    private val labelSets = ConcurrentHashMap<String, Map<String, Label>>()
    private val repoIds = ConcurrentHashMap<String, RepoId>()

    suspend fun repo(id: RepoId): ForgeResult<TicketRepo> = when (val found = repos.lookUp(id)) {
        is ForgeResult.Failure -> found
        is ForgeResult.Success -> found.value.repoDid?.let {
            ForgeResult.Success(TicketRepo(id, it, found.value.ownerDid, found.value.knot, found.value.record.spindle, found.value.git?.defaultBranch ?: "main"))
        } ?: ForgeResult.Failure(ForgeError.Unsupported)
    }

    /** The repository whose own DID is [repoDid], by its owner's handle and its name; null where the index can't say. */
    suspend fun repoId(repoDid: String): RepoId? {
        repoIds[repoDid]?.let { return it }
        val (uri, record) = (
            forgeCall {
                asDid(repoDid).then { did -> xrpc.index.getRepoByRepoDid(GetRepoByRepoDidQueryParams(did)).toResult { it.uri.atUri to it.value.decodeAs<RepoRecordJson>() } }
            } as? ForgeResult.Success
            )?.value ?: return null
        val ownerDid = record.ownerDid(uri)
        val owner = (identity.handle(ownerDid) as? ForgeResult.Success)?.value ?: return null
        return RepoId(owner, record.name(uri), ForgeInstance.Tangled).also { repoIds[repoDid] = it }
    }

    /** The person [did], by their handle, with their picture. */
    suspend fun user(did: String): ForgeUser {
        users[did]?.let { return it }
        val handle = (identity.handle(did) as? ForgeResult.Success)?.value ?: did
        return ForgeUser(handle, null, identity.avatar(did)).also { users[did] = it }
    }

    /** As [user], when the index already said who it is (a profile view, with their picture's blob). */
    suspend fun user(did: String, handle: String, avatarCid: String?): ForgeUser {
        users[did]?.let { return it }
        identity.remember(handle, did)
        return ForgeUser(handle, null, identity.avatarOf(did, avatarCid)).also { users[did] = it }
    }

    /** The DID of the person [login] (a handle, or a DID already). */
    suspend fun did(login: String): ForgeResult<String> = identity.did(login)

    suspend fun users(dids: List<String>): List<ForgeUser> = coroutineScope { dids.map { async { user(it) } }.awaitAll() }

    /** [repoDid]'s labels by their record key: their names and colours, which tickets only name by that key. */
    suspend fun labels(repoDid: String, fresh: Boolean = false): ForgeResult<Map<String, Label>> {
        if (!fresh) labelSets[repoDid]?.let { return ForgeResult.Success(it) }
        val did = when (val valid = asDid(repoDid)) {
            is ForgeResult.Failure -> return valid
            is ForgeResult.Success -> valid.value
        }
        val all = mutableMapOf<String, Label>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val answer = xrpc.index.listDefinitions(ListDefinitionsQueryParams(did.did, cursor, limit = 100)).toResult { it }) {
                is ForgeResult.Failure -> return answer
                is ForgeResult.Success -> answer.value
            }
            for (item in page.items) {
                val definition = item.value.decodeAs<LabelDefinitionJson>()
                all[item.uri.atUri.recordKey] = Label(definition.name, definition.color?.removePrefix("#"))
            }
            cursor = page.cursor?.takeIf { it.isNotBlank() && page.items.isNotEmpty() } ?: return ForgeResult.Success(all.also { labelSets[repoDid] = it })
        }
        return ForgeResult.Success(all.also { labelSets[repoDid] = it })
    }

    fun forget(repoDid: String) {
        labelSets.remove(repoDid)
    }

    /** The labels [keys] name, among [repoDid]'s; one whose definition is gone is left out, as Tangled's web app does. */
    suspend fun labelsOf(repoDid: String, keys: List<String>): List<Label> {
        if (keys.isEmpty()) return emptyList()
        val known = (labels(repoDid) as? ForgeResult.Success)?.value.orEmpty()
        return keys.mapNotNull { known[it] }
    }

    /** Who may write to [repo] beyond its owner: its collaborators, by DID. */
    suspend fun collaborators(repo: TicketRepo): ForgeResult<List<String>> {
        val did = when (val valid = asDid(repo.did)) {
            is ForgeResult.Failure -> return valid
            is ForgeResult.Success -> valid.value
        }
        return xrpc.index.listCollaborators(ListCollaboratorsQueryParams(did, limit = 100)).toResult { page -> page.items.map { it.subject.did } }
    }

    /** The reactions given to [uri] (a ticket, a comment): each one's record, by emoji and giver. */
    suspend fun reactions(uri: String): ForgeResult<List<Pair<String, ReactionRecordJson>>> {
        val all = mutableListOf<Pair<String, ReactionRecordJson>>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val page = when (val answer = xrpc.index.listReactions(ListReactionsQueryParams(AtUri(uri), cursor, limit = 100)).toResult { it }) {
                is ForgeResult.Failure -> return answer
                is ForgeResult.Success -> answer.value
            }
            all += page.items.map { it.uri.atUri to it.value.decodeAs<ReactionRecordJson>() }
            cursor = page.cursor?.takeIf { it.isNotBlank() && page.items.isNotEmpty() } ?: return ForgeResult.Success(all)
        }
        return ForgeResult.Success(all)
    }

    /** How many of each reaction [uri] got; none where they can't be read, which only leaves the counts out. */
    suspend fun reactionCounts(uri: String): Map<Reaction, Int> =
        (forgeCall { reactions(uri) } as? ForgeResult.Success)?.value.orEmpty().counts()

    fun List<Pair<String, ReactionRecordJson>>.counts(): Map<Reaction, Int> =
        mapNotNull { reactionOf(it.second.reaction) }.groupingBy { it }.eachCount()

    /** The patches of the pull request [ticket], oldest first: the last is what it would merge. */
    suspend fun patches(ticket: String): ForgeResult<List<PatchItemJson>> = forgeCall {
        // No lexicon describes it yet: Tangled's web app calls it (web/src/lib/api/records.ts, read 2026-11-17).
        val response = xrpc.plain.get("xrpc/org.tangled.temp.review.listPatches") {
            parameter("ticket", ticket)
            parameter("order", "asc")
            parameter("limit", 500)
        }
        response.toResult { ForgeJson.decodeFromString<PatchListJson>(bodyAsText()).items }
    }

    /** [repo]'s issues or pull requests as [query] asks. */
    suspend fun list(repo: TicketRepo, query: IssueQuery, pulls: Boolean): ForgeResult<List<IssueSummary>> = forgeCall {
        if (query.text.isNotBlank()) return@forgeCall search(repo, query, pulls)
        val did = when (val valid = asDid(repo.did)) {
            is ForgeResult.Failure -> return@forgeCall valid
            is ForgeResult.Success -> valid.value
        }
        val offset = ((query.page - 1) * IssueQuery.PAGE_SIZE).toLong()
        val limit = IssueQuery.PAGE_SIZE.toLong()
        val rows: ForgeResult<List<TicketRow>> = if (!pulls) {
            xrpc.index.listIssues(
                ListIssuesQueryParams(did, state = if (query.open) ListIssuesState.Open else ListIssuesState.Closed, offset = offset, limit = limit, order = ListIssuesOrder.Desc),
            ).toResult { page -> page.items.map { TicketRow(it.uri.atUri, it.value, it.author?.did, it.state.value, it.commentCount) } }
        } else {
            // Closed pull requests are those closed and those merged: one page of each, so that none is skipped. The
            // index calls a merged one `completed` (since 2026-11-18), and refuses the lexicon's `merged`.
            val statuses = if (query.open) listOf(ListPullsStatus.Open) else listOf(ListPullsStatus.Closed, ListPullsStatus.Completed)
            val pages = coroutineScope {
                statuses.map { status ->
                    async {
                        xrpc.index.listPulls(ListPullsQueryParams(did, status = status, offset = offset, limit = limit, order = ListPullsOrder.Desc))
                            .toResult { page -> page.items.map { TicketRow(it.uri.atUri, it.value, it.author?.did, it.state.value, it.commentCount) } }
                    }
                }.awaitAll()
            }
            pages.firstOrNull { it is ForgeResult.Failure } ?: ForgeResult.Success(pages.flatMap { (it as ForgeResult.Success).value })
        }
        when (rows) {
            is ForgeResult.Failure -> rows
            is ForgeResult.Success -> ForgeResult.Success(summaries(repo.did, rows.value, pulls).sortedByDescending { it.createdAt })
        }
    }

    /** The tickets holding [query]'s words, of [repo]'s issues or pull requests, open or not as it asks. */
    private suspend fun search(repo: TicketRepo, query: IssueQuery, pulls: Boolean): ForgeResult<List<IssueSummary>> {
        val did = when (val valid = asDid(repo.did)) {
            is ForgeResult.Failure -> return valid
            is ForgeResult.Success -> valid.value
        }
        val offset = ((query.page - 1) * IssueQuery.PAGE_SIZE).toLong()
        return when (
            val hits = xrpc.index.query(
                QueryQueryParams(q = query.text.trim(), repo = did, kind = if (pulls) QueryKind.Pull else QueryKind.Issue, offset = offset, limit = IssueQuery.PAGE_SIZE.toLong()),
            ).toResult { answer -> answer.hits.filter { it.nsid.nsid == TICKET }.map { TicketRow(it.uri.atUri, it.value, it.author?.did, it.state, it.commentCount ?: 0) } }
        ) {
            is ForgeResult.Failure -> hits
            // The search has no state of its own to filter by: each hit says where it stands.
            is ForgeResult.Success -> ForgeResult.Success(summaries(repo.did, hits.value, pulls).filter { (it.state == IssueState.OPEN) == query.open })
        }
    }

    /** A ticket as the index lists it: its record, who opened it, where it stands, how many comments it has. */
    data class TicketRow(val uri: String, val value: JsonContent, val author: String?, val state: String?, val comments: Long)

    suspend fun summaries(repoDid: String, rows: List<TicketRow>, pulls: Boolean?): List<IssueSummary> = coroutineScope {
        rows.mapNotNull { row ->
            val number = row.uri.recordKey.toIntOrNull() ?: return@mapNotNull null
            val ticket = row.value.decodeAs<TicketRecordJson>()
            val isPull = pulls ?: (ticket.targetBranch != null)
            async {
                IssueSummary(
                    number = number,
                    title = ticket.title?.text.orEmpty(),
                    state = ticketState(row.state ?: ticket.state, isPull),
                    author = row.author?.let { user(it) },
                    comments = row.comments.toInt(),
                    createdAt = instant(ticket.createdAt) ?: Instant.EPOCH,
                    labels = labelsOf(repoDid, ticket.tangledLabels.map { it.key }),
                    isPullRequest = isPull,
                    isDraft = false,
                )
            }
        }.awaitAll()
    }

    private companion object {
        const val MAX_PAGES = 10
    }
}

/** An ozone time as Java's. */
internal fun Timestamp.toJava(): Instant = Instant.parse(toString())
