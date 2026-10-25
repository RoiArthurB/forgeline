package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
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
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.Base64

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

        assertThat(info).isEqualTo(MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.SQUASH, MergeMethod.MERGE), canMergeWhenChecksPass = true))
    }

    @Test
    fun a_branch_of_the_repository_goes_with_the_merge_as_the_repository_says_a_fork_s_never() = runTest {
        suspend fun deleting(headRepo: Long) = with(codeberg) {
            api { path ->
                if (path.endsWith("/pulls/14792")) {
                    json("""{"head":{"sha":"cb7511ae","repo_id":$headRepo},"base":{"sha":"aa","repo_id":1},"mergeable":true}""")
                } else {
                    json("""{"default_delete_branch_after_merge":true,"permissions":{"push":true}}""")
                }
            }
        }.mergeInfo("tok", pull).value().deleteBranch

        assertThat(deleting(headRepo = 1)).isTrue()
        assertThat(deleting(headRepo = 2)).isNull()
    }

    @Test
    fun what_the_target_has_that_the_branch_lacks_is_compared_and_the_branch_brought_up_to_date() = runTest {
        suspend fun behind(missing: Int) = with(codeberg) {
            api { path ->
                when {
                    path.endsWith("/pulls/14792") -> json("""{"head":{"sha":"cb","ref":"fix/retry","repo_id":1},"base":{"sha":"aa","ref":"main","repo_id":1},"mergeable":true}""")
                    "/compare/" in path -> json("""{"total_commits":$missing,"commits":[]}""")
                    else -> json("""{"permissions":{"push":true}}""")
                }
            }
        }.mergeInfo("tok", pull).value().behind

        assertThat(behind(2)).isTrue()
        assertThat(behind(0)).isFalse()
        // The branch's name keeps its slash: the comparison is the rest of the path.
        assertThat(codeberg.requests.first { "/compare/" in it.url.encodedPath }.url.encodedPath).isEqualTo("$base/compare/fix/retry...main")
        codeberg.requests.clear()

        with(codeberg) { api { status(HttpStatusCode.OK) } }.updateBranch("tok", pull)

        val update = codeberg.requests.single()
        assertThat("${update.method.value} ${update.url.encodedPath}").isEqualTo("POST $base/pulls/14792/update")
        assertThat(update.url.parameters["style"]).isEqualTo("merge")
    }

    @Test
    fun a_fork_s_branch_is_not_compared() = runTest {
        val info = with(codeberg) {
            api { path ->
                if (path.endsWith("/pulls/14792")) json("""{"head":{"sha":"cb","ref":"fix","repo_id":2},"base":{"sha":"aa","ref":"main","repo_id":1},"mergeable":true}""")
                else json("""{"permissions":{"push":true}}""")
            }
        }.mergeInfo("tok", pull).value()

        assertThat(info.behind).isFalse()
        assertThat(codeberg.requests.none { "/compare/" in it.url.encodedPath }).isTrue()
    }

    @Test
    fun a_merge_can_delete_its_branch_wait_for_the_checks_and_be_called_off() = runTest {
        val api = with(codeberg) { api { status(HttpStatusCode.OK) } }

        api.merge("tok", pull, MergeMethod.SQUASH, deleteBranch = true, whenChecksPass = true)
        api.cancelMergeWhenChecksPass("tok", pull)

        assertThat(codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("POST $base/pulls/14792/merge", "DELETE $base/pulls/14792/merge").inOrder()
        assertThat((codeberg.requests.first().body as TextContent).text).isEqualTo("""{"Do":"squash","delete_branch_after_merge":true,"merge_when_checks_succeed":true}""")
    }

    // Real reviews of forgejo/forgejo#14885 and their remarks, captured on Codeberg 2026-10-24: replies sit in the
    // reviews of whoever replied, and only a thread's first remark says who resolved it.
    private val threaded = IssueRef(repo, 14885, isPullRequest = true)

    private fun threadedApi() = with(codeberg) {
        api { path ->
            when {
                path.endsWith("/reviews") -> json(fixture("threads/reviews.json"))
                path.endsWith("/comments") -> json(fixture("threads/review_${path.split('/').dropLast(1).last()}.json"))
                else -> json("{}")
            }
        }
    }

    @Test
    fun remarks_are_gathered_from_every_review_into_threads_by_their_line() = runTest {
        val threads = threadedApi().threads(null, threaded).value()

        assertThat(threads.map { "${it.path.substringAfterLast('/')}:${it.line}" })
            .containsExactly("git_push_test.go:436", "git_test.go:886", "hook_pre_receive.go:598", "git_push_test.go:460").inOrder()
        assertThat(threads.map { it.comments.size }).containsExactly(1, 2, 2, 3).inOrder()
        assertThat(threads.map { it.resolved }).containsExactly(false, true, true, true).inOrder()
        assertThat(threads[3].comments.map { it.author?.login }).containsExactly("famfo-cb", "oliverpool", "famfo-cb").inOrder()
        assertThat(threads[1].hunk).startsWith("@@ -888,0 +883,4 @@")
        // Forgejo's API resolves nothing.
        assertThat(threads.none { it.canResolve || it.onOldSide }).isTrue()
        // Only the reviews that hold remarks are asked for them.
        assertThat(codeberg.requests.count { it.url.encodedPath.endsWith("/comments") }).isEqualTo(4)
    }

    @Test
    fun a_reply_is_a_remark_on_the_same_line_sent_as_a_review_and_a_review_is_dismissed_with_why() = runTest {
        val thread = threadedApi().threads("tok", threaded).value().first()
        codeberg.requests.clear()
        val acting = with(codeberg) { api { json("""{"id":1}""") } }

        acting.reply("tok", threaded, thread, "Thanks!")
        acting.dismissReview("tok", threaded, 2117654, "Done differently.")

        assertThat(codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "POST $base/pulls/14885/reviews", "POST $base/pulls/14885/reviews/2117654/dismissals",
        ).inOrder()
        val reply = (codeberg.requests[0].body as TextContent).text
        assertThat(reply).contains(""""event":"COMMENT""")
        assertThat(reply).contains(""""path":"tests/integration/git_push_test.go""")
        assertThat(reply).contains(""""new_position":436""")
        assertThat(reply).contains(""""body":"Thanks!""")
        assertThat((codeberg.requests[1].body as TextContent).text).isEqualTo("""{"message":"Done differently."}""")
    }

    @Test
    fun a_remark_on_a_line_is_rewritten_as_the_comment_it_is_but_never_said_to_be_deleted() = runTest {
        // Forgejo keeps a remark on a line as an issue comment: its endpoint rewrites it, and answers 204 to deleting
        // it while keeping it (Codeberg, 2026-10-25).
        val thread = threadedApi().threads("tok", threaded).value().first()
        val comment = thread.comments.first()
        codeberg.requests.clear()
        val acting = with(codeberg) { api { json("""{"id":1}""") } }

        assertThat(acting.editsThreadComments).isTrue()
        assertThat(acting.deletesThreadComments).isFalse()
        acting.editThreadComment("tok", threaded, thread, comment, "Thanks again!")
        assertThat(acting.deleteThreadComment("tok", threaded, thread, comment)).isEqualTo(ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unsupported))

        assertThat(codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PATCH $base/issues/comments/${comment.id}")
        assertThat((codeberg.requests[0].body as TextContent).text).isEqualTo("""{"body":"Thanks again!"}""")
    }

    @Test
    fun a_remark_on_a_line_carries_its_reactions_and_takes_one_as_the_comment_it_is() = runTest {
        // Forgejo keeps a remark's reactions as an issue comment's: asked of each, none answering null.
        val reacted = with(codeberg) {
            api { path ->
                when {
                    path.endsWith("/reactions") ->
                        json("""[{"user":{"id":1,"login":"a"},"content":"+1"},{"user":{"id":2,"login":"b"},"content":"+1"},{"user":{"id":3,"login":"c"},"content":"heart"}]""")
                    path.endsWith("/reviews") -> json(fixture("threads/reviews.json"))
                    path.endsWith("/comments") -> json(fixture("threads/review_${path.split('/').dropLast(1).last()}.json"))
                    else -> json("{}")
                }
            }
        }
        val thread = reacted.threads("tok", threaded).value().first()
        assertThat(thread.comments.first().reactions).containsExactly(Reaction.THUMBS_UP, 2, Reaction.HEART, 1)
        assertThat(reacted.reactsToThreadComments).isTrue()
        codeberg.requests.clear()

        val counts = reacted.toggleThreadReaction("tok", "a", threaded, thread, thread.comments.first(), Reaction.THUMBS_UP).value()

        // The reader's own was there: it is taken back.
        assertThat(counts).containsExactly(Reaction.THUMBS_UP, 1, Reaction.HEART, 1)
        assertThat(codeberg.requests.last().method).isEqualTo(HttpMethod.Delete)
        assertThat(codeberg.requests.last().url.encodedPath).isEqualTo("$base/issues/comments/${thread.comments.first().id}/reactions")
    }

    @Test
    fun a_merge_says_how() = runTest {
        with(codeberg) { api { status(HttpStatusCode.OK) } }.merge("tok", pull, MergeMethod.REBASE)

        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("$base/pulls/14792/merge")
        assertThat((request.body as TextContent).text).isEqualTo("""{"Do":"rebase","delete_branch_after_merge":false}""")
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

    // forgejo/forgejo on its default branch, 2026-10-24: its root (cut to four entries), its .forgejo folder and the template there.
    private fun captured(name: String) = requireNotNull(javaClass.getResource("/codeberg/$name")).readText()

    @Test
    fun the_pull_request_template_is_found_in_the_folders_forgejo_looks_into() = runTest {
        val templates = with(codeberg) {
            api { path ->
                when {
                    path.endsWith("/git/trees/forgejo") -> json(captured("pr_template_root.json"))
                    path.endsWith("/git/trees/forgejo:.forgejo") -> json(captured("pr_template_forgejo_tree.json"))
                    path.endsWith("/raw/.forgejo/pull_request_template.md") -> text(captured("pull_request_template.md"))
                    else -> error(path)
                }
            }
        }.pullRequestTemplates("tok", repo, "forgejo").value()

        val template = templates.single()
        assertThat(template.key).isEqualTo(".forgejo/pull_request_template.md")
        assertThat(template.name).isEqualTo("Pull Request Template")
        assertThat(template.about).isEqualTo("Template for all Pull Requests")
        // The front matter isn't part of the description.
        assertThat(template.body).startsWith("## Checklist\n")
        assertThat(codeberg.requests.map { it.url.encodedPath }).containsExactly(
            "$base/git/trees/forgejo", "$base/git/trees/forgejo:.forgejo", "$base/raw/.forgejo/pull_request_template.md",
        ).inOrder()
        assertThat(codeberg.requests.last().url.parameters["ref"]).isEqualTo("forgejo")
    }

    @Test
    fun a_repository_without_those_folders_has_no_template_and_is_asked_nothing_more() = runTest {
        val templates = with(codeberg) {
            api { json("""{"tree":[{"path":"README.md","type":"blob","mode":"100644"},{"path":"src","type":"tree","mode":"040000"}],"total_count":2}""") }
        }.pullRequestTemplates("tok", repo, "main").value()

        assertThat(templates).isEmpty()
        assertThat(codeberg.requests).hasSize(1)
    }

    @Test
    fun a_template_at_the_root_comes_before_one_in_a_folder() = runTest {
        val templates = with(codeberg) {
            api { path ->
                if (path.endsWith("/raw/PULL_REQUEST_TEMPLATE.md")) {
                    text("## Why\n")
                } else {
                    json("""{"tree":[{"path":".github","type":"tree"},{"path":"PULL_REQUEST_TEMPLATE.md","type":"blob"}],"total_count":2}""")
                }
            }
        }.pullRequestTemplates("tok", repo, "main").value()

        assertThat(templates.single().key).isEqualTo("PULL_REQUEST_TEMPLATE.md")
        assertThat(templates.single().body).isEqualTo("## Why\n")
        assertThat(codeberg.requests).hasSize(2)
    }

    @Test
    fun a_template_without_front_matter_is_kept_whole() {
        val template = markdownTemplate(".gitea/pull_request_template.md", "---\nnot front matter, a rule\n")

        assertThat(template.body).isEqualTo("---\nnot front matter, a rule\n")
        assertThat(template.name).isEqualTo("pull_request_template.md")
    }

    private val suggestedOn = ReviewThread(
        id = "9", path = "docs/README.md", line = 2, onOldSide = false, hunk = "@@ -1,2 +1,2 @@\n # Title\n-teh text\n+the txet",
        comments = emptyList(), resolved = false, canResolve = false,
    )

    private fun fileJson(text: String) = """{"sha":"blob7","encoding":"base64","content":"${Base64.getEncoder().encodeToString(text.encodeToByteArray())}"}"""

    @Test
    fun a_suggestion_is_committed_to_the_pull_request_branch_by_the_contents_api() = runTest {
        val result = with(codeberg) {
            api { path ->
                when {
                    path == "$base/pulls/14792" -> json("""{"head":{"sha":"abc","ref":"typo","repo":{"full_name":"ana/forgejo"}},"base":{"sha":"def","ref":"forgejo"}}""")
                    requests.last().method == HttpMethod.Get -> json(fileJson("# Title\nthe txet\n"))
                    else -> json("""{"commit":{"sha":"new"}}""", HttpStatusCode.Created)
                }
            }
        }.applySuggestion("tok", pull, suggestedOn, listOf("the text"), "Apply suggestion from code review")

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        assertThat(codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly(
            "GET $base/pulls/14792", "GET /api/v1/repos/ana/forgejo/contents/docs/README.md", "PUT /api/v1/repos/ana/forgejo/contents/docs/README.md",
        ).inOrder()
        assertThat(codeberg.requests[1].url.parameters["ref"]).isEqualTo("typo")
        val sent = (codeberg.requests[2].body as TextContent).text
        assertThat(sent).contains(""""sha":"blob7"""")
        assertThat(sent).contains(""""branch":"typo"""")
        val content = Regex(""""content":"([^"]*)"""").find(sent)!!.groupValues[1]
        assertThat(Base64.getDecoder().decode(content).decodeToString()).isEqualTo("# Title\nthe text\n")
    }

    @Test
    fun a_suggestion_on_lines_someone_has_changed_since_commits_nothing() = runTest {
        val result = with(codeberg) {
            api { path ->
                if (path == "$base/pulls/14792") json("""{"head":{"sha":"abc","ref":"typo","repo":{"full_name":"ana/forgejo"}}}""") else json(fileJson("# Title\nthe text\n"))
            }
        }.applySuggestion("tok", pull, suggestedOn, listOf("the text"), "Apply")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Conflict))
        assertThat(codeberg.requests.map { it.method }).doesNotContain(HttpMethod.Put)
    }
}
