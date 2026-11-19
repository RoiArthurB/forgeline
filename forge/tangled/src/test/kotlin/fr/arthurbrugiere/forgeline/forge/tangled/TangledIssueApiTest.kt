package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * Tangled's issues, read from its index and written to the repository's knot. The answers are api.tangled.org's own,
 * captured 2026-11-17 from tangled.org/core (`src/test/resources/tangled`).
 */
class TangledIssueApiTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private val now = Instant.parse("2026-11-17T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)

    private val me = "did:plc:meme7t2adsemcrrd4snkeqli"
    private val token = run {
        val enc = Base64.getUrlEncoder().withoutPadding()
        enc.encodeToString("""{"alg":"ES256K"}""".toByteArray()) + "." +
            enc.encodeToString("""{"scope":"com.atproto.appPass","sub":"$me","iat":${now.epochSecond},"exp":${now.epochSecond + 7_200}}""".toByteArray()) + ".c2ln"
    }

    /** The people the fixtures name, by their DID documents. */
    private val people = mapOf(
        me to "me.example",
        OWNER_DID to "tangled.org",
        BOLTLESS to "boltless.me",
        ETI to "eti.tf",
    )

    private fun read(fake: Map<String, (HttpRequestData) -> String?> = emptyMap()) = FakeTangled(
        mapOf(
            "com.atproto.identity.resolveHandle" to resolved,
            "org.tangled.temp.repo.getRepoByName" to coreManifest,
            "org.tangled.label.listDefinitions" to { fixture("listDefinitions.json") },
        ) + people.map { (did, handle) -> "plc.directory/$did" to didDocument(handle, if (did == me) "https://pds.example/" else "https://tngl.sh") } + fake,
    )

    private class Apis(val issues: TangledIssueApi, val repos: TangledRepoApi)

    private fun apis(fake: FakeTangled): Apis {
        val identity = TangledIdentity(fake.client, fake.xrpc)
        val account = TangledAccount(fake.xrpc, identity, clock)
        val repos = TangledRepoApi(fake.xrpc, identity)
        val knot = TangledKnot(fake.xrpc, account, identity, clock)
        return Apis(TangledIssueApi(fake.xrpc, account, knot, repos.conversations, clock), repos)
    }

    @Test
    fun a_repository_s_issues_are_its_tickets_without_a_target_branch_newest_first() = runTest {
        val fake = read(mapOf("org.tangled.repo.listIssues" to { fixture("listIssues.json") }))

        val issues = apis(fake).repos.issues(null, core, IssueQuery(open = true)).value()

        assertThat(issues.map { it.number }).containsExactly(4786, 4783, 4780).inOrder()
        val first = issues.first()
        assertThat(first.title).isEqualTo("respect editorconfig on file render (tab size)")
        assertThat(first.state).isEqualTo(IssueState.OPEN)
        assertThat(first.author?.login).isEqualTo("boltless.me")
        assertThat(first.isPullRequest).isFalse()
        assertThat(issues.last().comments).isEqualTo(6)
        assertThat(fake.asked).contains("org.tangled.repo.listIssues?limit=${IssueQuery.PAGE_SIZE}&offset=0&order=desc&state=open&subject=$REPO_DID")
    }

    @Test
    fun the_repository_now_says_it_lists_its_conversations() = runTest {
        val fake = read()

        val details = apis(fake).repos.repo(null, core).value()

        assertThat(details.listsConversations).isTrue()
        assertThat(details.hasIssues).isTrue()
    }

    @Test
    fun closed_pull_requests_are_those_closed_and_those_merged() = runTest {
        // The index calls a merged pull request `completed`, and refuses the `merged` its lexicon names (2026-11-18).
        val fake = read(mapOf("org.tangled.repo.listPulls" to { r -> if (r.url.parameters["status"] == "completed") fixture("listPulls.json") else """{"items":[]}""" }))

        val pulls = apis(fake).repos.pullRequests(null, core, IssueQuery(open = false)).value()

        assertThat(pulls.map { it.number }).containsExactly(4785, 4784, 4782).inOrder()
        assertThat(pulls.all { it.isPullRequest }).isTrue()
        assertThat(fake.asked.filter { it.startsWith("org.tangled.repo.listPulls") }.map { it.substringAfter("status=").substringBefore('&') })
            .containsExactly("closed", "completed")
    }

    @Test
    fun a_ticket_s_labels_are_named_by_the_repository_s_definitions() = runTest {
        val labelled = fixture("listIssues.json").replaceFirst(""""state":"open","title"""", """"state":"open","tangledLabels":[{"key":"performance"}],"title"""")
        val fake = read(mapOf("org.tangled.repo.listIssues" to { labelled }))

        val issue = apis(fake).repos.issues(null, core, IssueQuery(open = true)).value().first()

        assertThat(issue.labels.map { it.name to it.color }).containsExactly("performance" to "10B981")
    }

    @Test
    fun words_to_find_are_searched_for_among_the_repository_s_issues() = runTest {
        val fake = read(mapOf("org.tangled.search.query" to { fixture("search_tickets.json") }))

        val found = apis(fake).repos.issues(null, core, IssueQuery(open = false, text = "editorconfig")).value()

        // The search can't filter by state: each hit says where it stands, completed being closed for an issue.
        assertThat(found.map { it.number }).contains(3085)
        assertThat(found.all { it.state != IssueState.OPEN }).isTrue()
        assertThat(fake.asked.single { it.startsWith("org.tangled.search.query") }).contains("kind=issue")
    }

    @Test
    fun an_issue_is_read_from_its_ticket_view_by_its_number() = runTest {
        val fake = read(
            mapOf(
                "org.tangled.track.getTicketView" to { fixture("getTicketView_issue.json") },
                "org.tangled.feed.listComments" to { fixture("listComments.json") },
                "org.tangled.feed.listReactions" to { fixture("listReactions.json") },
            ),
        )

        val issue = apis(fake).issues.issue(null, IssueRef(core, 4780)).value()

        assertThat(issue.title).isEqualTo("PR diff view with 100+ commits")
        assertThat(issue.body).startsWith("Currently API call limits to 100")
        assertThat(issue.state).isEqualTo(IssueState.OPEN)
        assertThat(issue.author?.login).isEqualTo("boltless.me")
        assertThat(issue.ref.isPullRequest).isFalse()
        assertThat(issue.comments).isEqualTo(6)
        assertThat(issue.reactions).containsExactly(Reaction.EYES, 1)
        assertThat(issue.createdAt).isEqualTo(Instant.parse("2026-11-16T11:37:53Z"))
        assertThat(fake.asked).contains("org.tangled.track.getTicketView?uri=at://$REPO_DID/org.tangled.track.ticket/4780")
    }

    @Test
    fun comments_are_read_oldest_first_by_who_wrote_them_and_keep_their_key_as_id() = runTest {
        val fake = read(mapOf("org.tangled.feed.listComments" to { fixture("listComments.json") }))

        val page = apis(fake).issues.timeline(null, IssueRef(core, 4780), 1).value()

        val comments = page.items.filterIsInstance<TimelineItem.Comment>()
        assertThat(comments.map { it.author?.login }).containsExactly("eti.tf", "boltless.me", "eti.tf").inOrder()
        assertThat(comments.first().body).startsWith("we could have a \"Show all\" button")
        assertThat(comments.first().createdAt).isEqualTo(Instant.parse("2026-11-16T11:45:00Z"))
        assertThat(Tid.encode(comments.first().id)).isEqualTo("3n2ghbgtk3equ")
    }

    /** The knot answers the writes; the person's own server signs them for it and keeps their attestation. */
    private fun writes(extra: Map<String, (HttpRequestData) -> String?> = emptyMap()) = read(
        mapOf<String, (HttpRequestData) -> String?>(
            "com.atproto.server.getServiceAuth" to { """{"token":"service-jwt"}""" },
            "com.atproto.repo.getRecord" to { r ->
                val rkey = r.url.parameters["rkey"]
                """{"uri":"at://$REPO_DID/${r.url.parameters["collection"]}/$rkey","cid":"bafycurrent",
                    "value":{"${'$'}type":"org.tangled.track.ticket","title":{"text":"PR diff view with 100+ commits"},"state":"open","x-tngl-editor":"$BOLTLESS"}}"""
            },
            "com.atproto.repo.createRecord" to { r ->
                if (r.url.host == "pds.example") """{"uri":"at://$me/org.tangled.graph.attestation/3att","cid":"bafyatt"}""" else """{"uri":"at://$REPO_DID/org.tangled.feed.comment/3n2jaaaaaaaaa","cid":"bafynew"}"""
            },
            "com.atproto.repo.putRecord" to { """{"uri":"at://$REPO_DID/org.tangled.track.ticket/4780","cid":"bafynext"}""" },
            "com.atproto.server.getSession" to { """{"did":"$me","handle":"me.example"}""" },
        ) + extra,
    )

    @Test
    fun a_comment_is_written_to_the_knot_naming_the_ticket_s_current_version() = runTest {
        val fake = writes()

        val comment = apis(fake).issues.comment(token, IssueRef(core, 4780), "Looks good").value()

        val auth = fake.to("com.atproto.server.getServiceAuth").first()
        assertThat(auth.url.host).isEqualTo("pds.example")
        assertThat(auth.url.parameters["aud"]).isEqualTo(REPO_DID)
        assertThat(auth.url.parameters["lxm"]).isEqualTo("com.atproto.repo.createRecord")
        val sent = fake.to("com.atproto.repo.createRecord").first { it.url.host == "knot1.tangled.sh" }
        assertThat(sent.headers[HttpHeaders.Authorization]).isEqualTo("Bearer service-jwt")
        assertThat(sent.text).contains(""""collection":"org.tangled.feed.comment"""")
        assertThat(sent.text).contains(""""uri":"at://$REPO_DID/org.tangled.track.ticket/4780","cid":"bafycurrent"""")
        assertThat(sent.text).contains(""""text":"Looks good"""")
        // What was written is attested on the person's own server.
        val attestation = fake.to("com.atproto.repo.createRecord").single { it.url.host == "pds.example" }
        assertThat(attestation.text).contains(""""collection":"org.tangled.graph.attestation"""")
        assertThat(attestation.text).contains(""""uri":"at://$REPO_DID/org.tangled.feed.comment/3n2jaaaaaaaaa"""")
        assertThat(comment.body).isEqualTo("Looks good")
        assertThat(comment.createdAt).isEqualTo(now)
        assertThat(Tid.encode(comment.id)).isEqualTo("3n2jaaaaaaaaa")
    }

    @Test
    fun a_reaction_is_a_record_on_the_knot_and_taking_it_back_deletes_it() = runTest {
        val mine = fixture("listReactions.json").replace(ETI, me)
        val given = writes(mapOf("org.tangled.feed.listReactions" to { fixture("listReactions.json") }))
        val taken = writes(mapOf("org.tangled.feed.listReactions" to { mine }, "com.atproto.repo.deleteRecord" to { "{}" }))

        val added = apis(given).issues.toggleReaction(token, "me.example", IssueRef(core, 4786), null, Reaction.EYES).value()
        val removed = apis(taken).issues.toggleReaction(token, "me.example", IssueRef(core, 4786), null, Reaction.EYES).value()

        assertThat(added).containsExactly(Reaction.EYES, 2)
        val sent = given.to("com.atproto.repo.createRecord").first { it.url.host == "knot1.tangled.sh" }
        assertThat(sent.text).contains(""""collection":"org.tangled.feed.reaction"""")
        assertThat(sent.text).contains(""""reaction":"👀"""")
        assertThat(removed).isEmpty()
        assertThat(taken.to("com.atproto.repo.deleteRecord").single().text).contains(""""rkey":"3n2iuklafc46n"""")
    }

    @Test
    fun closing_an_issue_as_not_planned_rewrites_the_ticket_it_still_is() = runTest {
        val fake = writes()

        apis(fake).issues.setOpen(token, IssueRef(core, 4780), open = false, reason = CloseReason.NOT_PLANNED).value()

        val sent = fake.to("com.atproto.repo.putRecord").single()
        assertThat(sent.url.host).isEqualTo("knot1.tangled.sh")
        assertThat(sent.text).contains(""""state":"closed"""")
        assertThat(sent.text).contains(""""swapRecord":"bafycurrent"""")
        // Who wrote the version read is the knot's to say, not part of the record.
        assertThat(sent.text).doesNotContain("x-tngl-editor")
    }

    @Test
    fun an_issue_closed_as_done_is_completed() = runTest {
        val fake = writes()

        apis(fake).issues.setOpen(token, IssueRef(core, 4780), open = false, reason = CloseReason.COMPLETED).value()

        assertThat(fake.to("com.atproto.repo.putRecord").single().text).contains(""""state":"completed"""")
    }

    @Test
    fun a_ticket_changed_in_between_is_a_conflict() = runTest {
        val stale = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.temp.repo.getRepoByName" to coreManifest,
                "plc.directory/$me" to didDocument("me.example", "https://pds.example/"),
                "com.atproto.server.getServiceAuth" to { """{"token":"service-jwt"}""" },
                "com.atproto.repo.getRecord" to { """{"uri":"at://$REPO_DID/org.tangled.track.ticket/4780","cid":"bafycurrent","value":{"state":"open"}}""" },
            ),
            // As an AT Protocol server refuses a swap: the record is no longer the version named.
            status = { method -> if (method == "com.atproto.repo.putRecord") 400 else null },
            failure = { """{"error":"InvalidSwap","message":"Record was at bafyother"}""" },
        )

        val result = apis(stale).issues.setOpen(token, IssueRef(core, 4780), open = false, reason = null)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Conflict))
    }

    @Test
    fun a_label_is_defined_under_a_key_made_of_its_name() = runTest {
        val fake = writes(mapOf("com.atproto.repo.createRecord" to { """{"uri":"at://$REPO_DID/org.tangled.label.definition/good-first-issue","cid":"bafylabel"}""" }))

        val label = apis(fake).issues.createLabel(token, core, "Good first issue", "7057ff").value()

        val sent = fake.to("com.atproto.repo.createRecord").first { it.url.host == "knot1.tangled.sh" }
        assertThat(sent.text).contains(""""rkey":"good-first-issue"""")
        assertThat(sent.text).contains(""""color":"#7057FF"""")
        assertThat(label.name).isEqualTo("Good first issue")
    }

    @Test
    fun a_new_issue_is_a_ticket_on_the_knot_naming_its_labels_by_their_keys() = runTest {
        val fake = writes(
            mapOf(
                "com.atproto.repo.createRecord" to { r ->
                    if (r.url.host == "pds.example") """{"uri":"at://$me/org.tangled.graph.attestation/3att","cid":"bafyatt"}""" else """{"uri":"at://$REPO_DID/org.tangled.track.ticket/4790","cid":"bafynew"}"""
                },
            ),
        )

        val issue = apis(fake).issues.create(token, core, "Tabs render as 8 spaces", "See editorconfig", labels = listOf("Performance", "unknown")).value()

        val sent = fake.to("com.atproto.repo.createRecord").first { it.url.host == "knot1.tangled.sh" }
        assertThat(sent.text).contains(""""collection":"org.tangled.track.ticket"""")
        assertThat(sent.text).contains(""""title":{"${'$'}type":"org.tangled.markup.markdown#inline","text":"Tabs render as 8 spaces"}""")
        assertThat(sent.text).contains(""""text":"See editorconfig"""")
        assertThat(sent.text).contains(""""state":"open"""")
        // A label is named by its key, matched whatever its case; one the repository doesn't define is left out.
        assertThat(sent.text).contains(""""tangledLabels":[{"${'$'}type":"org.tangled.label.defs#labelToken","key":"performance"}]""")
        assertThat(sent.text).doesNotContain("unknown")
        assertThat(sent.text).doesNotContain("targetBranch")
        assertThat(issue.ref).isEqualTo(IssueRef(core, 4790, isPullRequest = false))
        assertThat(issue.labels.map { it.name }).containsExactly("performance")
        assertThat(issue.author?.login).isEqualTo("me.example")
        assertThat(issue.createdAt).isEqualTo(now)
    }

    @Test
    fun a_new_issue_without_a_body_leaves_it_out() = runTest {
        val fake = writes(mapOf("com.atproto.repo.createRecord" to { """{"uri":"at://$REPO_DID/org.tangled.track.ticket/4790","cid":"bafynew"}""" }))

        val issue = apis(fake).issues.create(token, core, "Just a title", " ", labels = emptyList()).value()

        // The knot refuses an empty body.
        assertThat(fake.to("com.atproto.repo.createRecord").first { it.url.host == "knot1.tangled.sh" }.text).doesNotContain(""""body"""")
        assertThat(issue.body).isNull()
    }

    @Test
    fun editing_an_issue_rewrites_its_title_and_body_over_the_version_read() = runTest {
        val fake = writes()

        apis(fake).issues.edit(token, IssueRef(core, 4780), "PR diff view with 500+ commits", "Now 500").value()

        val read = fake.to("com.atproto.repo.getRecord").single()
        assertThat(read.url.host).isEqualTo("knot1.tangled.sh")
        assertThat(read.url.parameters["rkey"]).isEqualTo("4780")
        val sent = fake.to("com.atproto.repo.putRecord").single().text
        assertThat(sent).contains(""""title":{"${'$'}type":"org.tangled.markup.markdown#inline","text":"PR diff view with 500+ commits"}""")
        assertThat(sent).contains(""""body":{"${'$'}type":"org.tangled.markup.markdown","text":"Now 500"}""")
        assertThat(sent).contains(""""swapRecord":"bafycurrent"""")
        // What isn't edited stays as it was.
        assertThat(sent).contains(""""state":"open"""")
    }

    @Test
    fun an_issue_edited_to_a_blank_body_loses_it() = runTest {
        val withBody = writes(
            mapOf(
                "com.atproto.repo.getRecord" to {
                    """{"uri":"at://$REPO_DID/org.tangled.track.ticket/4780","cid":"bafycurrent","value":{"title":{"text":"t"},"body":{"text":"old"},"state":"open"}}"""
                },
            ),
        )

        apis(withBody).issues.edit(token, IssueRef(core, 4780), "t", "").value()

        assertThat(withBody.to("com.atproto.repo.putRecord").single().text).doesNotContain("old")
    }

    @Test
    fun a_comment_is_edited_and_deleted_by_its_key_on_the_knot() = runTest {
        val id = Tid.decode("3n2ghbgtk3equ")!!
        val fake = writes(
            mapOf(
                "com.atproto.repo.getRecord" to {
                    """{"uri":"at://$REPO_DID/org.tangled.feed.comment/3n2ghbgtk3equ","cid":"bafycomment",
                        "value":{"${'$'}type":"org.tangled.feed.comment","body":{"${'$'}type":"org.tangled.markup.markdown","text":"first"},"createdAt":"2026-11-16T11:45:00Z"}}"""
                },
                "com.atproto.repo.deleteRecord" to { "{}" },
            ),
        )
        val issues = apis(fake).issues

        issues.editComment(token, IssueRef(core, 4780), id, "second").value()
        issues.deleteComment(token, IssueRef(core, 4780), id).value()

        assertThat(fake.to("com.atproto.repo.getRecord").single().url.parameters["collection"]).isEqualTo("org.tangled.feed.comment")
        val put = fake.to("com.atproto.repo.putRecord").single()
        assertThat(put.url.host).isEqualTo("knot1.tangled.sh")
        assertThat(put.text).contains(""""rkey":"3n2ghbgtk3equ"""")
        assertThat(put.text).contains(""""text":"second"""")
        assertThat(put.text).contains(""""swapRecord":"bafycomment"""")
        assertThat(put.text).contains(""""createdAt":"2026-11-16T11:45:00Z"""")
        val deleted = fake.to("com.atproto.repo.deleteRecord").single()
        assertThat(deleted.url.host).isEqualTo("knot1.tangled.sh")
        assertThat(deleted.text).contains(""""collection":"org.tangled.feed.comment"""")
        assertThat(deleted.text).contains(""""rkey":"3n2ghbgtk3equ"""")
    }

    @Test
    fun labels_are_set_by_their_keys_and_taking_them_all_off_drops_the_field() = runTest {
        val fake = writes()
        val issues = apis(fake).issues

        issues.setLabels(token, IssueRef(core, 4780), listOf("performance", "refactor")).value()
        issues.setLabels(token, IssueRef(core, 4780), emptyList()).value()

        val (set, cleared) = fake.to("com.atproto.repo.putRecord").map { it.text }
        assertThat(set).contains(""""tangledLabels":[{"${'$'}type":"org.tangled.label.defs#labelToken","key":"performance"},{"${'$'}type":"org.tangled.label.defs#labelToken","key":"refactor"}]""")
        assertThat(cleared).doesNotContain("tangledLabels")
    }

    @Test
    fun assignees_are_named_by_their_dids() = runTest {
        val fake = writes()
        val issues = apis(fake).issues

        issues.setAssignees(token, IssueRef(core, 4780), listOf("tangled.org")).value()
        issues.setAssignees(token, IssueRef(core, 4780), emptyList()).value()

        val (set, cleared) = fake.to("com.atproto.repo.putRecord").map { it.text }
        assertThat(set).contains(""""assignees":["$OWNER_DID"]""")
        assertThat(cleared).doesNotContain("assignees")
    }

    @Test
    fun the_owner_administers_the_repository_and_anyone_else_reads_it() = runTest {
        val owner = FakeTangled(
            mapOf(
                "com.atproto.identity.resolveHandle" to resolved,
                "org.tangled.temp.repo.getRepoByName" to coreManifest,
                "plc.directory/$OWNER_DID" to didDocument("tangled.org", "https://tngl.sh/"),
                "com.atproto.server.getSession" to { """{"did":"$OWNER_DID","handle":"tangled.org"}""" },
                "org.tangled.repo.listCollaborators" to { """{"items":[]}""" },
            ),
        )
        val ownerToken = token.replace(
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"scope":"com.atproto.appPass","sub":"$me","iat":${now.epochSecond},"exp":${now.epochSecond + 7_200}}""".toByteArray()),
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"scope":"com.atproto.appPass","sub":"$OWNER_DID","iat":${now.epochSecond},"exp":${now.epochSecond + 7_200}}""".toByteArray()),
        )

        assertThat(apis(owner).issues.access(ownerToken, core).value().access).isEqualTo(RepoAccess.ADMIN)
        assertThat(apis(writes(mapOf("org.tangled.repo.listCollaborators" to { """{"items":[]}""" }))).issues.access(token, core).value().access).isEqualTo(RepoAccess.NONE)
    }

    private fun didDocument(handle: String, pds: String): (HttpRequestData) -> String? = {
        """{"id":"x","alsoKnownAs":["at://$handle"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"$pds"}]}"""
    }
}

internal const val BOLTLESS = "did:plc:xasnlahkri4ewmbuzly2rlc5"
internal const val ETI = "did:plc:xu5apv6kmu5jp7g5hwdnej42"

/** A captured answer of Tangled's, from `src/test/resources/tangled`. */
internal fun fixture(name: String): String = requireNotNull(FakeTangled::class.java.getResource("/tangled/$name")) { name }.readText()
