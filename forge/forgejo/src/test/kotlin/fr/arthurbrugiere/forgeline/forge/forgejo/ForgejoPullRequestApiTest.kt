package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Answers are cut down from codeberg.org's for forgejo/forgejo#14792, read on 2026-10-10. */
class ForgejoPullRequestApiTest {
    private val codeberg = Codeberg()
    private val repo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)
    private val pull = IssueRef(repo, 14792, isPullRequest = true)

    private fun api(route: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(String) -> io.ktor.client.request.HttpResponseData) =
        ForgejoPullRequestApi(codeberg.client { route(it.url.encodedPath) }, ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val base = "/api/v1/repos/forgejo/forgejo"

    private val diff = """
        diff --git a/.forgejo/workflows/testing.yml b/.forgejo/workflows/testing.yml
        index f9b9fd365d..bb74ca2abc 100644
        --- a/.forgejo/workflows/testing.yml
        +++ b/.forgejo/workflows/testing.yml
        @@ -343,3 +343,3 @@ jobs:
                   make coverage-show-percentage
        -        uses: unkai@2889dd # v0.2.17
        +        uses: unkai@38c76b # v0.2.18
        diff --git a/release-notes/14792.md b/release-notes/14792.md
        new file mode 100644
        index 0000000000..1111111111
        --- /dev/null
        +++ b/release-notes/14792.md
        @@ -0,0 +1 @@
        +Update unkai
    """.trimIndent() + "\n"

    @Test
    fun the_files_of_a_pull_request_are_read_from_its_whole_diff_in_one_request() = runTest {
        // Forgejo's own list of files names them without their changes.
        val files = with(codeberg) { api { text(diff) } }.files(null, pull).value()

        assertThat(files.files.map { it.path to it.change })
            .containsExactly(".forgejo/workflows/testing.yml" to FileChange.MODIFIED, "release-notes/14792.md" to FileChange.ADDED).inOrder()
        assertThat(files.files[0].additions to files.files[0].deletions).isEqualTo(1 to 1)
        assertThat(files.files[0].patch).startsWith("@@ -343,3 +343,3 @@ jobs:")
        assertThat(files.nextPage).isNull()
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("$base/pulls/14792.diff")
    }

    @Test
    fun there_is_no_second_page_of_files_to_ask_for() = runTest {
        val files = with(codeberg) { api { text(diff) } }.files(null, pull, page = 2).value()

        assertThat(files.files).isEmpty()
        assertThat(codeberg.requests).isEmpty()
    }

    private val commitsJson = """[
        {"sha":"cb7511ae7cd7be","created":"2026-10-10T03:39:59Z","commit":{"message":"Update unkai to v0.2.18\n","author":{"name":"Renovate Bot","email":"bot@kriese.eu","date":"2026-10-10T03:39:59Z"}},
         "author":{"id":1,"login":"viceice-bot","full_name":"","avatar_url":"https://codeberg.org/avatars/1"}},
        {"sha":"a278fd0507debd","commit":{"message":"Tidy","author":{"name":"Someone","date":"2026-10-10T05:00:00+02:00"}},"author":null}
    ]"""

    @Test
    fun the_commits_of_a_pull_request_are_asked_for_without_what_slows_them() = runTest {
        val commits = with(codeberg) { api { json(commitsJson) } }.commits(null, pull).value()

        assertThat(commits.map { it.title }).containsExactly("Update unkai to v0.2.18", "Tidy").inOrder()
        assertThat(commits[0].author?.login).isEqualTo("viceice-bot")
        assertThat(commits[0].authorName).isEqualTo("Renovate Bot")
        assertThat(commits[1].date).isEqualTo(Instant.parse("2026-10-10T03:00:00Z"))
        val request = codeberg.requests.single()
        assertThat(request.url.encodedPath).isEqualTo("$base/pulls/14792/commits")
        assertThat(listOf("stat", "verification", "files").map { request.url.parameters[it] }).containsExactly("false", "false", "false")
    }

    @Test
    fun checks_are_the_statuses_of_the_latest_commit() = runTest {
        val checks = with(codeberg) {
            api { path ->
                if (path.endsWith("/pulls/14792")) {
                    json("""{"number":14792,"head":{"sha":"cb7511ae"},"mergeable":true}""")
                } else {
                    json(
                        """{"state":"success","statuses":[
                            {"context":"pr-checklist / warn-user (pull_request_target)","status":"skipped","description":"Has been skipped","target_url":"/forgejo/forgejo/actions/runs/206160/jobs/0"},
                            {"context":"testing / backend-checks (pull_request)","status":"success","description":"Successful in 3m","target_url":"/forgejo/forgejo/actions/runs/206161/jobs/1"},
                            {"context":"testing / test-e2e (pull_request)","status":"pending","description":"","target_url":""},
                            {"context":"external/coverage","status":"failure","description":"Below 80%","target_url":"https://cov.example/1"}
                        ]}""",
                    )
                }
            }
        }.checks(null, pull).value()

        assertThat(checks).containsExactly(
            // Forgejo's own Actions name their page without the host.
            Check("pr-checklist / warn-user (pull_request_target)", CheckState.SKIPPED, "Has been skipped", "https://codeberg.org/forgejo/forgejo/actions/runs/206160/jobs/0"),
            Check("testing / backend-checks (pull_request)", CheckState.SUCCESS, "Successful in 3m", "https://codeberg.org/forgejo/forgejo/actions/runs/206161/jobs/1"),
            Check("testing / test-e2e (pull_request)", CheckState.PENDING, null, null),
            Check("external/coverage", CheckState.FAILURE, "Below 80%", "https://cov.example/1"),
        ).inOrder()
        assertThat(codeberg.requests.last().url.encodedPath).isEqualTo("$base/commits/cb7511ae/status")
    }

    @Test
    fun a_commit_nothing_ran_on_has_no_checks() = runTest {
        val checks = with(codeberg) {
            api { path -> if (path.endsWith("/pulls/14792")) json("""{"head":{"sha":"cb7511ae"}}""") else json("""{"state":"","statuses":null}""") }
        }.checks(null, pull).value()

        assertThat(checks).isEmpty()
    }

    @Test
    fun merging_offers_the_repository_s_ways_its_own_choice_first() = runTest {
        val info = with(codeberg) {
            api { path ->
                if (path.endsWith("/pulls/14792")) {
                    json("""{"head":{"sha":"cb7511ae"},"mergeable":true}""")
                } else {
                    json("""{"allow_merge_commits":true,"allow_squash_merge":true,"allow_rebase":false,"default_merge_style":"squash","permissions":{"admin":false,"push":true,"pull":true}}""")
                }
            }
        }.mergeInfo("tok", pull).value()

        assertThat(info).isEqualTo(MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.SQUASH, MergeMethod.MERGE)))
    }

    @Test
    fun a_merge_says_how() = runTest {
        with(codeberg) { api { status(HttpStatusCode.OK) } }.merge("tok", pull, MergeMethod.REBASE)

        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("$base/pulls/14792/merge")
        assertThat((request.body as TextContent).text).isEqualTo("""{"Do":"rebase"}""")
    }

    @Test
    fun a_review_goes_as_one_with_each_remark_on_its_side_of_the_change() = runTest {
        with(codeberg) { api { json("""{"id":1}""") } }.review(
            "tok", pull, ReviewVerdict.APPROVE, "Looks right.",
            listOf(
                LineComment("a.go", oldLine = null, newLine = 12, body = "Added"),
                LineComment("a.go", oldLine = 7, newLine = null, body = "Removed"),
                LineComment("a.go", oldLine = 3, newLine = 4, body = "Left alone"),
            ),
        )

        val request = codeberg.requests.single()
        assertThat(request.url.encodedPath).isEqualTo("$base/pulls/14792/reviews")
        assertThat((request.body as TextContent).text).isEqualTo(
            """{"event":"APPROVED","body":"Looks right.","comments":[""" +
                """{"path":"a.go","body":"Added","new_position":12,"old_position":0},""" +
                """{"path":"a.go","body":"Removed","new_position":0,"old_position":7},""" +
                // A line the change leaves alone is in the file after it.
                """{"path":"a.go","body":"Left alone","new_position":4,"old_position":0}]}""",
        )
    }

    @Test
    fun the_other_verdicts_have_forgejo_s_own_names() = runTest {
        val api = with(codeberg) { api { json("""{"id":1}""") } }

        api.review("tok", pull, ReviewVerdict.REQUEST_CHANGES, "No.", emptyList())
        api.review("tok", pull, ReviewVerdict.COMMENT, "Hm.", emptyList())

        assertThat(codeberg.requests.map { (it.body as TextContent).text }).containsExactly(
            """{"event":"REQUEST_CHANGES","body":"No.","comments":[]}""", """{"event":"COMMENT","body":"Hm.","comments":[]}""",
        ).inOrder()
    }

    @Test
    fun a_draft_is_opened_with_a_title_that_says_so() = runTest {
        val opened = with(codeberg) { api { json("""{"number":14800}""", HttpStatusCode.Created) } }
            .create("tok", repo, NewPullRequest("Fix the runner", "", head = "fix-runner", base = "forgejo", draft = true)).value()

        assertThat(opened).isEqualTo(IssueRef(repo, 14800, isPullRequest = true))
        assertThat((codeberg.requests.single().body as TextContent).text)
            .isEqualTo("""{"title":"WIP: Fix the runner","body":"","head":"fix-runner","base":"forgejo"}""")
    }

    @Test
    fun a_pull_request_that_is_not_a_draft_keeps_its_title() = runTest {
        with(codeberg) { api { json("""{"number":1}""") } }.create("tok", repo, NewPullRequest("Fix", "Why", "a", "b"))

        assertThat((codeberg.requests.single().body as TextContent).text).isEqualTo("""{"title":"Fix","body":"Why","head":"a","base":"b"}""")
    }

    @Test
    fun a_history_is_read_from_a_ref_for_a_path_page_by_page() = runTest {
        val page = with(codeberg) {
            api { json(commitsJson, headers = mapOf("Link" to """<https://codeberg.org/api/v1/repos/forgejo/forgejo/commits?limit=30&page=3>; rel="next"""")) }
        }.history(null, repo, ref = "v12.0/forgejo", path = "go.mod", page = 2).value()

        assertThat(page.commits).hasSize(2)
        assertThat(page.nextPage).isEqualTo(3)
        val request = codeberg.requests.single()
        assertThat(request.url.encodedPath).isEqualTo("$base/commits")
        assertThat(request.url.parameters["sha"]).isEqualTo("v12.0/forgejo")
        assertThat(request.url.parameters["path"]).isEqualTo("go.mod")
        assertThat(request.url.parameters["page"]).isEqualTo("2")
    }

    @Test
    fun a_history_s_last_page_has_none_after_it() = runTest {
        val page = with(codeberg) { api { json(commitsJson, headers = mapOf("Link" to """<https://codeberg.org/x?page=1>; rel="first"""")) } }
            .history(null, repo, ref = null, path = null).value()

        assertThat(page.nextPage).isNull()
    }

    @Test
    fun a_commit_is_read_with_its_diff_beside_it() = runTest {
        val details = with(codeberg) {
            api { path -> if (path.endsWith(".diff")) text(diff) else json("""{"sha":"cb7511ae7cd7be","commit":{"message":"Update unkai","author":{"name":"Renovate Bot"}}}""") }
        }.commit(null, repo, "cb7511ae7cd7be").value()

        assertThat(details.commit.title).isEqualTo("Update unkai")
        assertThat(details.files.map { it.path }).containsExactly(".forgejo/workflows/testing.yml", "release-notes/14792.md").inOrder()
        assertThat(codeberg.requests.map { it.url.encodedPath }).containsExactly("$base/git/commits/cb7511ae7cd7be", "$base/git/commits/cb7511ae7cd7be.diff")
    }

    @Test
    fun forgejo_has_no_blame_to_ask_for() = runTest {
        // Its API serves no blame (checked against Codeberg's description, 2026-10-10): the app doesn't offer it there.
        val api = with(codeberg) { api { status(HttpStatusCode.NotFound) } }

        assertThat(api.supportsBlame).isFalse()
        assertThat(api.blame(null, repo, "forgejo", "go.mod")).isEqualTo(ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported))
        assertThat(codeberg.requests).isEmpty()
    }
}
