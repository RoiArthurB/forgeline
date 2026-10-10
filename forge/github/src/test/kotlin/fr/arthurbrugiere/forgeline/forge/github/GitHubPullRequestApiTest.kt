package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
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
                        {"name":"deploy","status":"completed","conclusion":"skipped","details_url":"https://ci.example/9"}
                    ]}""",
                )
                else -> json("""{"state":"pending","statuses":[{"context":"coverage/total","state":"pending","description":"Waiting","target_url":"https://cov.example/1"}]}""")
            }
        }.checks("tok", pull).value()

        assertThat(checks).containsExactly(
            Check("build", CheckState.SUCCESS, "Built", "https://github.com/octo/tools/actions/runs/777/job/1", runId = 777),
            Check("test", CheckState.PENDING, null, "https://github.com/octo/tools/actions/runs/777/job/2", runId = 777),
            Check("lint", CheckState.FAILURE, null, "https://github.com/octo/tools/actions/runs/778/job/3", runId = 778),
            // Another service's check has a page, not a run the app can show.
            Check("deploy", CheckState.SKIPPED, null, "https://ci.example/9"),
            Check("coverage/total", CheckState.PENDING, "Waiting", "https://cov.example/1"),
        ).inOrder()
        assertThat(requests.map { it.path }).contains("/repos/octo/tools/commits/abc123/status")
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
