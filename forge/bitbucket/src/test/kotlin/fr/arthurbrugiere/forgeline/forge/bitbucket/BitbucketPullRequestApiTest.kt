package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeBlocker
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Answers recorded from atlassian/atlassian-connect-express on 2026-11-18, and cut down from Bitbucket's documented ones. */
class BitbucketPullRequestApiTest {
    private val mock = BitbucketMock()
    private val api = BitbucketPullRequestApi(mock.http)
    private val ref = IssueRef(ace, 604, isPullRequest = true)
    private val pull = "$ACE/pullrequests/604"

    private val statuses = """{"values":[
        {"key":"default","state":"SUCCESSFUL","name":"Pipeline - default","commit":{"hash":"e0949d3373fda3ded93aa313a43b53e919c91341"},"url":"https://bitbucket.org/atlassian/atlassian-connect-express/pipelines/results/3200","updated_on":"2026-11-15T20:08:43+00:00"},
        {"key":"renovate/stability-days","state":"INPROGRESS","name":"renovate/stability-days","description":"Waiting","commit":{"hash":"e0949d3373fda3ded93aa313a43b53e919c91341"},"url":"https://docs.renovatebot.com/"},
        {"key":"default","state":"FAILED","name":"Pipeline - default","commit":{"hash":"1111111111111111111111111111111111111111"},"url":"https://bitbucket.org/atlassian/atlassian-connect-express/pipelines/results/3100"}
    ]}"""

    @Test
    fun a_change_s_files_come_with_their_patches_from_the_whole_diff() = runTest {
        mock.get("$pull/diffstat", fixture("diffstat.json"))
        mock.on(HttpMethod.Get, "$pull/diff") { respond(fixture("pull.diff")) }

        val files = api.files(null, ref).value()

        val file = files.files.single()
        assertThat(file.path).isEqualTo("package.json")
        assertThat(file.change).isEqualTo(FileChange.MODIFIED)
        assertThat(file.additions).isEqualTo(1)
        assertThat(file.patch).contains("+    \"prettier\": \"3.10.1\",")
        assertThat(files.nextPage).isNull()
    }

    @Test
    fun commits_come_oldest_first() = runTest {
        mock.get("$pull/commits", """{"values":[{"hash":"b2","message":"Second\n","date":"2026-11-02T00:00:00+00:00","author":{"raw":"Ana <ana@example.org>","user":{"nickname":"ana"}}},{"hash":"a1","message":"First","author":{"raw":"Bo <bo@example.org>"}}]}""")

        val commits = api.commits(null, ref).value()

        assertThat(commits.map { it.sha }).containsExactly("a1", "b2").inOrder()
        assertThat(commits[0].authorName).isEqualTo("Bo")
        assertThat(commits[1].author?.login).isEqualTo("ana")
        assertThat(commits[1].message).isEqualTo("Second")
    }

    @Test
    fun checks_are_the_latest_commit_s_statuses_each_pipeline_opening_as_a_run() = runTest {
        mock.get(pull, fixture("pull.json"))
        mock.get("$pull/statuses", statuses)

        val checks = api.checks(null, ref).value()

        assertThat(checks.map { it.name }).containsExactly("Pipeline - default", "renovate/stability-days").inOrder()
        assertThat(checks[0].state).isEqualTo(CheckState.SUCCESS)
        assertThat(checks[0].runId).isEqualTo(3200)
        // Another service's status opens at its page.
        assertThat(checks[1].runId).isNull()
        assertThat(checks[1].state).isEqualTo(CheckState.PENDING)
    }

    @Test
    fun each_commit_s_checks_stand_as_one() = runTest {
        mock.get("$pull/statuses", statuses)

        val states = api.commitChecks(null, ref).value()

        assertThat(states).containsExactly("e0949d3373fda3ded93aa313a43b53e919c91341", CheckState.PENDING, "1111111111111111111111111111111111111111", CheckState.FAILURE)
    }

    @Test
    fun merging_is_told_by_bitbucket_s_own_checks_and_the_branch_s_strategies() = runTest {
        mock.get(
            pull,
            """{"id":604,"state":"OPEN","close_source_branch":true,
            "source":{"branch":{"name":"feature"},"repository":{"full_name":"atlassian/atlassian-connect-express"}},
            "destination":{"branch":{"name":"master","merge_strategies":["merge_commit","squash","fast_forward"],"default_merge_strategy":"squash"},"repository":{"full_name":"atlassian/atlassian-connect-express"}}}"""
        )
        mock.get(
            "$pull/mergeability/checks",
            """{"size":3,"values":[
              {"type":"current_user_permission_check","status":"PASSED","required":true,"blocking":false},
              {"type":"git_mergeability_check","status":"PASSED","required":true,"blocking":false},
              {"type":"standard_merge_check","status":"FAILED","required":true,"blocking":true,"check":{"type":"standard_merge_check_definition","kind":"minimum_approvals"},"requirement":{"minimum_approvals":2},"observed":{"approvals":1}}
            ]}""",
        )

        val info = api.mergeInfo(TOKEN, ref).value()

        assertThat(info.mergeable).isTrue()
        assertThat(info.canMerge).isTrue()
        // Bitbucket's default first.
        assertThat(info.methods).containsExactly(MergeMethod.SQUASH, MergeMethod.MERGE, MergeMethod.REBASE).inOrder()
        assertThat(info.deleteBranch).isTrue()
        assertThat(info.blockers).containsExactly(MergeBlocker.REVIEWS)
    }

    @Test
    fun a_conflict_or_a_missing_permission_stops_a_merge() = runTest {
        mock.get(pull, """{"id":604,"state":"OPEN","source":{"repository":{"full_name":"ana/fork"}},"destination":{"repository":{"full_name":"atlassian/atlassian-connect-express"}}}""")
        mock.get("$pull/mergeability/checks", """{"values":[{"type":"current_user_permission_check","blocking":true},{"type":"git_mergeability_check","blocking":true}]}""")

        val info = api.mergeInfo(TOKEN, ref).value()

        assertThat(info.mergeable).isFalse()
        assertThat(info.canMerge).isFalse()
        assertThat(info.blockers).containsExactly(MergeBlocker.CONFLICTS)
        // A fork's branch isn't the repository's to delete.
        assertThat(info.deleteBranch).isNull()
    }

    @Test
    fun a_merge_asks_for_the_strategy_its_way_names_and_the_branch_s_end() = runTest {
        mock.get(pull, """{"destination":{"branch":{"merge_strategies":["merge_commit","rebase_fast_forward"]}}}""")
        mock.write(HttpMethod.Post, "$pull/merge", "{}", HttpStatusCode.Accepted)

        api.merge(TOKEN, ref, MergeMethod.REBASE, deleteBranch = true).value()

        assertThat(mock.sent(HttpMethod.Post, "$pull/merge").toString()).isEqualTo("""{"type":"pullrequest","merge_strategy":"rebase_fast_forward","close_source_branch":true}""")
    }

    @Test
    fun merging_once_checks_pass_is_not_bitbucket_s() = runTest {
        assertThat(api.merge(TOKEN, ref, MergeMethod.MERGE, whenChecksPass = true)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(mock.writes).isEmpty()
    }

    @Test
    fun a_review_posts_its_remarks_then_its_words_then_its_verdict() = runTest {
        mock.write(HttpMethod.Post, "$pull/comments", "{}", HttpStatusCode.Created)
        mock.write(HttpMethod.Post, "$pull/approve")

        api.review(
            TOKEN, ref, ReviewVerdict.APPROVE, "Ship it",
            listOf(
                LineComment("a.kt", oldLine = null, newLine = 12, body = "Nice"),
                LineComment("b.kt", oldLine = 4, newLine = null, body = "Gone", startOldLine = 2),
                LineComment("c.kt", oldLine = null, newLine = null, body = "Whole file", onFile = true),
            ),
        ).value()

        assertThat(mock.writes).containsExactly("POST $pull/comments", "POST $pull/comments", "POST $pull/comments", "POST $pull/comments", "POST $pull/approve").inOrder()
        val bodies = mock.requests.filter { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/comments") }.map { it.text }
        assertThat(bodies).containsExactly(
            """{"content":{"raw":"Nice"},"inline":{"path":"a.kt","to":12}}""",
            """{"content":{"raw":"Gone"},"inline":{"path":"b.kt","from":4,"start_from":2}}""",
            """{"content":{"raw":"Whole file"},"inline":{"path":"c.kt"}}""",
            """{"content":{"raw":"Ship it"}}""",
        ).inOrder()
    }

    @Test
    fun asking_for_changes_is_a_verdict_of_its_own() = runTest {
        mock.write(HttpMethod.Post, "$pull/request-changes")

        api.review(TOKEN, ref, ReviewVerdict.REQUEST_CHANGES, "", emptyList()).value()

        assertThat(mock.writes).containsExactly("POST $pull/request-changes")
    }

    private val comments = """{"values":[
        {"id":1,"content":{"raw":"Why?"},"inline":{"path":"a.kt","to":12,"start_to":10},"user":{"nickname":"bo","uuid":"{b}"},"created_on":"2026-11-01T00:00:00+00:00","resolution":{"type":"comment_resolution","user":{"nickname":"ana"}}},
        {"id":2,"content":{"raw":"Because."},"parent":{"id":1},"inline":{"path":"a.kt","to":12},"user":{"nickname":"ana","uuid":"{a}"},"created_on":"2026-11-01T01:00:00+00:00"},
        {"id":3,"content":{"raw":"In general"},"user":{"nickname":"bo"},"created_on":"2026-11-01T02:00:00+00:00"},
        {"id":4,"content":{"raw":"Old line"},"inline":{"path":"b.kt","from":3},"user":{"nickname":"bo"},"created_on":"2026-11-02T00:00:00+00:00"},
        {"id":5,"content":{"raw":""},"deleted":true,"parent":{"id":4},"inline":{"path":"b.kt","from":3},"user":{"nickname":"bo"},"created_on":"2026-11-02T01:00:00+00:00"}
    ],"page":1}"""

    @Test
    fun threads_gather_each_remark_with_its_replies() = runTest {
        mock.get("$pull/comments", comments)

        val threads = api.threads(null, ref).value()

        assertThat(threads.map { it.id }).containsExactly("1", "4").inOrder()
        val first = threads[0]
        assertThat(first.path).isEqualTo("a.kt")
        assertThat(first.line).isEqualTo(12)
        assertThat(first.startLine).isEqualTo(10)
        assertThat(first.onOldSide).isFalse()
        assertThat(first.resolved).isTrue()
        assertThat(first.comments.map { it.body }).containsExactly("Why?", "Because.").inOrder()
        // On a removed line, its deleted reply left out.
        assertThat(threads[1].onOldSide).isTrue()
        assertThat(threads[1].comments.map { it.id }).containsExactly(4L)
    }

    @Test
    fun a_thread_is_answered_rewritten_taken_back_and_resolved() = runTest {
        mock.get("$pull/comments", comments)
        mock.write(HttpMethod.Post, "$pull/comments", "{}", HttpStatusCode.Created)
        mock.write(HttpMethod.Put, "$pull/comments/2")
        mock.write(HttpMethod.Delete, "$pull/comments/2", "", HttpStatusCode.NoContent)
        mock.write(HttpMethod.Post, "$pull/comments/1/resolve")
        mock.write(HttpMethod.Delete, "$pull/comments/1/resolve", "", HttpStatusCode.NoContent)
        val thread = api.threads(TOKEN, ref).value().first()

        api.reply(TOKEN, ref, thread, "Agreed").value()
        api.editThreadComment(TOKEN, ref, thread, thread.comments[1], "Because!").value()
        api.deleteThreadComment(TOKEN, ref, thread, thread.comments[1]).value()
        api.setResolved(TOKEN, ref, thread, resolved = false).value()
        api.setResolved(TOKEN, ref, thread, resolved = true).value()

        assertThat(mock.writes).containsExactly(
            "POST $pull/comments", "PUT $pull/comments/2", "DELETE $pull/comments/2", "DELETE $pull/comments/1/resolve", "POST $pull/comments/1/resolve",
        ).inOrder()
        assertThat(mock.sent(HttpMethod.Post, "$pull/comments").toString()).isEqualTo("""{"content":{"raw":"Agreed"},"parent":{"id":1}}""")
        assertThat(mock.sent(HttpMethod.Put, "$pull/comments/2").toString()).isEqualTo("""{"content":{"raw":"Because!"}}""")
    }

    @Test
    fun a_pull_request_is_opened_from_a_branch_into_another() = runTest {
        mock.write(HttpMethod.Post, "$ACE/pullrequests", """{"id":605,"title":"Add it"}""", HttpStatusCode.Created)

        val opened = api.create(TOKEN, ace, NewPullRequest("Add it", "Why", head = "feature", base = "master", draft = true)).value()

        assertThat(opened).isEqualTo(IssueRef(ace, 605, isPullRequest = true))
        assertThat(mock.sent(HttpMethod.Post, "$ACE/pullrequests").toString())
            .isEqualTo("""{"title":"Add it","description":"Why","source":{"branch":{"name":"feature"}},"destination":{"branch":{"name":"master"}},"draft":true}""")
    }

    @Test
    fun history_reads_from_head_by_path_and_pages_on() = runTest {
        mock.get("$ACE/commits/HEAD", """{"values":[{"hash":"9c5c858","message":"Bump"}],"next":"https://api.bitbucket.org/2.0/repositories/atlassian/atlassian-connect-express/commits/HEAD?path=package.json&page=2"}""")

        val page = api.history(null, ace, null, "package.json").value()

        assertThat(page.commits.single().sha).isEqualTo("9c5c858")
        assertThat(page.nextPage).isEqualTo(2)
        assertThat(mock.requests.single().url.parameters["path"]).isEqualTo("package.json")
    }

    @Test
    fun a_commit_reads_with_its_files() = runTest {
        mock.get("$ACE/commit/9c5c858", """{"hash":"9c5c858","message":"Bump prettier","date":"2026-11-15T20:00:00+00:00"}""")
        mock.get("$ACE/diffstat/9c5c858", fixture("diffstat.json"))
        mock.on(HttpMethod.Get, "$ACE/diff/9c5c858") { respond(fixture("pull.diff")) }

        val details = api.commit(null, ace, "9c5c858").value()

        assertThat(details.commit.title).isEqualTo("Bump prettier")
        assertThat(details.files.single().patch).isNotNull()
    }
}
