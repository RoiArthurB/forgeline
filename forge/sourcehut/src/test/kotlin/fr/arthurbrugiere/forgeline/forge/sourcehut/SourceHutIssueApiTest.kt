package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** The answers follow todo.sr.ht's schema (api/graph/schema.graphqls, read 2026-11-06): no token here reads a tracker. */
class SourceHutIssueApiTest {
    private val scdoc = RepoId("~sircmpwn", "scdoc", ForgeInstance.SourceHut)
    private val ticket = IssueRef(scdoc, 21, isPullRequest = false)

    private fun <T> ForgeResult<T>.value(): T = if (this is ForgeResult.Success) value else error("$this")

    private fun api(vararg answers: Pair<String, (FakeSourceHut.Request) -> String>) = FakeSourceHut(answers.toList()).let { it to SourceHutIssueApi(it.client) }

    @Test
    fun a_repository_s_tickets_are_its_owner_s_tracker_of_the_same_name_open_ones_first_asked() = runTest {
        val (fake, api) = api(
            "query TrackerTickets(" to { request ->
                if (request.string("cursor") == null) tickets(listOf(ticketJson(21, "Crash on a long line"), ticketJson(20, "Fixed thing", resolved = true)), cursor = "page2")
                else tickets(listOf(ticketJson(6, "Old request")), cursor = null)
            },
        )

        val open = api.tickets(TOKEN, scdoc, IssueQuery()).value()

        assertThat(open.map { it.number to it.title }).containsExactly(21 to "Crash on a long line", 6 to "Old request").inOrder()
        assertThat(open.first().author).isEqualTo(ForgeUser("~reporter", null, null))
        assertThat(open.first().labels).containsExactly(Label("bug", "e5c07b"))
        assertThat(open.none { it.isPullRequest }).isTrue()
        // todo.sr.ht filters nothing: every page of the cursor was read, and the resolved one left out here.
        assertThat(fake.tickets.map { it.string("cursor") }).containsExactly(null, "page2").inOrder()
        assertThat(fake.tickets.first().string("owner")).isEqualTo("sircmpwn")
        assertThat(api.tickets(TOKEN, scdoc, IssueQuery(open = false)).value().map { it.number }).containsExactly(20)
        assertThat(api.tickets(TOKEN, scdoc, IssueQuery(text = "LONG")).value().map { it.number }).containsExactly(21)
    }

    @Test
    fun a_ticket_reads_as_an_issue() = runTest {
        val (_, api) = api("query Ticket(" to { _ -> inTracker(""""ticket":${ticketJson(21, "Crash on a long line", resolved = true, resolution = "WONT_FIX")}""") })

        val issue = api.issue(TOKEN, ticket).value()

        assertThat(issue.title).isEqualTo("Crash on a long line")
        assertThat(issue.body).isEqualTo("It crashes.")
        assertThat(issue.state).isEqualTo(IssueState.CLOSED)
        assertThat(issue.stateReason).isEqualTo("not_planned")
        assertThat(issue.assignees).containsExactly(ForgeUser("~sircmpwn", null, null))
        assertThat(issue.createdAt).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"))
        assertThat(issue.pullRequest).isNull()
    }

    @Test
    fun a_ticket_s_events_read_as_its_conversation() = runTest {
        val events = listOf(
            event(1, "2026-10-01T12:00:00Z", """{"__typename":"Created","eventType":"CREATED"}"""),
            event(2, "2026-10-01T13:00:00Z", """{"__typename":"Comment","eventType":"COMMENT","author":${person("~helper")},"text":"Old wording","supersededBy":{"text":"New wording"}}"""),
            event(3, "2026-10-01T13:05:00Z", """{"__typename":"Comment","eventType":"COMMENT","author":${person("~helper")},"text":"New wording","supersededBy":null}"""),
            event(
                4, "2026-10-02T09:00:00Z",
                """{"__typename":"LabelUpdate","eventType":"LABEL_ADDED","labeler":${person("~sircmpwn")},"label":{"__typename":"Label","id":3,"name":"bug","backgroundColor":"#e5c07b"}}""",
                """{"__typename":"Assignment","eventType":"ASSIGNED_USER","assigner":${person("~sircmpwn")},"assignee":${person("~sircmpwn")}}""",
                """{"__typename":"StatusChange","eventType":"STATUS_CHANGE","editor":${person("~sircmpwn")},"oldStatus":"REPORTED","newStatus":"IN_PROGRESS","newResolution":"UNRESOLVED"}""",
            ),
            event(5, "2026-10-03T09:00:00Z", """{"__typename":"StatusChange","eventType":"STATUS_CHANGE","editor":${person("~sircmpwn")},"oldStatus":"IN_PROGRESS","newStatus":"RESOLVED","newResolution":"FIXED"}"""),
            event(6, "2026-10-04T09:00:00Z", """{"__typename":"StatusChange","eventType":"STATUS_CHANGE","editor":${person("~reporter")},"oldStatus":"RESOLVED","newStatus":"REPORTED","newResolution":"UNRESOLVED"}"""),
            event(
                7, "2026-10-05T09:00:00Z",
                """{"__typename":"TicketMention","eventType":"TICKET_MENTIONED","author":${person("~other")},"mentioned":{"id":4,"subject":"Related","tracker":{"name":"hare","owner":${person("~sircmpwn")}}}}""",
                """{"__typename":"UserMention","eventType":"USER_MENTIONED","author":${person("~other")},"mentioned":${person("~sircmpwn")}}""",
            ),
        )
        val (fake, api) = api(
            // Newest first, over two pages: they read oldest first.
            "query TicketEvents(" to { request -> if (request.string("cursor") == null) eventPage(events.drop(3).reversed(), "more") else eventPage(events.take(3).reversed(), null) },
        )

        val page = api.timeline(TOKEN, ticket, 1).value()

        assertThat(page.nextPage).isNull()
        val items = page.items
        assertThat(items.map { it::class.simpleName }).containsExactly("Comment", "Labeled", "Event", "Event", "StateChanged", "StateChanged", "CrossReferenced").inOrder()
        assertThat((items[0] as TimelineItem.Comment).body).isEqualTo("New wording")
        assertThat((items[0] as TimelineItem.Comment).author?.login).isEqualTo("~helper")
        assertThat((items[1] as TimelineItem.Labeled).label).isEqualTo(Label("bug", "e5c07b"))
        assertThat((items[2] as TimelineItem.Event).let { it.event to it.subject }).isEqualTo(ConversationEvent.ASSIGNED to "~sircmpwn")
        assertThat((items[3] as TimelineItem.Event).let { it.event to it.subject }).isEqualTo(ConversationEvent.STATUS_CHANGED to "In progress")
        assertThat((items[4] as TimelineItem.StateChanged).let { it.change to it.stateReason }).isEqualTo(StateChange.CLOSED to "completed")
        assertThat((items[5] as TimelineItem.StateChanged).change).isEqualTo(StateChange.REOPENED)
        val mention = items[6] as TimelineItem.CrossReferenced
        assertThat(mention.source).isEqualTo(IssueRef(RepoId("~sircmpwn", "hare", ForgeInstance.SourceHut), 4, isPullRequest = false))
        assertThat(mention.sourceTitle).isEqualTo("Related")
        assertThat(fake.tickets.map { it.string("cursor") }).containsExactly(null, "more").inOrder()
        // There is no second page: every event came with the first.
        assertThat(api.timeline(TOKEN, ticket, 2).value().items).isEmpty()
    }

    @Test
    fun whoever_owns_the_tracker_administers_it_and_its_acl_says_who_triages() = runTest {
        fun rights(me: String, triage: Boolean) = api("query Tracker(" to { _ -> tracker(me = me, triage = triage) }).second

        assertThat(rights("~sircmpwn", triage = true).access(TOKEN, scdoc).value().access).isEqualTo(RepoAccess.ADMIN)
        assertThat(rights("~helper", triage = true).access(TOKEN, scdoc).value().access).isEqualTo(RepoAccess.TRIAGE)
        assertThat(rights("~passerby", triage = false).access(TOKEN, scdoc).value().access).isEqualTo(RepoAccess.NONE)
        assertThat(rights("~passerby", triage = false).actions).containsExactly(
            ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.CLOSE_REASON, ConversationAction.DELETE,
        )
    }

    @Test
    fun an_owner_without_a_tracker_named_as_the_repository_has_no_tickets() = runTest {
        val (_, api) = api("query Tracker(" to { _ -> """{"data":{"me":{"canonicalName":"~me"},"user":{"tracker":null}}}""" })

        assertThat(((api.tracker(TOKEN, scdoc) as ForgeResult.Failure).error as ForgeError.Http).status).isEqualTo(404)
    }

    @Test
    fun commenting_resolving_and_reopening_name_the_tracker_by_its_id() = runTest {
        val (fake, api) = api(
            "query Tracker(" to { _ -> tracker() },
            "mutation SubmitComment(" to { _ ->
                """{"data":{"submitComment":${event(9, "2026-10-06T09:00:00Z", """{"__typename":"Comment","eventType":"COMMENT","author":${person("~me")},"text":"On it","supersededBy":null}""")}}}"""
            },
            "mutation UpdateTicketStatus(" to { _ -> """{"data":{"updateTicketStatus":{"id":10}}}""" },
        )

        val comment = api.comment(TOKEN, ticket, "On it").value()
        api.setOpen(TOKEN, ticket, open = false, reason = CloseReason.NOT_PLANNED).value()
        api.setOpen(TOKEN, ticket, open = false, reason = null).value()
        api.setOpen(TOKEN, ticket, open = true).value()

        assertThat(comment.body).isEqualTo("On it")
        val sent = fake.tickets.filter { it.query.startsWith("mutation") }
        assertThat(sent.first().variables.toString()).isEqualTo("""{"tracker":42,"ticket":21,"text":"On it"}""")
        assertThat(sent.drop(1).map { it.string("status") to it.string("resolution") }).containsExactly(
            "RESOLVED" to "WONT_FIX", "RESOLVED" to "CLOSED", "REPORTED" to "UNRESOLVED",
        ).inOrder()
    }

    @Test
    fun labels_and_assignees_are_set_by_what_changes() = runTest {
        val (fake, api) = api(
            "query Tracker(" to { _ -> tracker() },
            "query Ticket(" to { _ -> inTracker(""""ticket":${ticketJson(21, "Crash")}""") },
            "query TrackerLabels(" to { _ -> inTracker(""""labels":{"results":[{"__typename":"Label","id":3,"name":"bug","backgroundColor":"#e5c07b"},{"__typename":"Label","id":4,"name":"easy","backgroundColor":"#98c379"}],"cursor":null}""") },
            "query PersonId(" to { request -> """{"data":{"user":{"id":${if (request.string("username") == "helper") 7 else 1}}}}""" },
            "mutation LabelTicket(" to { _ -> """{"data":{"labelTicket":{"id":11}}}""" },
            "mutation UnlabelTicket(" to { _ -> """{"data":{"unlabelTicket":{"id":12}}}""" },
            "mutation AssignUser(" to { _ -> """{"data":{"assignUser":{"id":13}}}""" },
            "mutation UnassignUser(" to { _ -> """{"data":{"unassignUser":{"id":14}}}""" },
        )

        assertThat(api.labels(TOKEN, scdoc).value().map { it.name }).containsExactly("bug", "easy").inOrder()
        api.setLabels(TOKEN, ticket, listOf("easy")).value()
        api.setAssignees(TOKEN, ticket, listOf("~helper")).value()

        val sent = fake.tickets.filter { it.query.startsWith("mutation") }
        assertThat(sent.map { it.query.substringBefore('(') to (it.string("label") ?: it.string("user")) }).containsExactly(
            "mutation LabelTicket" to "4", "mutation UnlabelTicket" to "3", "mutation AssignUser" to "7", "mutation UnassignUser" to "1",
        ).inOrder()
    }

    @Test
    fun opening_a_ticket_sends_its_subject_and_text() = runTest {
        val (fake, api) = api(
            "query Tracker(" to { _ -> tracker() },
            "mutation SubmitTicket(" to { _ -> """{"data":{"submitTicket":${ticketJson(22, "New one")}}}""" },
        )

        val opened = api.create(TOKEN, scdoc, "New one", "").value()

        assertThat(opened.ref).isEqualTo(IssueRef(scdoc, 22, isPullRequest = false))
        assertThat(fake.tickets.last().variables.toString()).isEqualTo("""{"tracker":42,"subject":"New one","body":null}""")
    }

    @Test
    fun whether_the_reader_follows_a_ticket_is_its_subscription() = runTest {
        val subscribed = """"subscription":{"id":5}"""
        val (fake, api) = api(
            "query Tracker(" to { _ -> tracker() },
            "query Ticket(" to { _ -> inTracker(""""ticket":${ticketJson(21, "Crash", extra = ",$subscribed")}""") },
            "mutation TicketUnsubscribe(" to { _ -> """{"data":{"ticketUnsubscribe":{"id":5}}}""" },
        )

        assertThat(api.isSubscribed(TOKEN, "~me", ticket).value()).isTrue()
        api.setSubscribed(TOKEN, "~me", ticket, false).value()
        assertThat(fake.tickets.last().query).startsWith("mutation TicketUnsubscribe(")
    }

    @Test
    fun nothing_is_asked_without_a_token() = runTest {
        val (fake, api) = api()

        assertThat(api.issue(null, ticket)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(fake.requests).isEmpty()
    }

    private fun person(name: String) = """{"__typename":"User","canonicalName":"$name"}"""

    private fun tracker(me: String = "~sircmpwn", triage: Boolean = true) =
        """{"data":{"me":{"canonicalName":"$me"},"user":{"tracker":{"id":42,"name":"scdoc","owner":${person("~sircmpwn")},""" +
            """"acl":{"__typename":"TrackerACL","browse":true,"submit":true,"comment":true,"edit":$triage,"triage":$triage}}}}}"""

    private fun inTracker(fields: String) = """{"data":{"user":{"tracker":{"id":42,$fields}}}}"""

    private fun tickets(list: List<String>, cursor: String?) =
        inTracker(""""tickets":{"results":[${list.joinToString(",")}],"cursor":${cursor?.let { "\"$it\"" } ?: "null"}}""")

    private fun ticketJson(id: Int, subject: String, resolved: Boolean = false, resolution: String = "FIXED", extra: String = "") =
        """{"__typename":"Ticket","id":$id,"created":"2026-10-01T12:00:00Z","subject":"$subject","body":"It crashes.","status":"${if (resolved) "RESOLVED" else "REPORTED"}",""" +
            """"resolution":"${if (resolved) resolution else "UNRESOLVED"}","submitter":${person("~reporter")},""" +
            """"labels":[{"__typename":"Label","id":3,"name":"bug","backgroundColor":"#e5c07b"}],"assignees":[${person("~sircmpwn")}]$extra}"""

    private fun event(id: Int, at: String, vararg changes: String) = """{"__typename":"Event","id":$id,"created":"$at","changes":[${changes.joinToString(",")}]}"""

    private fun eventPage(list: List<String>, cursor: String?) =
        """{"data":{"user":{"tracker":{"ticket":{"events":{"results":[${list.joinToString(",")}],"cursor":${cursor?.let { "\"$it\"" } ?: "null"}}}}}}}"""
}

/** A tracker named scdoc, owned by ~sircmpwn, read by ~sircmpwn. */
internal val TRACKER = """{"data":{"me":{"canonicalName":"~sircmpwn"},"user":{"tracker":{"id":42,"name":"scdoc","owner":{"__typename":"User","canonicalName":"~sircmpwn"},""" +
    """"acl":null}}}}"""
