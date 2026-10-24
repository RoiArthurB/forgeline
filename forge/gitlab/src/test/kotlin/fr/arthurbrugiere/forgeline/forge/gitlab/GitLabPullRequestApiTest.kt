package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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

/** Answers are cut down from gitlab.com's for gitlab-org/gitlab-runner!7606, read on 2026-10-10. */
class GitLabPullRequestApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("gitlab-org", "gitlab-runner", ForgeInstance.GitLab)
    private val mr = IssueRef(repo, 7606, isPullRequest = true)
    private val base = "/api/v4/projects/gitlab-org%2Fgitlab-runner"

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, nextPage: String? = null) =
        respond(body, status, headersOf(*listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), nextPage?.let { "X-Next-Page" to listOf(it) }).toTypedArray()))

    private fun api(handler: suspend MockRequestHandleScope.(String) -> HttpResponseData) =
        GitLabPullRequestApi(gitlabHttpClient(MockEngine { requests += it; handler(it.url.encodedPath) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.sent get() = (body as TextContent).text

    private val mrJson = """{"iid":7606,"merge_status":"can_be_merged","detailed_merge_status":"mergeable",
        "diff_refs":{"base_sha":"4718911","head_sha":"cb7511a","start_sha":"a278fd0"},
        "head_pipeline":{"id":2932580967,"status":"running","web_url":"https://gitlab.com/gitlab-org/gitlab-runner/-/pipelines/2932580967"},"user":{"can_merge":true}}"""

    @Test
    fun the_files_of_a_merge_request_are_counted_from_their_changes() = runTest {
        val files = api {
            json(
                """[
                    {"old_path":"docs/a.md","new_path":"docs/a.md","diff":"@@ -223,2 +223,3 @@ image\n In this ex\n-old\n+new\n+more\n","new_file":false,"renamed_file":false,"deleted_file":false},
                    {"old_path":"x.go","new_path":"x.go","diff":"@@ -0,0 +1 @@\n+package x\n","new_file":true,"renamed_file":false,"deleted_file":false},
                    {"old_path":"old.go","new_path":"new.go","diff":"","new_file":false,"renamed_file":true,"deleted_file":false},
                    {"old_path":"gone.go","new_path":"gone.go","diff":"@@ -1 +0,0 @@\n-package gone\n","new_file":false,"renamed_file":false,"deleted_file":true}
                ]""",
                nextPage = "2",
            )
        }.files(null, mr).value()

        assertThat(files.files.map { it.path to it.change }).containsExactly(
            "docs/a.md" to FileChange.MODIFIED, "x.go" to FileChange.ADDED, "new.go" to FileChange.RENAMED, "gone.go" to FileChange.REMOVED,
        ).inOrder()
        // GitLab sends the change, not how many lines it adds and removes.
        assertThat(files.files.map { it.additions to it.deletions }).containsExactly(2 to 1, 1 to 0, 0 to 0, 0 to 1).inOrder()
        assertThat(files.files[2].previousPath).isEqualTo("old.go")
        assertThat(files.files[2].patch).isNull()
        assertThat(files.nextPage).isEqualTo(2)
        assertThat(requests.single().url.encodedPath).isEqualTo("$base/merge_requests/7606/diffs")
    }

    @Test
    fun the_commits_of_a_merge_request_read_oldest_first() = runTest {
        val commits = api {
            json(
                """[
                    {"id":"cb7511a","title":"Second","message":"Second\n","author_name":"Ada","authored_date":"2026-10-10T05:00:00.000+02:00"},
                    {"id":"a278fd0","title":"First","message":"First\n\nWhy.\n","author_name":"Ada","authored_date":"2026-10-09T10:00:00.000Z"}
                ]""",
            )
        }.commits(null, mr).value()

        // GitLab lists them newest first; a pull request reads in the order it was built.
        assertThat(commits.map { it.title }).containsExactly("First", "Second").inOrder()
        assertThat(commits[0].description).isEqualTo("Why.")
        assertThat(commits[0].authorName).isEqualTo("Ada")
        assertThat(commits[1].date).isEqualTo(Instant.parse("2026-10-10T03:00:00Z"))
    }

    @Test
    fun checks_are_the_jobs_of_the_latest_pipeline() = runTest {
        val checks = api { path ->
            if (path.endsWith("/jobs")) {
                json(
                    """[
                        {"id":3,"name":"deploy","status":"manual","stage":"release","web_url":"https://gitlab.com/j/3"},
                        {"id":1,"name":"build","status":"success","stage":"build","web_url":"https://gitlab.com/j/1"},
                        {"id":2,"name":"test","status":"failed","stage":"test","web_url":"https://gitlab.com/j/2"},
                        {"id":4,"name":"docs","status":"running","stage":"test","web_url":"https://gitlab.com/j/4"}
                    ]""",
                )
            } else {
                json(mrJson)
            }
        }.checks("tok", mr).value()

        // In the order they were made, each leading to the pipeline, which the app shows as a run.
        assertThat(checks).containsExactly(
            Check("build", CheckState.SUCCESS, "build", "https://gitlab.com/j/1", runId = 2932580967),
            Check("test", CheckState.FAILURE, "test", "https://gitlab.com/j/2", runId = 2932580967),
            Check("deploy", CheckState.SKIPPED, "release", "https://gitlab.com/j/3", runId = 2932580967),
            Check("docs", CheckState.PENDING, "test", "https://gitlab.com/j/4", runId = 2932580967),
        ).inOrder()
        assertThat(requests.last().url.encodedPath).isEqualTo("$base/pipelines/2932580967/jobs")
    }

    @Test
    fun a_pipeline_whose_jobs_cannot_be_listed_stands_for_them() = runTest {
        // Seen signed out on gitlab.com, 2026-10-10: the merge request names its pipeline, the jobs answer 404.
        val checks = api { path -> if (path.endsWith("/jobs")) json("""{"message":"404 Not found"}""", HttpStatusCode.NotFound) else json(mrJson) }
            .checks(null, mr).value()

        assertThat(checks).containsExactly(Check("Pipeline", CheckState.PENDING, null, "https://gitlab.com/gitlab-org/gitlab-runner/-/pipelines/2932580967"))
    }

    @Test
    fun a_merge_request_without_a_pipeline_has_no_checks() = runTest {
        assertThat(api { json("""{"iid":7606,"head_pipeline":null}""") }.checks(null, mr).value()).isEmpty()
    }

    @Test
    fun merging_offers_to_squash_or_not_as_the_project_prefers() = runTest {
        val info = api { path -> if (path.endsWith("/7606")) json(mrJson) else json("""{"squash_option":"default_on","merge_method":"ff"}""") }.mergeInfo("tok", mr).value()

        // Its pipeline still runs: it can be merged once that passes.
        assertThat(info).isEqualTo(MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.SQUASH, MergeMethod.MERGE), canMergeWhenChecksPass = true))
    }

    @Test
    fun a_branch_of_the_project_goes_with_the_merge_as_the_merge_request_or_the_project_says() = runTest {
        val own = mrJson.replace(""""user":{"can_merge":true}}""", """"user":{"can_merge":true},"source_project_id":250833,"target_project_id":250833}""")
        suspend fun deleting(request: String, project: String) = api { path -> if (path.endsWith("/7606")) json(request) else json(project) }.mergeInfo("tok", mr).value().deleteBranch

        assertThat(deleting(own, """{"remove_source_branch_after_merge":true}""")).isTrue()
        assertThat(deleting(own.replace(""""source_project_id""", """"force_remove_source_branch":false,"source_project_id"""), """{"remove_source_branch_after_merge":true}""")).isFalse()
        assertThat(deleting(own, "{}")).isFalse()
        // A fork's branch isn't the project's.
        assertThat(deleting(own.replace(""""source_project_id":250833""", """"source_project_id":99"""), """{"remove_source_branch_after_merge":true}""")).isNull()
    }

    @Test
    fun a_finished_pipeline_leaves_nothing_to_wait_for_and_a_merge_set_for_it_says_so() = runTest {
        val passed = mrJson.replace(""""status":"running""", """"status":"success""")
        val armed = mrJson.replace(""""user":{"can_merge":true}}""", """"user":{"can_merge":true},"merge_when_pipeline_succeeds":true}""")

        val done = api { path -> if (path.endsWith("/7606")) json(passed) else json("{}") }.mergeInfo("tok", mr).value()
        val waiting = api { path -> if (path.endsWith("/7606")) json(armed) else json("{}") }.mergeInfo("tok", mr).value()

        assertThat(done.canMergeWhenChecksPass).isFalse()
        assertThat(waiting.mergesWhenChecksPass).isTrue()
    }

    @Test
    fun a_merge_request_behind_its_target_is_rebased_onto_it() = runTest {
        val behind = mrJson.replace(""""user":{"can_merge":true}}""", """"user":{"can_merge":true},"diverged_commits_count":3}""")
        val info = api { path -> if (path.endsWith("/7606")) json(behind) else json("{}") }.mergeInfo("tok", mr).value()
        assertThat(info.behind).isTrue()
        // The count is only given when asked.
        assertThat(requests.first { it.url.encodedPath.endsWith("/7606") }.url.parameters["include_diverged_commits_count"]).isEqualTo("true")
        requests.clear()

        api { json("""{"rebase_in_progress":true}""", HttpStatusCode.Accepted) }.updateBranch("tok", mr)

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PUT $base/merge_requests/7606/rebase")
    }

    @Test
    fun a_merge_request_gitlab_says_needs_a_rebase_is_behind_and_one_up_to_date_isn_t() = runTest {
        suspend fun behind(extra: String) = api { path ->
            if (path.endsWith("/7606")) json("""{"detailed_merge_status":"$extra","user":{"can_merge":true},"diverged_commits_count":0}""") else json("{}")
        }.mergeInfo("tok", mr).value().behind

        assertThat(behind("need_rebase")).isTrue()
        assertThat(behind("mergeable")).isFalse()
    }

    @Test
    fun a_merge_can_delete_its_branch_wait_for_the_pipeline_and_be_called_off() = runTest {
        val api = api { json("{}") }

        api.merge("tok", mr, MergeMethod.MERGE, deleteBranch = true, whenChecksPass = true)
        api.cancelMergeWhenChecksPass("tok", mr)

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "PUT $base/merge_requests/7606/merge", "POST $base/merge_requests/7606/cancel_merge_when_pipeline_succeeds",
        ).inOrder()
        assertThat(requests.first().sent).isEqualTo("""{"squash":false,"should_remove_source_branch":true,"merge_when_pipeline_succeeds":true}""")
    }

    @Test
    fun what_blocks_a_merge_request_makes_it_not_mergeable_and_a_check_under_way_not_known() = runTest {
        suspend fun mergeable(status: String) = api { path ->
            if (path.endsWith("/7606")) json("""{"detailed_merge_status":"$status","user":{"can_merge":false}}""") else json("""{"squash_option":"never"}""")
        }.mergeInfo("tok", mr).value()

        assertThat(mergeable("not_approved")).isEqualTo(MergeInfo(false, false, listOf(MergeMethod.MERGE), blockers = listOf(MergeBlocker.REVIEWS)))
        assertThat(mergeable("conflict").mergeable).isFalse()
        assertThat(mergeable("checking").mergeable).isNull()
    }

    @Test
    fun gitlab_s_detailed_merge_status_says_what_blocks_the_merge() {
        assertThat(mergeBlockerOf("conflict")).isEqualTo(MergeBlocker.CONFLICTS)
        assertThat(mergeBlockerOf("ci_must_pass")).isEqualTo(MergeBlocker.CHECKS)
        assertThat(mergeBlockerOf("ci_still_running")).isEqualTo(MergeBlocker.CHECKS)
        assertThat(mergeBlockerOf("requested_changes")).isEqualTo(MergeBlocker.CHANGES_REQUESTED)
        assertThat(mergeBlockerOf("discussions_not_resolved")).isEqualTo(MergeBlocker.THREADS)
        assertThat(mergeBlockerOf("need_rebase")).isEqualTo(MergeBlocker.BEHIND)
        assertThat(mergeBlockerOf("locked_paths")).isEqualTo(MergeBlocker.OTHER)
        // A draft says so itself, and what is being worked out blocks nothing yet.
        assertThat(mergeBlockerOf("draft_status")).isNull()
        assertThat(mergeBlockerOf("checking")).isNull()
        assertThat(mergeBlockerOf("mergeable")).isNull()
    }

    // Real discussions of gitlab-org/gitlab-runner!7661, captured 2026-10-23 (three on lines, one not), cut to four.
    private val discussions = requireNotNull(javaClass.getResource("/gitlab/captured/merge_request_discussions.json")).readText()

    @Test
    fun the_discussions_on_lines_are_the_threads_and_the_rest_is_left_to_the_conversation() = runTest {
        val threads = api { json(discussions) }.threads("tok", mr).value()

        assertThat(threads).hasSize(3)
        assertThat(threads.map { it.id.take(10) }).containsExactly("8a3e89c007", "fa8639895c", "a107b496e7").inOrder()
        assertThat(threads.map { it.line }).containsExactly(777, 804, 823).inOrder()
        assertThat(threads.all { it.path == "docs/executors/kubernetes/_index.md" && !it.onOldSide && it.resolved && it.canResolve }).isTrue()
        assertThat(threads.first().comments.single().body).isNotEmpty()
        assertThat(requests.single().url.encodedPath).isEqualTo("$base/merge_requests/7606/discussions")
    }

    @Test
    fun signed_out_nothing_can_be_resolved() = runTest {
        val threads = api { json(discussions) }.threads(null, mr).value()

        assertThat(threads.none { it.canResolve }).isTrue()
    }

    @Test
    fun a_reply_and_resolving_go_to_the_discussion() = runTest {
        val thread = api { json(discussions) }.threads("tok", mr).value().first()
        requests.clear()
        val acting = api { json("{}") }

        acting.reply("tok", mr, thread, "Done.")
        acting.setResolved("tok", mr, thread, resolved = false)

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "POST $base/merge_requests/7606/discussions/${thread.id}/notes",
            "PUT $base/merge_requests/7606/discussions/${thread.id}",
        ).inOrder()
        assertThat(requests[0].sent).isEqualTo("""{"body":"Done."}""")
        assertThat(requests[1].url.parameters["resolved"]).isEqualTo("false")
    }

    @Test
    fun gitlab_can_t_dismiss_someone_else_s_review() = runTest {
        val api = api { json("{}") }

        assertThat(api.dismissesReviews).isFalse()
        assertThat(api.dismissReview("tok", mr, 1, "no")).isEqualTo(ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported))
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_merge_says_whether_to_squash() = runTest {
        val api = api { json("{}") }

        api.merge("tok", mr, MergeMethod.SQUASH)
        api.merge("tok", mr, MergeMethod.MERGE)

        assertThat(requests.map { it.method }.distinct()).containsExactly(HttpMethod.Put)
        assertThat(requests.first().url.encodedPath).isEqualTo("$base/merge_requests/7606/merge")
        assertThat(requests.map { it.sent }).containsExactly(
            """{"squash":true,"should_remove_source_branch":false}""", """{"squash":false,"should_remove_source_branch":false}""",
        ).inOrder()
    }

    @Test
    fun a_review_sends_its_remarks_then_what_it_says_then_the_approval() = runTest {
        val result = api { path -> if (path.endsWith("/7606")) json(mrJson) else json("{}", HttpStatusCode.Created) }.review(
            "tok", mr, ReviewVerdict.APPROVE, "Thanks!",
            listOf(
                LineComment("new.go", oldLine = null, newLine = 12, body = "Added", previousPath = "old.go"),
                LineComment("a.go", oldLine = 3, newLine = 4, body = "Left alone"),
            ),
        )

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        assertThat(requests.map { it.url.encodedPath.removePrefix("$base/merge_requests/7606") })
            .containsExactly("", "/discussions", "/discussions", "/notes", "/approve").inOrder()
        // Each remark is placed by the commits the change runs between, and by the line's number on each side it is on.
        assertThat(requests[1].sent).isEqualTo(
            """{"body":"Added","position":{"position_type":"text","base_sha":"4718911","start_sha":"a278fd0","head_sha":"cb7511a","new_path":"new.go","old_path":"old.go","new_line":12}}""",
        )
        assertThat(requests[2].sent).contains(""""new_path":"a.go","old_path":"a.go","new_line":4,"old_line":3""")
        assertThat(requests[3].sent).isEqualTo("""{"body":"Thanks!"}""")
    }

    @Test
    fun a_comment_with_nothing_on_lines_is_one_note() = runTest {
        api { json("{}", HttpStatusCode.Created) }.review("tok", mr, ReviewVerdict.COMMENT, "Hm.", emptyList())

        assertThat(requests.single().url.encodedPath).isEqualTo("$base/merge_requests/7606/notes")
    }

    @Test
    fun an_approval_is_not_given_when_what_came_with_it_could_not_be_sent() = runTest {
        val result = api { path -> if (path.endsWith("/notes")) json("{}", HttpStatusCode.Forbidden) else json("{}") }
            .review("tok", mr, ReviewVerdict.APPROVE, "Thanks!", emptyList())

        assertThat(result).isInstanceOf(ForgeResult.Failure::class.java)
        assertThat(requests.map { it.url.encodedPath }.none { it.endsWith("/approve") }).isTrue()
    }

    @Test
    fun gitlab_s_api_cannot_ask_for_changes() = runTest {
        val api = api { json("{}") }

        assertThat(api.verdicts).containsExactly(ReviewVerdict.COMMENT, ReviewVerdict.APPROVE)
        assertThat(api.review("tok", mr, ReviewVerdict.REQUEST_CHANGES, "No.", emptyList())).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_draft_is_opened_with_a_title_that_says_so() = runTest {
        val opened = api { json("""{"iid":7700}""", HttpStatusCode.Created) }
            .create("tok", repo, NewPullRequest("Fix the cache", "Why.", head = "fix-cache", base = "main", draft = true)).value()

        assertThat(opened).isEqualTo(IssueRef(repo, 7700, isPullRequest = true))
        assertThat(requests.single().url.encodedPath).isEqualTo("$base/merge_requests")
        assertThat(requests.single().sent)
            .isEqualTo("""{"title":"Draft: Fix the cache","description":"Why.","source_branch":"fix-cache","target_branch":"main"}""")
    }

    @Test
    fun a_history_is_read_from_a_ref_for_a_path_page_by_page() = runTest {
        val page = api { json("""[{"id":"cb7511a","title":"Second","message":"Second\n","author_name":"Ada"}]""", nextPage = "3") }
            .history(null, repo, ref = "17-0-stable", path = "go.mod", page = 2).value()

        assertThat(page.commits.single().title).isEqualTo("Second")
        assertThat(page.nextPage).isEqualTo(3)
        val request = requests.single()
        assertThat(request.url.encodedPath).isEqualTo("$base/repository/commits")
        assertThat(request.url.parameters["ref_name"]).isEqualTo("17-0-stable")
        assertThat(request.url.parameters["path"]).isEqualTo("go.mod")
        assertThat(request.url.parameters["page"]).isEqualTo("2")
    }

    @Test
    fun a_commit_is_read_with_its_diff_beside_it() = runTest {
        val details = api { path ->
            if (path.endsWith("/diff")) {
                json("""[{"old_path":"x.go","new_path":"x.go","diff":"@@ -1 +1 @@\n-a\n+b\n"}]""")
            } else {
                json("""{"id":"cb7511a","title":"Second","message":"Second\n","author_name":"Ada"}""")
            }
        }.commit(null, repo, "cb7511a").value()

        assertThat(details.commit.sha).isEqualTo("cb7511a")
        assertThat(details.files.single().additions).isEqualTo(1)
        assertThat(requests.map { it.url.encodedPath }).containsExactly("$base/repository/commits/cb7511a", "$base/repository/commits/cb7511a/diff")
    }

    @Test
    fun blame_numbers_the_runs_of_lines_gitlab_answers() = runTest {
        val api = api {
            json(
                """[
                    {"commit":{"id":"a278fd0","message":"First\n","author_name":"Ada","authored_date":"2026-10-09T10:00:00.000Z"},"lines":["package x","","import a"]},
                    {"commit":{"id":"cb7511a","message":"Second\n","author_name":"Grace"},"lines":["func main() {}"]},
                    {"commit":{"id":"a278fd0","message":"First\n","author_name":"Ada"},"lines":["// end","// of file"]}
                ]""",
            )
        }

        val blame = api.blame(null, repo, "main", "cmd/run main.go").value()

        assertThat(api.supportsBlame).isTrue()
        assertThat(blame.map { Triple(it.startLine, it.endLine, it.commit.shortSha) })
            .containsExactly(Triple(1, 3, "a278fd0"), Triple(4, 4, "cb7511a"), Triple(5, 6, "a278fd0")).inOrder()
        assertThat(blame[1].commit.authorName).isEqualTo("Grace")
        // The path is one segment of the address, whatever it holds.
        assertThat(requests.single().url.encodedPath).isEqualTo("$base/repository/files/cmd%2Frun%20main.go/blame")
        assertThat(requests.single().url.parameters["ref"]).isEqualTo("main")
    }

    // gitlab-org/gitlab-runner's merge request templates, 2026-10-24: listed by name, and the Default one's text.
    private fun captured(name: String) = requireNotNull(javaClass.getResource("/gitlab/captured/$name")).readText()

    @Test
    fun merge_request_templates_are_listed_by_name_and_one_s_text_is_asked_when_it_is_picked() = runTest {
        val api = api { path -> json(captured(if (path.endsWith("/merge_requests/Default")) "merge_request_template_default.json" else "merge_request_templates.json")) }

        val templates = api.pullRequestTemplates("tok", repo, "main").value()
        assertThat(templates.map { it.key }).containsExactly("Default", "Documentation", "Security Release").inOrder()
        assertThat(templates.all { it.body == null }).isTrue()
        val default = api.pullRequestTemplate("tok", repo, templates.first()).value()
        api.pullRequestTemplate("tok", repo, default)

        assertThat(default.body).contains("## What does this MR do?\n\n%{first_multiline_commit}")
        assertThat(requests.map { it.url.encodedPath }).containsExactly("$base/templates/merge_requests", "$base/templates/merge_requests/Default").inOrder()
    }
}
