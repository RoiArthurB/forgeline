package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tangled.feed.CommentView
import org.tangled.feed.CommentViewBodyUnion
import org.tangled.feed.ListCommentsOrder
import org.tangled.feed.ListCommentsQueryParams
import org.tangled.track.GetTicketViewQueryParams
import org.tangled.track.TicketViewDetailed
import org.tangled.track.TicketViewDetailedBodyUnion
import org.tangled.track.TicketViewDetailedTangledLabelUnion
import org.tangled.track.TicketViewDetailedTitleUnion
import sh.christian.ozone.api.AtUri
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tangled's issues and pull requests, which are its tickets ([TICKET]): read from its index, written to the
 * repository's knot ([TangledKnot]) as Tangled's web app does (`web/src/lib/api/ticket.ts`, `comment.ts`,
 * `reaction.ts`, `labels.ts` in tangled.org/core, read 2026-11-17).
 *
 * A comment is an `org.tangled.feed.comment` record, kept on the knot under a TID, which is its id here; it names
 * the ticket it is on by address and version, so the ticket is read first. Closing an issue as done marks it
 * `completed`, as not planned `closed`. Labels are the repository's own definitions, named on a ticket by their
 * record key. What Tangled doesn't keep (milestones, locking, pinning, a timeline of who closed or labelled what) is
 * Unsupported, as [IssueApi] says.
 */
class TangledIssueApi internal constructor(
    private val xrpc: TangledXrpc,
    private val account: TangledAccount,
    private val knot: TangledKnot,
    private val conversations: TangledConversations,
    private val clock: Clock = Clock.systemUTC(),
) : IssueApi {

    override val actions: Set<ConversationAction> = setOf(ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.CLOSE_REASON)

    override val issueOnly: Set<ConversationAction> = setOf(ConversationAction.CLOSE_REASON)

    override val editsComments: Boolean get() = true

    override val reacts: Boolean get() = true

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = forgeCall {
        val repo = when (val found = conversations.repo(ref.repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val uri = ticketUri(repo.did, ref.number)
        val view = when (val ticket = view(uri)) {
            is ForgeResult.Failure -> return@forgeCall ticket
            is ForgeResult.Success -> ticket.value
        }
        coroutineScope {
            val isPull = view.targetBranch != null
            val author = async { conversations.user(view.author.did.did, view.author.handle.handle, view.author.avatarCid?.cid) }
            val labels = async { conversations.labelsOf(repo.did, view.labelKeys) }
            val assignees = async { conversations.users(view.assignees.orEmpty().map { it.did }) }
            val reactions = async { conversations.reactionCounts(uri) }
            val comments = async { commentCount(uri) }
            val pull = async { if (isPull) pullInfo(repo, uri, view) else null }
            ForgeResult.Success(
                IssueDetails(
                    ref = ref.copy(isPullRequest = isPull),
                    title = view.titleText,
                    body = view.bodyText?.takeIf { it.isNotBlank() },
                    state = ticketState(view.state, isPull),
                    stateReason = if (isPull) null else stateReason(view.state),
                    author = author.await(),
                    labels = labels.await(),
                    createdAt = view.createdAt.toJava(),
                    closedAt = null,
                    comments = comments.await(),
                    reactions = reactions.await(),
                    pullRequest = pull.await(),
                    assignees = assignees.await(),
                ),
            )
        }
    }

    /** What a pull request's latest patch holds: where it goes, where it comes from, how much it changes. */
    private suspend fun pullInfo(repo: TicketRepo, uri: String, view: TicketViewDetailed): PullRequestInfo {
        val patch = (conversations.patches(uri) as? ForgeResult.Success)?.value?.lastOrNull()?.value
        val stats = patch?.let { TangledChanges.stats(xrpc, repo, it) }
        return PullRequestInfo(
            isDraft = false,
            isMerged = ticketState(view.state, isPullRequest = true) == IssueState.MERGED,
            baseRef = view.targetBranch.orEmpty(),
            headRef = patch?.source?.branch.orEmpty(),
            additions = stats?.insertions ?: 0,
            deletions = stats?.deletions ?: 0,
            changedFiles = stats?.files ?: 0,
            commits = stats?.commits ?: 0,
        )
    }

    override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = forgeCall {
        when (val repo = conversations.repo(ref.repo)) {
            is ForgeResult.Failure -> repo
            is ForgeResult.Success -> view(ticketUri(repo.value.did, ref.number)).map { it.titleText }
        }
    }

    private suspend fun view(uri: String): ForgeResult<TicketViewDetailed> = xrpc.index.getTicketView(GetTicketViewQueryParams(AtUri(uri))).toResult { it }

    private suspend fun commentCount(uri: String): Int =
        (xrpc.index.listComments(ListCommentsQueryParams(AtUri(uri), limit = 1)).toResult { it.total } as? ForgeResult.Success)?.value?.toInt() ?: 0

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = forgeCall {
        val repo = when (val found = conversations.repo(ref.repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val uri = ticketUri(repo.did, ref.number)
        val answer = when (
            val comments = xrpc.index.listComments(
                ListCommentsQueryParams(AtUri(uri), offset = ((page - 1) * PAGE).toLong(), limit = PAGE.toLong(), order = ListCommentsOrder.Asc),
            ).toResult { it }
        ) {
            is ForgeResult.Failure -> return@forgeCall comments
            is ForgeResult.Success -> comments.value
        }
        val items = coroutineScope { answer.items.map { async { comment(it) } }.awaitAll() }.filterNotNull()
        val total = answer.total?.toInt()
        val more = if (total != null) page * PAGE < total else answer.items.size == PAGE
        ForgeResult.Success(TimelinePage(items, if (more) page + 1 else null, total?.let { (it + PAGE - 1) / PAGE }))
    }

    private suspend fun comment(view: CommentView): TimelineItem.Comment? {
        val id = Tid.decode(view.uri.atUri.recordKey) ?: return null
        // A comment kept on the knot is addressed under the repository: the knot names who wrote it as its editor.
        val author = (view.editor?.did ?: view.uri.atUri.authority.takeUnless { it == view.subject.uri.atUri.authority })?.let { conversations.user(it) }
        return TimelineItem.Comment(
            id = id,
            author = author,
            body = (view.body as? CommentViewBodyUnion.OrgTangledMarkupMarkdown)?.value?.text.orEmpty(),
            createdAt = view.createdAt.toJava(),
            reactions = conversations.reactionCounts(view.uri.atUri),
        )
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = forgeCall {
        val (repo, host) = when (val target = writable(ref.repo)) {
            is ForgeResult.Failure -> return@forgeCall target
            is ForgeResult.Success -> target.value
        }
        // A comment names the version of the ticket it answers: the knot refuses an older one.
        val ticket = when (val current = knot.read(host, ticketUri(repo.did, ref.number))) {
            is ForgeResult.Failure -> return@forgeCall current
            is ForgeResult.Success -> current.value.ref
        }
        val now = now()
        val written = knot.create(
            token,
            host,
            repo.did,
            COMMENT,
            buildJsonObject {
                put("\$type", COMMENT)
                putJsonObject("subject") {
                    put("\$type", "com.atproto.repo.strongRef")
                    put("uri", ticket.uri)
                    put("cid", ticket.cid)
                }
                putJsonObject("body") {
                    put("\$type", "org.tangled.markup.markdown")
                    put("text", body)
                }
                put("createdAt", now.toString())
            },
        )
        when (written) {
            is ForgeResult.Failure -> written
            is ForgeResult.Success -> {
                val me = (account.did(token) as? ForgeResult.Success)?.value?.let { conversations.user(it) }
                ForgeResult.Success(TimelineItem.Comment(Tid.decode(written.value.uri.recordKey) ?: 0, me, body, now, emptyMap()))
            }
        }
    }

    override suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = forgeCall {
        val (repo, host) = when (val target = writable(ref.repo)) {
            is ForgeResult.Failure -> return@forgeCall target
            is ForgeResult.Success -> target.value
        }
        knot.update(token, host, commentUri(repo.did, commentId)) { current ->
            val markdown = (current["body"] as? JsonObject).orEmpty()
            current.with("body", JsonObject(markdown + ("\$type" to JsonPrimitive("org.tangled.markup.markdown")) + ("text" to JsonPrimitive(body))))
        }.map { }
    }

    override suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = forgeCall {
        when (val target = writable(ref.repo)) {
            is ForgeResult.Failure -> target
            is ForgeResult.Success -> knot.delete(token, target.value.second, commentUri(target.value.first.did, commentId))
        }
    }

    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = updateTicket(token, ref) { current ->
        val withTitle = current.with("title", inlineMarkdown(title))
        // The knot refuses an empty body: a blank one is left out.
        if (body.isBlank()) JsonObject(withTitle - "body") else withTitle.with("body", markdown(body, current["body"] as? JsonObject))
    }

    override suspend fun toggleReaction(token: String, login: String, ref: IssueRef, commentId: Long?, reaction: Reaction): ForgeResult<Map<Reaction, Int>> = forgeCall {
        val (repo, host) = when (val target = writable(ref.repo)) {
            is ForgeResult.Failure -> return@forgeCall target
            is ForgeResult.Success -> target.value
        }
        val me = when (val did = account.did(token)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        val subject = commentId?.let { commentUri(repo.did, it) } ?: ticketUri(repo.did, ref.number)
        val given = when (val all = conversations.reactions(subject)) {
            is ForgeResult.Failure -> return@forgeCall all
            is ForgeResult.Success -> all.value
        }
        val emoji = REACTION_EMOJI.getValue(reaction)
        val mine = given.firstOrNull { (_, record) -> record.editor == me && reactionOf(record.reaction) == reaction }
        val counts = with(conversations) { given.counts() }.toMutableMap()
        if (mine != null) {
            when (val deleted = knot.delete(token, host, mine.first)) {
                is ForgeResult.Failure -> return@forgeCall deleted
                is ForgeResult.Success -> counts[reaction] = (counts[reaction] ?: 1) - 1
            }
        } else {
            val target = when (val current = knot.read(host, subject)) {
                is ForgeResult.Failure -> return@forgeCall current
                is ForgeResult.Success -> current.value.ref
            }
            val written = knot.create(
                token,
                host,
                repo.did,
                REACTION,
                buildJsonObject {
                    put("\$type", REACTION)
                    putJsonObject("subject") {
                        put("uri", target.uri)
                        put("cid", target.cid)
                    }
                    put("reaction", emoji)
                    put("createdAt", now().toString())
                },
            )
            when (written) {
                is ForgeResult.Failure -> return@forgeCall written
                is ForgeResult.Success -> counts[reaction] = (counts[reaction] ?: 0) + 1
            }
        }
        ForgeResult.Success(counts.filterValues { it > 0 })
    }

    override suspend fun create(token: String, repo: RepoId, title: String, body: String, labels: List<String>): ForgeResult<IssueDetails> = forgeCall {
        val (target, host) = when (val found = writable(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val known = (conversations.labels(target.did) as? ForgeResult.Success)?.value.orEmpty()
        val keys = labels.mapNotNull { name -> known.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.key }
        val createdAt = now()
        val written = when (val ticket = createTicket(token, host, target.did, title, body, null, keys, createdAt)) {
            is ForgeResult.Failure -> return@forgeCall ticket
            is ForgeResult.Success -> ticket.value
        }
        val number = written.uri.recordKey.toIntOrNull() ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        val me = (account.did(token) as? ForgeResult.Success)?.value?.let { conversations.user(it) }
        ForgeResult.Success(
            IssueDetails(
                ref = IssueRef(repo, number, isPullRequest = false),
                title = title,
                body = body.takeIf { it.isNotBlank() },
                state = IssueState.OPEN,
                stateReason = null,
                author = me,
                labels = keys.mapNotNull { known[it] },
                createdAt = createdAt,
                closedAt = null,
                comments = 0,
                reactions = emptyMap(),
                pullRequest = null,
            ),
        )
    }

    /** Makes a ticket in [repoDid] on its knot at [host]: an issue, or a pull request into [targetBranch]. */
    internal suspend fun createTicket(
        token: String,
        host: String,
        repoDid: String,
        title: String,
        body: String,
        targetBranch: String?,
        labelKeys: List<String> = emptyList(),
        createdAt: Instant = now(),
    ): ForgeResult<StrongRef> = knot.create(
        token,
        host,
        repoDid,
        TICKET,
        buildJsonObject {
            put("\$type", TICKET)
            put("title", inlineMarkdown(title))
            if (body.isNotBlank()) put("body", markdown(body, null))
            put("state", "open")
            targetBranch?.let { put("targetBranch", it) }
            if (labelKeys.isNotEmpty()) put("tangledLabels", labelTokens(labelKeys))
            put("createdAt", createdAt.toString())
        },
    )

    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> = updateTicket(token, ref) { current ->
        val isPull = current["targetBranch"] != null
        val state = when {
            open -> "open"
            isPull -> "closed"
            reason == CloseReason.NOT_PLANNED || reason == CloseReason.DUPLICATE -> "closed"
            else -> "completed"
        }
        current.with("state", JsonPrimitive(state))
    }

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = forgeCall {
        val target = when (val found = conversations.repo(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val me = when (val did = account.did(token)) {
            is ForgeResult.Failure -> return@forgeCall did
            is ForgeResult.Success -> did.value
        }
        // As Tangled's web app reckons it (web/src/lib/api/access.ts): the owner administers, collaborators push.
        val access = when {
            me == target.ownerDid -> RepoAccess.ADMIN
            (conversations.collaborators(target) as? ForgeResult.Success)?.value?.contains(me) == true -> RepoAccess.WRITE
            else -> RepoAccess.NONE
        }
        ForgeResult.Success(RepoRights(access))
    }

    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = forgeCall {
        when (val target = conversations.repo(repo)) {
            is ForgeResult.Failure -> target
            is ForgeResult.Success -> conversations.labels(target.value.did, fresh = true).map { it.values.sortedBy { label -> label.name.lowercase() } }
        }
    }

    override suspend fun createLabel(token: String, repo: RepoId, name: String, color: String): ForgeResult<Label> = forgeCall {
        val (target, host) = when (val found = writable(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val hex = "#" + color.removePrefix("#").uppercase()
        val written = knot.create(
            token,
            host,
            target.did,
            LABEL_DEFINITION,
            buildJsonObject {
                put("\$type", LABEL_DEFINITION)
                put("name", name)
                put("color", hex)
                put("createdAt", now().toString())
            },
            rkey = labelKey(name),
        )
        conversations.forget(target.did)
        written.map { Label(name, hex.removePrefix("#")) }
    }

    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = forgeCall {
        val repo = when (val found = conversations.repo(ref.repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val known = when (val all = conversations.labels(repo.did, fresh = true)) {
            is ForgeResult.Failure -> return@forgeCall all
            is ForgeResult.Success -> all.value
        }
        val keys = names.mapNotNull { name -> known.entries.firstOrNull { it.value.name == name }?.key }
        updateTicket(token, ref) { current -> if (keys.isEmpty()) JsonObject(current - "tangledLabels") else current.with("tangledLabels", labelTokens(keys)) }
    }

    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = forgeCall {
        val target = when (val found = conversations.repo(repo)) {
            is ForgeResult.Failure -> return@forgeCall found
            is ForgeResult.Success -> found.value
        }
        val collaborators = (conversations.collaborators(target) as? ForgeResult.Success)?.value.orEmpty()
        ForgeResult.Success(conversations.users((listOf(target.ownerDid) + collaborators).distinct()))
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> = forgeCall {
        val dids = mutableListOf<String>()
        for (login in logins) {
            when (val did = conversations.did(login)) {
                is ForgeResult.Failure -> return@forgeCall did
                is ForgeResult.Success -> dids += did.value
            }
        }
        updateTicket(token, ref) { current ->
            if (dids.isEmpty()) JsonObject(current - "assignees") else current.with("assignees", JsonArray(dids.distinct().map(::JsonPrimitive)))
        }
    }

    /** Rewrites the ticket [ref] with what [change] makes of it, as its knot keeps it now. */
    private suspend fun updateTicket(token: String, ref: IssueRef, change: (JsonObject) -> JsonObject): ForgeResult<Unit> = forgeCall {
        when (val target = writable(ref.repo)) {
            is ForgeResult.Failure -> target
            is ForgeResult.Success -> knot.update(token, target.value.second, ticketUri(target.value.first.did, ref.number), change).map { }
        }
    }

    /** [repo], with the knot its conversations are written to. */
    private suspend fun writable(repo: RepoId): ForgeResult<Pair<TicketRepo, String>> = when (val found = conversations.repo(repo)) {
        is ForgeResult.Failure -> found
        is ForgeResult.Success -> found.value.writableKnot.map { found.value to it }
    }

    private fun commentUri(repoDid: String, commentId: Long): String = "at://$repoDid/$COMMENT/${Tid.encode(commentId)}"

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)

    internal companion object {
        /** Comments a page: as many as other forges give. */
        const val PAGE = 50

        fun inlineMarkdown(text: String): JsonObject = buildJsonObject {
            put("\$type", "org.tangled.markup.markdown#inline")
            put("text", text)
        }

        /** [text] as a Markdown body, keeping what else [current] held (the pictures it names). */
        fun markdown(text: String, current: JsonObject?): JsonObject =
            JsonObject(current.orEmpty() + ("\$type" to JsonPrimitive("org.tangled.markup.markdown")) + ("text" to JsonPrimitive(text)))

        fun labelTokens(keys: List<String>): JsonArray = buildJsonArray {
            for (key in keys) {
                add(
                    buildJsonObject {
                        put("\$type", "org.tangled.label.defs#labelToken")
                        put("key", key)
                    },
                )
            }
        }

        fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

        private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    }
}

internal val TicketViewDetailed.titleText: String get() = (title as? TicketViewDetailedTitleUnion.Inline)?.value?.text.orEmpty()

internal val TicketViewDetailed.bodyText: String? get() = (body as? TicketViewDetailedBodyUnion.OrgTangledMarkupMarkdown)?.value?.text

internal val TicketViewDetailed.labelKeys: List<String>
    get() = tangledLabels.orEmpty().mapNotNull { (it as? TicketViewDetailedTangledLabelUnion.LabelToken)?.value?.key?.rkey }
