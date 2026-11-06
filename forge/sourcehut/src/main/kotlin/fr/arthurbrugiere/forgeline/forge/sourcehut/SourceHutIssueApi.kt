package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.apollographql.apollo.api.Optional
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.AssignUserMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.DeleteTicketMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.LabelTicketMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.PersonIdQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.SubmitCommentMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.SubmitTicketMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TicketEventsQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TicketQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TicketSubscribeMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TicketUnsubscribeMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TrackerLabelsQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TrackerQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.TrackerTicketsQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.UnassignUserMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.UnlabelTicketMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.UpdateTicketMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.UpdateTicketStatusMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.fragment.EventFields
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.fragment.TicketFields
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.fragment.TicketLabel
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.type.TicketResolution
import fr.arthurbrugiere.forgeline.forge.sourcehut.todo.type.TicketStatus
import io.ktor.client.HttpClient
import java.time.Instant

/** todo.sr.ht's GraphQL endpoint: trackers, their tickets, labels and events. */
internal const val TODO_SRHT_QUERY = "https://todo.sr.ht/query"

/**
 * A repository's tickets on todo.sr.ht: those of its owner's tracker of the same name, SourceHut's own convention
 * (~sircmpwn/hare's are todo.sr.ht/~sircmpwn/hare). A ticket's number is its id in the tracker. What the reader may do
 * is the tracker's ACL: whoever may triage labels, assigns and resolves; its owner deletes too. SourceHut keeps no
 * milestones, types, locks or pins, and a comment, once sent, can't be edited through the API.
 */
class SourceHutIssueApi(
    private val httpClient: HttpClient,
    private val forge: ForgeInstance = ForgeInstance.SourceHut,
) : IssueApi {

    override val actions: Set<ConversationAction> = setOf(
        ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.CLOSE_REASON, ConversationAction.DELETE,
    )

    private suspend fun <D : com.apollographql.apollo.api.Operation.Data> todo(token: String?, operation: com.apollographql.apollo.api.Operation<D>) =
        httpClient.srht(token, operation, TODO_SRHT_QUERY)

    /** The tracker named as [repo], with the reader's rights on it; a 404 where its owner keeps none. */
    internal suspend fun tracker(token: String?, repo: RepoId): ForgeResult<Tracker> = todo(token, TrackerQuery(repo.owner.username, repo.name)).then { data ->
        val tracker = data.user?.tracker ?: return@then notFound("tracker ${repo.fullName}")
        val owner = tracker.owner.entityName.canonicalName
        val acl = tracker.acl
        ForgeResult.Success(
            Tracker(
                id = tracker.id,
                isOwner = owner.equals(data.me.canonicalName, ignoreCase = true),
                mayTriage = acl?.triage == true,
                owner = owner,
                me = data.me.canonicalName,
            ),
        )
    }

    /** A tracker by its id, whether the reader owns it or may triage there. */
    internal data class Tracker(val id: Int, val isOwner: Boolean, val mayTriage: Boolean, val owner: String, val me: String)

    /**
     * The tickets [query] asks for, newest first. todo.sr.ht pages by cursor and filters nothing: open and resolved, and
     * the words, are told apart here, as many of its pages read as the page asked for needs.
     */
    internal suspend fun tickets(token: String?, repo: RepoId, query: fr.arthurbrugiere.forgeline.core.model.IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        val wanted = query.page * fr.arthurbrugiere.forgeline.core.model.IssueQuery.PAGE_SIZE
        val found = mutableListOf<TicketFields>()
        var cursor: String? = null
        for (page in 1..MAX_PAGES) {
            val read = when (val result = todo(token, TrackerTicketsQuery(repo.owner.username, repo.name, Optional.presentIfNotNull(cursor)))) {
                is ForgeResult.Failure -> return@forgeCall result
                is ForgeResult.Success -> result.value.user?.tracker?.tickets ?: return@forgeCall notFound("tracker ${repo.fullName}")
            }
            found += read.results.map { it.ticketFields }.filter { ticket ->
                (ticket.status != TicketStatus.RESOLVED) == query.open &&
                    (query.text.isBlank() || ticket.subject.contains(query.text.trim(), ignoreCase = true))
            }
            cursor = read.cursor
            if (cursor == null || found.size >= wanted) break
        }
        val newest = found.sortedByDescending { it.id }
        ForgeResult.Success(newest.drop(wanted - fr.arthurbrugiere.forgeline.core.model.IssueQuery.PAGE_SIZE).take(fr.arthurbrugiere.forgeline.core.model.IssueQuery.PAGE_SIZE).map { it.toSummary() })
    }

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = forgeCall {
        todo(token, TicketQuery(ref.repo.owner.username, ref.repo.name, ref.number)).then { data ->
            val ticket = data.user?.tracker?.ticket ?: return@then notFound("ticket ${ref.repo.fullName}#${ref.number}")
            ForgeResult.Success(ticket.ticketFields.toDetails(ref))
        }
    }

    /** Every event at once, oldest first: a ticket's events come by cursor, a page number can't reach the middle. */
    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = forgeCall {
        if (page > 1) return@forgeCall ForgeResult.Success(TimelinePage(emptyList(), null))
        val events = mutableListOf<EventFields>()
        var cursor: String? = null
        for (round in 1..MAX_PAGES) {
            val read = when (val result = todo(token, TicketEventsQuery(ref.repo.owner.username, ref.repo.name, ref.number, Optional.presentIfNotNull(cursor)))) {
                is ForgeResult.Failure -> return@forgeCall result
                is ForgeResult.Success -> result.value.user?.tracker?.ticket?.events ?: return@forgeCall notFound("ticket ${ref.repo.fullName}#${ref.number}")
            }
            events += read.results.map { it.eventFields }
            cursor = read.cursor ?: break
        }
        ForgeResult.Success(TimelinePage(events.flatMap { it.toItems(ref.repo) }.sortedBy { it.createdAt ?: Instant.MIN }, nextPage = null))
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = forgeCall {
        withTracker(token, ref.repo) { tracker ->
            todo(token, SubmitCommentMutation(tracker.id, ref.number, body)).then { data ->
                val comment = data.submitComment.eventFields.toItems(ref.repo).filterIsInstance<TimelineItem.Comment>().firstOrNull()
                comment?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Unreadable)
            }
        }
    }

    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = forgeCall {
        withTracker(token, ref.repo) { tracker -> todo(token, UpdateTicketMutation(tracker.id, ref.number, title, Optional.present(body.ifBlank { null }))).map { } }
    }

    /** SourceHut's tickets take no labels as they are opened: whoever may triage labels them after. */
    override suspend fun create(token: String, repo: RepoId, title: String, body: String, labels: List<String>): ForgeResult<IssueDetails> = forgeCall {
        withTracker(token, repo) { tracker ->
            todo(token, SubmitTicketMutation(tracker.id, title, Optional.present(body.ifBlank { null }))).map { data ->
                data.submitTicket.ticketFields.let { it.toDetails(IssueRef(repo, it.id, isPullRequest = false)) }
            }
        }
    }

    /**
     * Resolving takes a resolution: done is FIXED, not planned WONT_FIX, a duplicate DUPLICATE, none CLOSED. Reopening
     * puts it back as REPORTED, unresolved.
     */
    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> = forgeCall {
        withTracker(token, ref.repo) { tracker ->
            val (status, resolution) = if (open) {
                TicketStatus.REPORTED to TicketResolution.UNRESOLVED
            } else {
                TicketStatus.RESOLVED to when (reason) {
                    CloseReason.COMPLETED -> TicketResolution.FIXED
                    CloseReason.NOT_PLANNED -> TicketResolution.WONT_FIX
                    CloseReason.DUPLICATE -> TicketResolution.DUPLICATE
                    null -> TicketResolution.CLOSED
                }
            }
            todo(token, UpdateTicketStatusMutation(tracker.id, ref.number, status, Optional.present(resolution))).map { }
        }
    }

    /** Its owner administers the tracker; the ACL says who else triages there. */
    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = forgeCall {
        tracker(token, repo).map { tracker ->
            RepoRights(
                when {
                    tracker.isOwner -> RepoAccess.ADMIN
                    tracker.mayTriage -> RepoAccess.TRIAGE
                    else -> RepoAccess.NONE
                },
            )
        }
    }

    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = forgeCall { trackerLabels(token, repo).map { all -> all.map { it.toModel() } } }

    private suspend fun trackerLabels(token: String, repo: RepoId): ForgeResult<List<TicketLabel>> {
        val labels = mutableListOf<TicketLabel>()
        var cursor: String? = null
        for (page in 1..MAX_PAGES) {
            val read = when (val result = todo(token, TrackerLabelsQuery(repo.owner.username, repo.name, Optional.presentIfNotNull(cursor)))) {
                is ForgeResult.Failure -> return result
                is ForgeResult.Success -> result.value.user?.tracker?.labels ?: return notFound("tracker ${repo.fullName}")
            }
            labels += read.results.map { it.ticketLabel }
            cursor = read.cursor ?: break
        }
        return ForgeResult.Success(labels)
    }

    /** Each label put on or taken off by its id, against what the ticket wears now. */
    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = forgeCall {
        withTracker(token, ref.repo) { tracker ->
            val current = when (val read = issue(token, ref)) {
                is ForgeResult.Failure -> return@withTracker read
                is ForgeResult.Success -> read.value.labels.map { it.name }.toSet()
            }
            val labels = when (val all = trackerLabels(token, ref.repo)) {
                is ForgeResult.Failure -> return@withTracker all
                is ForgeResult.Success -> all.value.associateBy { it.name }
            }
            for (name in names.toSet() - current) {
                val label = labels[name] ?: return@withTracker notFound("label $name")
                (todo(token, LabelTicketMutation(tracker.id, ref.number, label.id)) as? ForgeResult.Failure)?.let { return@withTracker it }
            }
            for (name in current - names.toSet()) {
                val label = labels[name] ?: continue
                (todo(token, UnlabelTicketMutation(tracker.id, ref.number, label.id)) as? ForgeResult.Failure)?.let { return@withTracker it }
            }
            ForgeResult.Success(Unit)
        }
    }

    /** SourceHut lists nobody to assign: its owner, and the reader, can be; anyone else is typed by name on the web. */
    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = forgeCall {
        tracker(token, repo).map { tracker -> listOf(tracker.owner, tracker.me).distinct().map { ForgeUser(it, null, null) } }
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> = forgeCall {
        withTracker(token, ref.repo) { tracker ->
            val current = when (val read = issue(token, ref)) {
                is ForgeResult.Failure -> return@withTracker read
                is ForgeResult.Success -> read.value.assignees.map { it.login.canonical }.toSet()
            }
            val wanted = logins.map { it.canonical }.toSet()
            for ((login, assign) in (wanted - current).map { it to true } + (current - wanted).map { it to false }) {
                val id = when (val person = todo(token, PersonIdQuery(login.username))) {
                    is ForgeResult.Failure -> return@withTracker person
                    is ForgeResult.Success -> person.value.user?.id ?: return@withTracker notFound("person $login")
                }
                val done = if (assign) todo(token, AssignUserMutation(tracker.id, ref.number, id)) else todo(token, UnassignUserMutation(tracker.id, ref.number, id))
                (done as? ForgeResult.Failure)?.let { return@withTracker it }
            }
            ForgeResult.Success(Unit)
        }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = forgeCall {
        withTracker(token, ref.repo) { tracker -> todo(token, DeleteTicketMutation(tracker.id, ref.number)).map { } }
    }

    /** A subscription is the reader's own: the ticket says whether they have one. */
    override suspend fun isSubscribed(token: String, login: String, ref: IssueRef): ForgeResult<Boolean> = forgeCall {
        todo(token, TicketQuery(ref.repo.owner.username, ref.repo.name, ref.number)).then { data ->
            val ticket = data.user?.tracker?.ticket ?: return@then notFound("ticket ${ref.repo.fullName}#${ref.number}")
            ForgeResult.Success(ticket.subscription != null)
        }
    }

    override suspend fun setSubscribed(token: String, login: String, ref: IssueRef, subscribed: Boolean): ForgeResult<Unit> = forgeCall {
        withTracker(token, ref.repo) { tracker ->
            if (subscribed) todo(token, TicketSubscribeMutation(tracker.id, ref.number)).map { } else todo(token, TicketUnsubscribeMutation(tracker.id, ref.number)).map { }
        }
    }

    /** The mutations name a tracker by its id: it is asked first. */
    private suspend fun <T> withTracker(token: String, repo: RepoId, then: suspend (Tracker) -> ForgeResult<T>): ForgeResult<T> = when (val tracker = tracker(token, repo)) {
        is ForgeResult.Failure -> tracker
        is ForgeResult.Success -> then(tracker.value)
    }

    private fun TicketFields.toSummary() = IssueSummary(
        number = id,
        title = subject,
        state = if (status == TicketStatus.RESOLVED) IssueState.CLOSED else IssueState.OPEN,
        author = ForgeUser(submitter.entityName.canonicalName, null, null),
        comments = null,
        createdAt = instant(created) ?: Instant.EPOCH,
        labels = labels.map { it.ticketLabel.toModel() },
        isPullRequest = false,
        isDraft = false,
    )

    private fun TicketFields.toDetails(ref: IssueRef) = IssueDetails(
        ref = ref.copy(isPullRequest = false),
        title = subject,
        body = body,
        state = if (status == TicketStatus.RESOLVED) IssueState.CLOSED else IssueState.OPEN,
        stateReason = if (status == TicketStatus.RESOLVED) resolution.reason() else null,
        author = ForgeUser(submitter.entityName.canonicalName, null, null),
        labels = labels.map { it.ticketLabel.toModel() },
        createdAt = instant(created) ?: Instant.EPOCH,
        closedAt = null,
        comments = 0,
        reactions = emptyMap(),
        pullRequest = null,
        assignees = assignees.map { ForgeUser(it.entityName.canonicalName, null, null) },
    )

    /**
     * One event's changes as the conversation tells them: a comment (its latest wording only), resolving and reopening,
     * another status, a label, an assignment, a mention from another ticket. Its creation is the ticket itself, and a
     * person mentioned is noise, as on the other forges.
     */
    private fun EventFields.toItems(home: RepoId): List<TimelineItem> {
        val at = instant(created) ?: return emptyList()
        return changes.mapNotNull { change ->
            change.onComment?.let { comment ->
                if (comment.supersededBy != null) return@mapNotNull null
                return@mapNotNull TimelineItem.Comment(id.toLong(), comment.author.entityName.person(), comment.text, at, emptyMap())
            }
            change.onStatusChange?.let { status ->
                val who = status.editor.entityName.person()
                return@mapNotNull when {
                    status.newStatus == TicketStatus.RESOLVED && status.oldStatus != TicketStatus.RESOLVED ->
                        TimelineItem.StateChanged(StateChange.CLOSED, who, status.newResolution.reason(), at)
                    status.oldStatus == TicketStatus.RESOLVED && status.newStatus != TicketStatus.RESOLVED ->
                        TimelineItem.StateChanged(StateChange.REOPENED, who, null, at)
                    else -> TimelineItem.Event(ConversationEvent.STATUS_CHANGED, who, status.newStatus.named(), at)
                }
            }
            change.onLabelUpdate?.let { update ->
                return@mapNotNull TimelineItem.Labeled(change.eventType.rawValue == "LABEL_ADDED", update.label.ticketLabel.toModel(), update.labeler.entityName.person(), at)
            }
            change.onAssignment?.let { assignment ->
                val kind = if (change.eventType.rawValue == "ASSIGNED_USER") ConversationEvent.ASSIGNED else ConversationEvent.UNASSIGNED
                return@mapNotNull TimelineItem.Event(kind, assignment.assigner.entityName.person(), assignment.assignee.entityName.canonicalName, at)
            }
            change.onTicketMention?.let { mention ->
                val tracker = mention.mentioned.tracker
                val repo = RepoId(tracker.owner.entityName.canonicalName, tracker.name, forge)
                return@mapNotNull TimelineItem.CrossReferenced(IssueRef(repo, mention.mentioned.id, isPullRequest = false), mention.mentioned.subject, false, mention.author.entityName.person(), at)
            }
            null
        }
    }

    private companion object {
        /** How many pages of a cursor are followed at most: past that, the oldest are left out. */
        const val MAX_PAGES = 20
    }
}

private fun fr.arthurbrugiere.forgeline.forge.sourcehut.todo.fragment.EntityName.person() = ForgeUser(canonicalName, null, null)

/** A label as todo.sr.ht colours it: its background, written `#rrggbb`. */
private fun TicketLabel.toModel() = Label(name, backgroundColor.removePrefix("#").ifBlank { null })

/** Why a ticket was resolved, as the app reads close reasons: done, not planned, a duplicate; none for a plain CLOSED. */
private fun TicketResolution.reason(): String? = when (this) {
    TicketResolution.FIXED, TicketResolution.IMPLEMENTED -> "completed"
    TicketResolution.WONT_FIX, TicketResolution.BY_DESIGN, TicketResolution.INVALID, TicketResolution.NOT_OUR_BUG -> "not_planned"
    TicketResolution.DUPLICATE -> "duplicate"
    else -> null
}

/** A status as SourceHut's pages write it: "In progress". */
private fun TicketStatus.named(): String = rawValue.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
