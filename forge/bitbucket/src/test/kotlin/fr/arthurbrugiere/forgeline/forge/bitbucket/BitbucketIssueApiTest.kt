package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Answers recorded from atlassian/atlassian-connect-express's pull request #604 on 2026-11-18, its description shortened. */
class BitbucketIssueApiTest {
    private val mock = BitbucketMock()
    private val people = BitbucketPeople()
    private val api = BitbucketIssueApi(mock.http, people)
    private val ref = IssueRef(ace, 604, isPullRequest = true)
    private val pull = "$ACE/pullrequests/604"

    @Test
    fun a_pull_request_reads_with_its_branches_size_and_those_still_to_review() = runTest {
        mock.get(pull, fixture("pull.json"))
        mock.get("$pull/diffstat", fixture("diffstat.json"))

        val details = api.issue(null, ref).value()

        assertThat(details.title).isEqualTo("[Renovate] Update dependency prettier to v3.10.1")
        assertThat(details.state).isEqualTo(IssueState.OPEN)
        assertThat(details.author?.login).isEqualTo("atlassian-renovate-bot")
        assertThat(details.body).startsWith("This PR contains")
        val info = details.pullRequest!!
        assertThat(info.baseRef).isEqualTo("master")
        assertThat(info.headRef).isEqualTo("renovate/prettier-3.x")
        assertThat(info.changedFiles).isEqualTo(1)
        assertThat(info.additions).isEqualTo(1)
        assertThat(info.deletions).isEqualTo(1)
        // Both asked; neither has said yet (Chloe Zhang approved without being asked).
        assertThat(info.reviewers.map { it.login }).containsExactly("Ben Kelley", "Serena Wang")
    }

    @Test
    fun the_timeline_tells_approvals_pushes_and_comments_oldest_first() = runTest {
        mock.get("$pull/activity", fixture("activity.json"))

        val items = api.timeline(null, ref, 1).value().items

        assertThat(items.map { it.createdAt }).isInOrder()
        val approval = items.filterIsInstance<TimelineItem.Review>().single()
        assertThat(approval.state).isEqualTo(ReviewState.APPROVED)
        assertThat(approval.author?.login).isEqualTo("Chloe Zhang")
        // Renovate pushed onto its branch after opening it.
        assertThat(items.filterIsInstance<TimelineItem.Event>().map { it.event }).contains(ConversationEvent.PUSHED)
        // Nothing past the first page: the whole activity is on it.
        assertThat(api.timeline(null, ref, 2).value().items).isEmpty()
    }

    @Test
    fun a_merge_and_a_rename_are_told_from_the_updates_before_them() = runTest {
        mock.get(
            "$pull/activity",
            """{"values":[
              {"update":{"state":"MERGED","title":"Two","date":"2026-11-03T00:00:00+00:00","author":{"nickname":"ana","uuid":"{a}"},"source":{"commit":{"hash":"bbb"}}}},
              {"comment":{"id":7,"content":{"raw":"Looks good"},"user":{"nickname":"bo","uuid":"{b}"},"created_on":"2026-11-02T00:00:00+00:00"}},
              {"comment":{"id":8,"content":{"raw":"On a line"},"inline":{"path":"a.kt","to":3},"user":{"nickname":"bo"},"created_on":"2026-11-02T01:00:00+00:00"}},
              {"changes_requested":{"date":"2026-11-01T12:00:00+00:00","user":{"nickname":"bo"}}},
              {"update":{"state":"OPEN","title":"One","date":"2026-11-01T00:00:00+00:00","author":{"nickname":"ana"},"source":{"commit":{"hash":"bbb"}}}}
            ]}""",
        )

        val items = api.timeline(null, ref, 1).value().items

        assertThat(items.map { it::class.simpleName }).containsExactly("Review", "Comment", "Renamed", "StateChanged").inOrder()
        assertThat((items[0] as TimelineItem.Review).state).isEqualTo(ReviewState.CHANGES_REQUESTED)
        // A remark on a line is a thread's, not the conversation's.
        assertThat((items[1] as TimelineItem.Comment).body).isEqualTo("Looks good")
        assertThat((items[2] as TimelineItem.Renamed).to).isEqualTo("Two")
        assertThat((items[3] as TimelineItem.StateChanged).change).isEqualTo(StateChange.MERGED)
    }

    @Test
    fun a_comment_is_posted_as_markdown_and_answered_as_kept() = runTest {
        mock.write(HttpMethod.Post, "$pull/comments", """{"id":2012,"content":{"raw":"Thanks!"},"user":{"nickname":"ana","uuid":"{a}"},"created_on":"2026-11-18T01:00:00.000000+00:00"}""", io.ktor.http.HttpStatusCode.Created)

        val comment = api.comment(TOKEN, ref, "Thanks!").value()

        assertThat(comment.id).isEqualTo(2012)
        assertThat(comment.author?.login).isEqualTo("ana")
        assertThat(mock.sent(HttpMethod.Post, "$pull/comments").getValue("content").jsonObject.getValue("raw").jsonPrimitive.content).isEqualTo("Thanks!")
        assertThat(mock.requests.single().headers["Authorization"]).isEqualTo(TOKEN)
    }

    @Test
    fun a_comment_is_rewritten_and_deleted_by_its_id() = runTest {
        mock.write(HttpMethod.Put, "$pull/comments/2012")
        mock.write(HttpMethod.Delete, "$pull/comments/2012", "", io.ktor.http.HttpStatusCode.NoContent)

        api.editComment(TOKEN, ref, 2012, "Fixed").value()
        api.deleteComment(TOKEN, ref, 2012).value()

        assertThat(mock.writes).containsExactly("PUT $pull/comments/2012", "DELETE $pull/comments/2012").inOrder()
        assertThat(mock.sent(HttpMethod.Put, "$pull/comments/2012").toString()).isEqualTo("""{"content":{"raw":"Fixed"}}""")
    }

    @Test
    fun editing_sends_the_title_and_description_together() = runTest {
        mock.write(HttpMethod.Put, pull)

        api.edit(TOKEN, ref, "New title", "New words").value()

        assertThat(mock.sent(HttpMethod.Put, pull).toString()).isEqualTo("""{"title":"New title","description":"New words"}""")
    }

    @Test
    fun closing_declines_and_reopening_is_unsupported() = runTest {
        mock.write(HttpMethod.Post, "$pull/decline")

        api.setOpen(TOKEN, ref, open = false).value()
        val reopened = api.setOpen(TOKEN, ref, open = true)

        assertThat(mock.writes).containsExactly("POST $pull/decline")
        assertThat(reopened).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }

    @Test
    fun no_issue_is_opened_on_bitbucket() = runTest {
        assertThat(api.create(TOKEN, ace, "Bug", "")).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(mock.requests).isEmpty()
    }

    @Test
    fun reviewers_are_asked_by_the_accounts_behind_their_names() = runTest {
        mock.get("/workspaces/atlassian/members", """{"values":[{"user":{"nickname":"Ben Kelley","display_name":"Ben Kelley","uuid":"{933f}"}},{"user":{"nickname":"serena","uuid":"{3820}"}}]}""")
        mock.get(pull, fixture("pull.json"))
        mock.write(HttpMethod.Put, pull)

        val members = api.assignable(TOKEN, ace).value()
        api.setReviewers(TOKEN, ref, members.map { it.login }).value()

        val sent = mock.sent(HttpMethod.Put, pull)
        assertThat(sent.getValue("reviewers").jsonArray.map { it.jsonObject.getValue("uuid").jsonPrimitive.content }).containsExactly("{933f}", "{3820}").inOrder()
        // Bitbucket asks for the title on every change.
        assertThat(sent.getValue("title").jsonPrimitive.content).isEqualTo("[Renovate] Update dependency prettier to v3.10.1")
    }

    @Test
    fun a_reviewer_never_seen_is_not_guessed() = runTest {
        val result = api.setReviewers(TOKEN, ref, listOf("nobody"))

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.Http::class.java)
        assertThat(mock.writes).isEmpty()
    }

    @Test
    fun drafting_and_moving_the_base_keep_the_title() = runTest {
        mock.get(pull, """{"title":"Kept"}""")
        mock.write(HttpMethod.Put, pull)

        api.setDraft(TOKEN, ref, true).value()
        assertThat(mock.sent(HttpMethod.Put, pull).toString()).isEqualTo("""{"title":"Kept","draft":true}""")

        api.setBase(TOKEN, ref, "develop").value()
        assertThat(mock.sent(HttpMethod.Put, pull).toString()).isEqualTo("""{"title":"Kept","destination":{"branch":{"name":"develop"}}}""")
    }

    @Test
    fun rights_are_the_workspace_s_permission_on_the_repository() = runTest {
        mock.get("/user/workspaces/atlassian/permissions/repositories", """{"values":[{"permission":"write","repository":{"full_name":"atlassian/atlassian-connect-express"}}]}""")

        assertThat(api.access(TOKEN, ace).value().access).isEqualTo(RepoAccess.WRITE)
        assertThat(mock.requests.single().url.parameters["q"]).isEqualTo("repository.full_name=\"atlassian/atlassian-connect-express\"")
    }
}
