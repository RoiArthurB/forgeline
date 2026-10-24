package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckReport
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeBlocker
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Answers are cut down from api.github.com's, in the shape its documentation gives (REST, version 2022-11-28). */
class GitHubPullRequestApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("octo", "tools")
    private val pull = IssueRef(repo, 88, isPullRequest = true)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, link: String? = null) =
        respond(body, status, headersOf(*listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), link?.let { HttpHeaders.Link to listOf(it) }).toTypedArray()))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubPullRequestApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.path get() = url.encodedPath

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun the_files_of_a_pull_request_come_with_their_changes_page_by_page() = runTest {
        val files = api {
            json(
                """[
                    {"filename":"src/Upload.kt","status":"modified","additions":2,"deletions":1,"patch":"@@ -1,2 +1,3 @@\n-old\n+new\n+more\n same"},
                    {"filename":"docs/retry.md","status":"added","additions":3,"deletions":0,"patch":"@@ -0,0 +1,3 @@\n+a\n+b\n+c"},
                    {"filename":"new/Name.kt","previous_filename":"old/Name.kt","status":"renamed","additions":0,"deletions":0},
                    {"filename":"logo.png","status":"removed","additions":0,"deletions":0}
                ]""",
                link = """<https://api.github.com/repositories/1/pulls/88/files?per_page=50&page=3>; rel="next"""",
            )
        }.files("tok", pull, page = 2).value()

        assertThat(files.files.map { it.path to it.change }).containsExactly(
            "src/Upload.kt" to FileChange.MODIFIED, "docs/retry.md" to FileChange.ADDED, "new/Name.kt" to FileChange.RENAMED, "logo.png" to FileChange.REMOVED,
        ).inOrder()
        assertThat(files.files[0].additions to files.files[0].deletions).isEqualTo(2 to 1)
        assertThat(files.files[0].patch).startsWith("@@ -1,2 +1,3 @@")
        assertThat(files.files[2].previousPath).isEqualTo("old/Name.kt")
        // A file that isn't text comes without a patch.
        assertThat(files.files[3].patch).isNull()
        assertThat(files.nextPage).isEqualTo(3)
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/pulls/88/files")
        assertThat(requests.single().url.parameters["page"]).isEqualTo("2")
    }

    private val commitsJson = """[
        {"sha":"1111111aaaaaaa","commit":{"message":"Retry uploads\n\nOn slow links.","author":{"name":"The Octocat","date":"2026-09-26T08:00:00Z"}},"author":{"login":"octocat","avatar_url":"https://a/1"}},
        {"sha":"2222222bbbbbbb","commit":{"message":"Fix typo","author":{"name":"Someone Else","date":"2026-09-26T09:00:00Z"}},"author":null}
    ]"""

    @Test
    fun the_commits_of_a_pull_request_say_who_wrote_them_and_when() = runTest {
        val commits = api { json(commitsJson) }.commits(null, pull).value()

        assertThat(commits.map { it.title }).containsExactly("Retry uploads", "Fix typo").inOrder()
        assertThat(commits[0].description).isEqualTo("On slow links.")
        assertThat(commits[0].author).isEqualTo(ForgeUser("octocat", null, "https://a/1"))
        assertThat(commits[0].date).isEqualTo(Instant.parse("2026-09-26T08:00:00Z"))
        // An email no account claims: the name written in the commit is all there is.
        assertThat(commits[1].author).isNull()
        assertThat(commits[1].authorName).isEqualTo("Someone Else")
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/pulls/88/commits")
    }

    @Test
    fun checks_gather_check_runs_and_statuses_of_the_latest_commit() = runTest {
        val checks = api { request ->
            when {
                request.path.endsWith("/pulls/88") -> json("""{"head":{"sha":"abc123"},"mergeable":true}""")
                request.path.endsWith("/commits/abc123/check-runs") -> json(
                    """{"total_count":4,"check_runs":[
                        {"name":"build","status":"completed","conclusion":"success","html_url":"https://github.com/octo/tools/actions/runs/777/job/1","output":{"title":"Built"}},
                        {"name":"test","status":"in_progress","conclusion":null,"html_url":"https://github.com/octo/tools/actions/runs/777/job/2"},
                        {"name":"lint","status":"completed","conclusion":"failure","html_url":"https://github.com/octo/tools/actions/runs/778/job/3"},
                        {"id":4242,"name":"deploy","status":"completed","conclusion":"skipped","details_url":"https://ci.example/9"}
                    ]}""",
                )
                else -> json("""{"state":"pending","statuses":[{"context":"coverage/total","state":"pending","description":"Waiting","target_url":"https://cov.example/1"}]}""")
            }
        }.checks("tok", pull).value()

        assertThat(checks).containsExactly(
            Check("build", CheckState.SUCCESS, "Built", "https://github.com/octo/tools/actions/runs/777/job/1", runId = 777),
            Check("test", CheckState.PENDING, null, "https://github.com/octo/tools/actions/runs/777/job/2", runId = 777),
            Check("lint", CheckState.FAILURE, null, "https://github.com/octo/tools/actions/runs/778/job/3", runId = 778),
            // Another service's check is no run the app can show: it has a report, by its id, and a page.
            Check("deploy", CheckState.SKIPPED, null, "https://ci.example/9", checkId = 4242),
            Check("coverage/total", CheckState.PENDING, "Waiting", "https://cov.example/1"),
        ).inOrder()
        assertThat(requests.map { it.path }).contains("/repos/octo/tools/commits/abc123/status")
    }

    @Test
    fun a_check_s_report_is_what_it_wrote_and_where_it_keeps_more() = runTest {
        val report = api {
            json(
                """{"id":4242,"name":"Code Health Review (main)","status":"completed","conclusion":"failure","completed_at":"2026-10-09T04:03:42Z",
                    "html_url":"https://github.com/octo/tools/runs/4242","details_url":"https://health.example/results/7",
                    "app":{"slug":"code-health","name":"Code Health"},
                    "output":{"title":"PR Check","summary":"## Quality Gate Failed\n\n","text":"* Complex Method **Zip.kt**","annotations_count":0}}""",
            )
        }.checkReport("tok", repo, 4242).value()

        assertThat(report).isEqualTo(
            CheckReport(
                name = "Code Health Review (main)", state = CheckState.FAILURE, app = "Code Health", title = "PR Check",
                summary = "## Quality Gate Failed", text = "* Complex Method **Zip.kt**", completedAt = Instant.parse("2026-10-09T04:03:42Z"),
                detailsUrl = "https://health.example/results/7", url = "https://github.com/octo/tools/runs/4242",
            ),
        )
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/check-runs/4242")
    }

    @Test
    fun a_check_that_wrote_nothing_and_is_still_running_reports_just_that() = runTest {
        val report = api {
            json("""{"id":7,"name":"scan","status":"in_progress","conclusion":null,"html_url":"https://github.com/octo/tools/runs/7","details_url":"https://github.com/octo/tools/runs/7","output":{"title":null,"summary":null,"text":null}}""")
        }.checkReport(null, repo, 7).value()

        assertThat(report).isEqualTo(CheckReport("scan", CheckState.PENDING, null, null, null, null, null, detailsUrl = null, url = "https://github.com/octo/tools/runs/7"))
    }

    @Test
    fun a_check_that_is_gone_fails() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.checkReport(null, repo, 7)

        assertThat(result).isInstanceOf(ForgeResult.Failure::class.java)
    }

    @Test
    fun check_runs_still_show_when_the_statuses_cannot_be_asked_for() = runTest {
        val checks = api { request ->
            when {
                request.path.endsWith("/pulls/88") -> json("""{"head":{"sha":"abc123"}}""")
                request.path.endsWith("/check-runs") -> json("""{"check_runs":[{"name":"build","status":"completed","conclusion":"success"}]}""")
                else -> json("{}", HttpStatusCode.Forbidden)
            }
        }.checks("tok", pull).value()

        assertThat(checks.map { it.name }).containsExactly("build")
    }

    @Test
    fun merging_is_offered_the_ways_the_repository_allows_to_those_who_can_push() = runTest {
        val info = api { request ->
            if (request.path.endsWith("/pulls/88")) {
                json("""{"head":{"sha":"abc123"},"mergeable":true}""")
            } else {
                json("""{"allow_merge_commit":false,"allow_squash_merge":true,"allow_rebase_merge":true,"permissions":{"push":true}}""")
            }
        }.mergeInfo("tok", pull).value()

        assertThat(info).isEqualTo(MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.SQUASH, MergeMethod.REBASE)))
    }

    @Test
    fun a_reader_cannot_merge_and_github_may_still_be_working_out_whether_anyone_can() = runTest {
        val info = api { request ->
            if (request.path.endsWith("/pulls/88")) json("""{"head":{"sha":"abc123"},"mergeable":null}""") else json("""{"permissions":{"push":false}}""")
        }.mergeInfo("tok", pull).value()

        assertThat(info.mergeable).isNull()
        assertThat(info.canMerge).isFalse()
    }

    @Test
    fun a_merge_says_how() = runTest {
        val result = api { json("""{"merged":true}""") }.merge("tok", pull, MergeMethod.SQUASH)

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        assertThat(requests.single().method).isEqualTo(HttpMethod.Put)
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/pulls/88/merge")
        assertThat(requests.single().sent).isEqualTo("""{"merge_method":"squash"}""")
    }

    /** A pull request from a branch of the repository itself, as GitHub describes both ends. */
    private val ownBranch = """{"head":{"sha":"abc123","ref":"fix/retry","repo":{"full_name":"octo/tools"}},"base":{"sha":"def","ref":"main","repo":{"full_name":"octo/tools"}},"mergeable":true,"node_id":"PR_88"}"""

    @Test
    fun a_branch_of_the_repository_itself_can_go_with_the_merge_and_auto_merge_is_offered_where_allowed() = runTest {
        val info = api { request ->
            if (request.path.endsWith("/pulls/88")) json(ownBranch) else json("""{"allow_auto_merge":true,"delete_branch_on_merge":false,"permissions":{"push":true}}""")
        }.mergeInfo("tok", pull).value()

        assertThat(info.deleteBranch).isFalse()
        assertThat(info.canMergeWhenChecksPass).isTrue()
        assertThat(info.mergesWhenChecksPass).isFalse()
    }

    @Test
    fun a_fork_s_branch_or_one_github_deletes_anyway_is_no_choice() = runTest {
        val fork = ownBranch.replace(""""repo":{"full_name":"octo/tools"}},"base""", """"repo":{"full_name":"someone/tools"}},"base""")
        val fromFork = api { request -> if (request.path.endsWith("/pulls/88")) json(fork) else json("""{"permissions":{"push":true}}""") }.mergeInfo("tok", pull).value()
        requests.clear()
        val deletesAnyway = api { request ->
            if (request.path.endsWith("/pulls/88")) json(ownBranch) else json("""{"delete_branch_on_merge":true,"permissions":{"push":true}}""")
        }.mergeInfo("tok", pull).value()

        assertThat(fromFork.deleteBranch).isNull()
        assertThat(deletesAnyway.deleteBranch).isNull()
        // Not allowed by the repository: no auto-merge.
        assertThat(deletesAnyway.canMergeWhenChecksPass).isFalse()
    }

    @Test
    fun a_pull_request_set_to_merge_once_its_checks_pass_says_so() = runTest {
        val armed = ownBranch.replace(""""node_id":"PR_88"}""", """"node_id":"PR_88","auto_merge":{"merge_method":"squash"}}""")
        val info = api { request -> if (request.path.endsWith("/pulls/88")) json(armed) else json("""{"allow_auto_merge":true,"permissions":{"push":true}}""") }.mergeInfo("tok", pull).value()

        assertThat(info.mergesWhenChecksPass).isTrue()
    }

    @Test
    fun a_merged_branch_is_deleted_when_asked_slashes_and_all() = runTest {
        val result = api { request ->
            when (request.method) {
                HttpMethod.Put -> json("""{"merged":true}""")
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> json(ownBranch)
            }
        }.merge("tok", pull, MergeMethod.MERGE, deleteBranch = true)

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        assertThat(requests.map { "${it.method.value} ${it.path}" }).containsExactly(
            "PUT /repos/octo/tools/pulls/88/merge", "GET /repos/octo/tools/pulls/88", "DELETE /repos/octo/tools/git/refs/heads/fix/retry",
        ).inOrder()
    }

    @Test
    fun a_refused_merge_deletes_nothing() = runTest {
        api { json("""{"message":"Pull Request is not mergeable"}""", HttpStatusCode.MethodNotAllowed) }.merge("tok", pull, MergeMethod.MERGE, deleteBranch = true)

        assertThat(requests.map { it.method }).containsExactly(HttpMethod.Put)
    }

    @Test
    fun merging_once_the_checks_pass_and_taking_it_back_go_through_graphql_by_the_node() = runTest {
        val acting = api { request -> if (request.path == "/graphql") json("""{"data":{"done":{"clientMutationId":null}}}""") else json(ownBranch) }

        assertThat(acting.merge("tok", pull, MergeMethod.SQUASH, whenChecksPass = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.cancelMergeWhenChecksPass("tok", pull)).isEqualTo(ForgeResult.Success(Unit))

        val mutations = requests.filter { it.path == "/graphql" }.map { it.sent }
        assertThat(mutations[0]).contains("enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: \$method})")
        assertThat(mutations[0]).contains(""""variables":{"id":"PR_88","method":"SQUASH"}""")
        assertThat(mutations[1]).contains("disablePullRequestAutoMerge(input: {pullRequestId: \$id})")
        // Nothing merged now.
        assertThat(requests.none { it.path.endsWith("/merge") }).isTrue()
    }

    @Test
    fun a_branch_behind_the_one_it_goes_into_is_brought_up_to_date_by_github() = runTest {
        val behind = ownBranch.replace(""""mergeable":true""", """"mergeable":true,"mergeable_state":"behind"""")
        val info = api { request -> if (request.path.endsWith("/pulls/88")) json(behind) else json("""{"permissions":{"push":true}}""") }.mergeInfo("tok", pull).value()
        assertThat(info.behind).isTrue()
        requests.clear()

        val result = api { json("""{"message":"Updating pull request branch.","url":"https://github.com/octo/tools/pull/88"}""", HttpStatusCode.Accepted) }.updateBranch("tok", pull)

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        assertThat("${requests.single().method.value} ${requests.single().path}").isEqualTo("PUT /repos/octo/tools/pulls/88/update-branch")
    }

    @Test
    fun a_branch_up_to_date_is_not_behind() = runTest {
        val clean = ownBranch.replace(""""mergeable":true""", """"mergeable":true,"mergeable_state":"clean"""")
        val info = api { request -> if (request.path.endsWith("/pulls/88")) json(clean) else json("""{"permissions":{"push":true}}""") }.mergeInfo("tok", pull).value()

        assertThat(info.behind).isFalse()
        assertThat(info.blockers).isEmpty()
    }

    @Test
    fun conflicts_and_a_branch_behind_are_said_from_the_pull_request_alone() = runTest {
        val dirty = ownBranch.replace(""""mergeable":true""", """"mergeable":false,"mergeable_state":"dirty"""")
        val conflicting = api { request -> if (request.path.endsWith("/pulls/88")) json(dirty) else json("""{"permissions":{"push":true}}""") }.mergeInfo("tok", pull).value()
        val behind = ownBranch.replace(""""mergeable":true""", """"mergeable":true,"mergeable_state":"behind"""")
        val late = api { request -> if (request.path.endsWith("/pulls/88")) json(behind) else json("""{"permissions":{"push":true}}""") }.mergeInfo("tok", pull).value()

        assertThat(conflicting.blockers).containsExactly(MergeBlocker.CONFLICTS)
        assertThat(late.blockers).containsExactly(MergeBlocker.BEHIND)
        assertThat(requests.none { it.path == "/graphql" }).isTrue()
    }

    private val blocked = """{"head":{"sha":"abc","ref":"retry","repo":{"full_name":"octo/tools"}},"base":{"repo":{"full_name":"octo/tools"}},"mergeable":true,"mergeable_state":"blocked"}"""

    private fun standing(decision: String?, rollup: String?) =
        """{"data":{"repository":{"pullRequest":{"reviewDecision":${decision?.let { "\"$it\"" }},"commits":{"nodes":[{"commit":{"statusCheckRollup":${rollup?.let { "{\"state\":\"$it\"}" }}}}]}}}}}"""

    @Test
    fun a_blocked_merge_says_which_reviews_and_checks_stand_in_its_way() = runTest {
        val info = api { request ->
            when {
                request.path == "/graphql" -> json(standing("REVIEW_REQUIRED", "FAILURE"))
                request.path.endsWith("/pulls/88") -> json(blocked)
                else -> json("""{"permissions":{"push":true}}""")
            }
        }.mergeInfo("tok", pull).value()

        assertThat(info.blockers).containsExactly(MergeBlocker.REVIEWS, MergeBlocker.CHECKS).inOrder()
        assertThat(requests.single { it.path == "/graphql" }.sent).contains(""""variables":{"owner":"octo","name":"tools","number":88}""")
    }

    @Test
    fun a_reviewer_asking_for_changes_blocks_a_merge() = runTest {
        val info = api { request ->
            when {
                request.path == "/graphql" -> json(standing("CHANGES_REQUESTED", "SUCCESS"))
                request.path.endsWith("/pulls/88") -> json(blocked)
                else -> json("""{"permissions":{"push":true}}""")
            }
        }.mergeInfo("tok", pull).value()

        assertThat(info.blockers).containsExactly(MergeBlocker.CHANGES_REQUESTED)
    }

    @Test
    fun a_blocked_merge_with_reviews_and_checks_in_is_held_by_another_rule() = runTest {
        val info = api { request ->
            when {
                request.path == "/graphql" -> json(standing("APPROVED", "SUCCESS"))
                request.path.endsWith("/pulls/88") -> json(blocked)
                else -> json("""{"permissions":{"push":true}}""")
            }
        }.mergeInfo("tok", pull).value()

        assertThat(info.blockers).containsExactly(MergeBlocker.OTHER)
        // Blocked isn't refused: whoever may bypass the rules can still merge.
        assertThat(info.mergeable).isTrue()
    }

    // Real answers for gama-platform/gama#1265, captured 2026-10-23: five threads by a review bot, two answered.
    private fun captured(name: String) = requireNotNull(javaClass.getResource("/github/pull/$name")) { name }.readText()

    private val gama = IssueRef(RepoId("gama-platform", "gama"), 1265, isPullRequest = true)

    @Test
    fun signed_in_threads_come_from_graphql_with_whether_each_is_resolved() = runTest {
        val threads = api { json(captured("review_threads.json")) }.threads("tok", gama).value()

        assertThat(threads).hasSize(5)
        val first = threads.first()
        assertThat(first.id).isEqualTo("PRRT_kwDOLUle7M6vdxgm")
        assertThat(first.path).isEqualTo("gama.core/src/gama/core/topology/graph/FloydWarshallShortestPathsGAMA.java")
        // The code moved on: placed where it was.
        assertThat(first.line).isEqualTo(265)
        assertThat(first.outdated).isTrue()
        assertThat(first.onOldSide).isFalse()
        assertThat(first.hunk).startsWith("@@")
        assertThat(first.comments.map { it.author?.login }).containsExactly("copilot-pull-request-reviewer", "copilot-swe-agent").inOrder()
        assertThat(first.canResolve).isTrue()
        assertThat(threads.map { it.resolved }).containsExactly(false, true, true, false, true).inOrder()
        assertThat(requests.single().path).isEqualTo("/graphql")
        assertThat(requests.single().sent).contains(""""variables":{"owner":"gama-platform","name":"gama","number":1265}""")
    }

    @Test
    fun signed_out_threads_are_put_back_together_from_the_rest_comments() = runTest {
        val threads = api { json(captured("review_comments.json")) }.threads(null, gama).value()

        assertThat(threads.map { it.id }).containsExactly("4355905017", "4355905046", "4355905072", "4355905095", "4355905117").inOrder()
        assertThat(threads.map { it.comments.size }).containsExactly(2, 1, 1, 2, 1).inOrder()
        assertThat(threads[0].comments[1].id).isEqualTo(4356030362)
        assertThat(threads[0].line).isEqualTo(265)
        // Nothing to resolve without an account, nor anything said about it.
        assertThat(threads.none { it.canResolve || it.resolved }).isTrue()
        assertThat(requests.single().path).isEqualTo("/repos/gama-platform/gama/pulls/1265/comments")
    }

    @Test
    fun a_reply_goes_under_the_first_comment_and_resolving_names_the_thread() = runTest {
        val thread = api { json(captured("review_threads.json")) }.threads("tok", gama).value().first()
        requests.clear()
        val acting = api { request -> if (request.path == "/graphql") json("""{"data":{"done":{"thread":{"id":"x"}}}}""") else json("{}", HttpStatusCode.Created) }

        assertThat(acting.reply("tok", gama, thread, "Fixed in 7a1b.")).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.setResolved("tok", gama, thread, resolved = true)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(acting.setResolved("tok", gama, thread, resolved = false)).isEqualTo(ForgeResult.Success(Unit))

        assertThat(requests[0].path).isEqualTo("/repos/gama-platform/gama/pulls/1265/comments/4355905017/replies")
        assertThat(requests[0].sent).isEqualTo("""{"body":"Fixed in 7a1b."}""")
        assertThat(requests[1].sent).contains("resolveReviewThread(input: {threadId: \$id})")
        assertThat(requests[2].sent).contains("unresolveReviewThread(input: {threadId: \$id})")
        assertThat(requests[1].sent).contains(""""variables":{"id":"PRRT_kwDOLUle7M6vdxgm"}""")
    }

    @Test
    fun a_review_is_dismissed_with_why() = runTest {
        val result = api { json("""{"id":80,"state":"DISMISSED"}""") }.dismissReview("tok", pull, 80, "Outdated after the rewrite.")

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        assertThat("${requests.single().method.value} ${requests.single().path}").isEqualTo("PUT /repos/octo/tools/pulls/88/reviews/80/dismissals")
        assertThat(requests.single().sent).isEqualTo("""{"message":"Outdated after the rewrite.","event":"DISMISS"}""")
    }

    @Test
    fun a_merge_github_refuses_says_why() = runTest {
        val result = api { json("""{"message":"Pull Request is not mergeable"}""", HttpStatusCode.MethodNotAllowed) }.merge("tok", pull, MergeMethod.MERGE)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(405, "Pull Request is not mergeable")))
    }

    @Test
    fun a_review_goes_as_one_with_its_verdict_and_its_remarks_on_lines() = runTest {
        api { json("""{"id":1}""") }.review(
            "tok", pull, ReviewVerdict.REQUEST_CHANGES, "Two things.",
            listOf(LineComment("src/Upload.kt", oldLine = null, newLine = 12, body = "Why twice?"), LineComment("src/Upload.kt", oldLine = 7, newLine = null, body = "This was needed")),
        )

        assertThat(requests.single().method).isEqualTo(HttpMethod.Post)
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/pulls/88/reviews")
        assertThat(requests.single().sent).isEqualTo(
            """{"event":"REQUEST_CHANGES","body":"Two things.","comments":[""" +
                """{"path":"src/Upload.kt","line":12,"side":"RIGHT","body":"Why twice?"},""" +
                """{"path":"src/Upload.kt","line":7,"side":"LEFT","body":"This was needed"}]}""",
        )
    }

    @Test
    fun an_approval_can_go_with_nothing_said() = runTest {
        api { json("""{"id":1}""") }.review("tok", pull, ReviewVerdict.APPROVE, "", emptyList())

        assertThat(requests.single().sent).isEqualTo("""{"event":"APPROVE"}""")
    }

    @Test
    fun a_pull_request_is_opened_from_one_branch_into_another() = runTest {
        val opened = api { json("""{"number":91}""", HttpStatusCode.Created) }
            .create("tok", repo, NewPullRequest("Bump the SDK", "To 36.", head = "bump-sdk", base = "main", draft = true)).value()

        assertThat(opened).isEqualTo(IssueRef(repo, 91, isPullRequest = true))
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/pulls")
        assertThat(requests.single().sent).isEqualTo("""{"title":"Bump the SDK","body":"To 36.","head":"bump-sdk","base":"main","draft":true}""")
    }

    @Test
    fun a_history_is_read_from_a_ref_for_a_path_page_by_page() = runTest {
        val page = api { json(commitsJson, link = """<https://api.github.com/repositories/1/commits?page=3>; rel="next"""") }
            .history(null, repo, ref = "release/2", path = "src/Upload.kt", page = 2).value()

        assertThat(page.commits.map { it.shortSha }).containsExactly("1111111", "2222222").inOrder()
        assertThat(page.nextPage).isEqualTo(3)
        val request = requests.single()
        assertThat(request.path).isEqualTo("/repos/octo/tools/commits")
        assertThat(request.url.parameters["sha"]).isEqualTo("release/2")
        assertThat(request.url.parameters["path"]).isEqualTo("src/Upload.kt")
        assertThat(request.url.parameters["page"]).isEqualTo("2")
    }

    @Test
    fun the_default_branch_s_whole_history_asks_for_no_ref_and_no_path() = runTest {
        val page = api { json("[]") }.history(null, repo, ref = null, path = null).value()

        assertThat(page.commits).isEmpty()
        assertThat(page.nextPage).isNull()
        assertThat(requests.single().url.parameters.names()).containsExactly("per_page", "page")
    }

    @Test
    fun a_commit_comes_with_the_files_it_changed() = runTest {
        val details = api {
            json(
                """{"sha":"1111111aaaaaaa","commit":{"message":"Retry uploads","author":{"name":"The Octocat","date":"2026-09-26T08:00:00Z"}},"author":{"login":"octocat"},
                    "files":[{"filename":"src/Upload.kt","status":"modified","additions":1,"deletions":1,"patch":"@@ -1 +1 @@\n-a\n+b"}]}""",
            )
        }.commit(null, repo, "1111111aaaaaaa").value()

        assertThat(details.commit.title).isEqualTo("Retry uploads")
        assertThat(details.files.single().path).isEqualTo("src/Upload.kt")
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools/commits/1111111aaaaaaa")
    }

    @Test
    fun a_pull_request_that_is_not_there_is_an_http_404() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.files(null, pull)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun blame_is_asked_of_the_graphql_api_and_says_who_last_left_each_run_of_lines() = runTest {
        val api = api {
            json(
                """{"data":{"repository":{"target":{"blame":{"ranges":[
                    {"startingLine":1,"endingLine":3,"commit":{"oid":"1111111aaaaaaa","message":"Retry uploads\n\nOn slow links.","authoredDate":"2026-09-26T08:00:00Z","author":{"name":"The Octocat","user":{"login":"octocat","avatarUrl":"https://a/1"}}}},
                    {"startingLine":4,"endingLine":4,"commit":{"oid":"2222222bbbbbbb","message":"Fix typo","authoredDate":"2026-09-27T08:00:00Z","author":{"name":"Someone Else","user":null}}}
                ]}}}}}""",
            )
        }

        val blame = api.blame("tok", repo, "main", "src/Upload.kt").value()

        assertThat(api.supportsBlame).isTrue()
        assertThat(blame.map { Triple(it.startLine, it.endLine, it.commit.title) }).containsExactly(Triple(1, 3, "Retry uploads"), Triple(4, 4, "Fix typo")).inOrder()
        assertThat(blame[0].commit.author).isEqualTo(ForgeUser("octocat", null, "https://a/1"))
        assertThat(blame[1].commit.authorName).isEqualTo("Someone Else")
        assertThat(requests.single().path).isEqualTo("/graphql")
        assertThat(requests.single().sent).contains(""""variables":{"owner":"octo","name":"tools","ref":"main","path":"src/Upload.kt"}""")
    }

    @Test
    fun blame_is_for_signed_in_users_and_a_file_that_is_not_there_is_a_404() = runTest {
        // GitHub's GraphQL API takes no anonymous call: nothing is sent.
        assertThat(api { json("{}") }.blame(null, repo, "main", "a.kt")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(requests).isEmpty()

        val missing = api { json("""{"data":{"repository":{"target":null}}}""") }.blame("tok", repo, "nope", "a.kt")
        assertThat(missing).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, null)))
    }
}
