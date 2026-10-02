package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Fixtures are real api.github.com responses for paperclipai/paperclip captured on 2026-09-26. */
class GitHubRepoApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val paperclip = RepoId("paperclipai", "paperclip")

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/repo/$name")) { name }.readText()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubRepoApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun a_repository_with_its_issues_switched_off_says_so() = runTest {
        val repo = api { json(fixture("repo.json").replace("\"has_issues\":true", "\"has_issues\":false")) }.repo(null, paperclip).value()

        assertThat(repo.hasIssues).isFalse()
    }

    @Test
    fun parses_repository_details() = runTest {
        val repo = api { json(fixture("repo.json")) }.repo(null, paperclip).value()

        assertThat(repo.id).isEqualTo(paperclip)
        assertThat(repo.defaultBranch).isEqualTo("master")
        assertThat(repo.license).isEqualTo("MIT")
        assertThat(repo.language).isEqualTo("TypeScript")
        assertThat(repo.stars).isGreaterThan(80_000)
        assertThat(repo.ownerAvatarUrl).startsWith("https://avatars.githubusercontent.com/")
        assertThat(repo.isArchived).isFalse()
        assertThat(repo.hasIssues).isTrue()
        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip")
    }

    @Test
    fun a_renamed_repo_reports_its_canonical_name() = runTest {
        // GitHub redirects old names to the moved repo; its body carries the new owner/name,
        // which callers must use afterwards (search rejects old names with 422).
        val moved = fixture("repo.json")
            .replace("\"full_name\":\"paperclipai/paperclip\"", "\"full_name\":\"newowner/paperclip\"")
            .replaceFirst("\"login\":\"paperclipai\"", "\"login\":\"newowner\"")

        val repo = api { json(moved) }.repo(null, paperclip).value()

        assertThat(repo.id).isEqualTo(RepoId("newowner", "paperclip"))
    }

    @Test
    fun anonymous_calls_send_no_credentials_and_signed_in_calls_do() = runTest {
        val api = api { json(fixture("repo.json")) }

        api.repo(null, paperclip)
        api.repo("ghp_token", paperclip)

        assertThat(requests[0].headers[HttpHeaders.Authorization]).isNull()
        assertThat(requests[1].headers[HttpHeaders.Authorization]).isEqualTo("Bearer ghp_token")
        assertThat(requests.all { it.headers["X-GitHub-Api-Version"] == "2022-11-28" }).isTrue()
    }

    @Test
    fun a_missing_repository_is_an_http_404() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.repo(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
    }

    @Test
    fun decodes_the_readme_and_keeps_its_path() = runTest {
        val readme = api { json(fixture("readme.json")) }.readme(null, paperclip).value()!!

        assertThat(readme.path).isEqualTo("README.md")
        assertThat(readme.markdown).startsWith("<p align=\"center\">")
        assertThat(readme.markdown).contains("# Paperclip is the app people use to manage AI agents for work.")
    }

    @Test
    fun a_readme_can_be_read_at_another_ref() = runTest {
        api { json(fixture("readme.json")) }.readme(null, paperclip, ref = "v2026.916.1")

        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/readme?ref=v2026.916.1")
    }

    @Test
    fun lists_every_branch_and_tag_in_one_request_each() = runTest {
        // Fixtures are trimmed from real matching-refs answers (977 branches, 1664 tags on 2026-09-29).
        val refs = api {
            json(fixture(if (it.url.encodedPath.endsWith("/heads")) "matching_refs_heads.json" else "matching_refs_tags.json"))
        }.refs(null, paperclip).value()

        assertThat(refs.branches).containsExactly(
            "LOOA-700-recovery-tightloop",
            "LOOA-956-paperclip-feature-skill-usage-analytics-in-the-skill-browser-and-studio",
            "master",
            "PAP-10015-per-use-join-leave-projects-and-agents",
            "PAP-10026-team-s-based-paperclip-not-always-a-fixed-ceo",
        ).inOrder()
        // Newest versions first, comparing numbers as numbers.
        assertThat(refs.tags).containsExactly(
            "v2026.916.1", "v2026.916.0", "v2026.831.1",
            "@paperclipai/adapter-claude-local@0.2.4", "@paperclipai/adapter-claude-local@0.2.3", "@paperclipai/adapter-claude-local@0.2.2",
        ).inOrder()
        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/repos/paperclipai/paperclip/git/matching-refs/heads",
            "/repos/paperclipai/paperclip/git/matching-refs/tags",
        )
    }

    @Test
    fun tags_compare_their_numbers_as_numbers() = runTest {
        val tags = """[{"ref":"refs/tags/v1.9.0"},{"ref":"refs/tags/v1.10.0"},{"ref":"refs/tags/v1.2.0"}]"""

        val refs = api { json(if (it.url.encodedPath.endsWith("/heads")) "[]" else tags) }.refs(null, paperclip).value()

        assertThat(refs.tags).containsExactly("v1.10.0", "v1.9.0", "v1.2.0").inOrder()
    }

    @Test
    fun an_empty_repository_has_no_refs() = runTest {
        // GitHub answers 409 "Git Repository is empty" for the git database endpoints.
        val result = api { json("""{"message":"Git Repository is empty."}""", HttpStatusCode.Conflict) }.refs(null, paperclip)

        assertThat(result.value().branches).isEmpty()
        assertThat(result.value().tags).isEmpty()
    }

    @Test
    fun a_repo_without_readme_has_none() = runTest {
        val result = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.readme(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Success(null))
    }

    @Test
    fun lists_a_directory_with_folders_first() = runTest {
        val entries = api { json(fixture("contents_root.json")) }.contents(null, paperclip, "", "master").value()

        assertThat(entries).hasSize(47)
        val firstFile = entries.indexOfFirst { it.type == RepoFileType.FILE }
        assertThat(entries.take(firstFile).all { it.type == RepoFileType.DIR }).isTrue()
        assertThat(entries.drop(firstFile).none { it.type == RepoFileType.DIR }).isTrue()
        assertThat(entries.first().name).isEqualTo(".agents")
        assertThat(requests.single().url.toString()).isEqualTo("https://api.github.com/repos/paperclipai/paperclip/contents?ref=master")
    }

    @Test
    fun paths_are_encoded_segment_by_segment() = runTest {
        api { json("[]") }.contents(null, paperclip, "docs/my guide", "feature/x")

        assertThat(requests.single().url.toString())
            .isEqualTo("https://api.github.com/repos/paperclipai/paperclip/contents/docs/my%20guide?ref=feature%2Fx")
    }

    @Test
    fun reads_a_text_file() = runTest {
        val text = api { json(fixture("file_package_json.json")) }.fileText(null, paperclip, "package.json", "master").value()

        assertThat(text).startsWith("{")
        assertThat(text).contains("\"name\"")
    }

    @Test
    fun files_too_large_for_the_contents_api_are_reported() = runTest {
        val tooLarge = """{"type":"file","encoding":"none","content":"","size":5000000,"path":"big.bin","name":"big.bin"}"""

        val result = api { json(tooLarge) }.fileText(null, paperclip, "big.bin", "master")

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(413, "File too large to preview")))
    }

    @Test
    fun open_issues_come_from_search_so_pull_requests_never_crowd_them_out() = runTest {
        val issues = api { json(fixture("search_issues.json")) }.openIssues(null, paperclip).value()

        assertThat(issues).hasSize(5)
        assertThat(issues.first().number).isEqualTo(14127)
        assertThat(issues.all { !it.isPullRequest && it.state == IssueState.OPEN }).isTrue()
        assertThat(issues.first().comments).isNotNull()
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/search/issues")
        assertThat(url.parameters["q"]).isEqualTo("repo:paperclipai/paperclip is:issue is:open")
        assertThat(url.parameters["sort"]).isEqualTo("created")
    }

    @Test
    fun lists_open_pull_requests() = runTest {
        val pulls = api { json(fixture("pulls.json")) }.openPullRequests(null, paperclip).value()

        assertThat(pulls.map { it.number }).containsExactly(14129, 14128, 14126, 14125).inOrder()
        assertThat(pulls.all { it.isPullRequest }).isTrue()
        assertThat(pulls.first().author?.login).isEqualTo("basil-k-aji-dev")
        assertThat(pulls.first().comments).isNull()
    }

    @Test
    fun lists_releases_newest_first() = runTest {
        val releases = api { json(fixture("releases.json")) }.releases(null, paperclip).value()

        assertThat(releases.map { it.tag }).containsExactly("v2026.916.1", "v2026.916.0", "v2026.831.1").inOrder()
        assertThat(releases.first().publishedAt).isEqualTo(Instant.parse("2026-09-21T21:22:44Z"))
        assertThat(releases.first().body).isNotEmpty()
    }

    @Test
    fun lists_workflow_runs_with_status_and_conclusion() = runTest {
        val runs = api { json(fixture("runs.json")) }.workflowRuns(null, paperclip).value()

        assertThat(runs.map { it.status to it.conclusion }).containsExactly(
            RunStatus.QUEUED to null,
            RunStatus.COMPLETED to RunConclusion.SKIPPED,
            RunStatus.COMPLETED to RunConclusion.FAILURE,
            RunStatus.COMPLETED to RunConclusion.SKIPPED,
        ).inOrder()
        assertThat(runs.first().event).isEqualTo("pull_request")
        assertThat(runs.first().runNumber).isEqualTo(39953)
    }

    @Test
    fun readme_urls_point_at_raw_files_and_blob_pages() {
        val api = api { error("no request") }

        assertThat(api.rawBaseUrl(paperclip, "master")).isEqualTo("https://raw.githubusercontent.com/paperclipai/paperclip/master/")
        assertThat(api.blobBaseUrl(paperclip, "master")).isEqualTo("https://github.com/paperclipai/paperclip/blob/master/")
    }

    // Regression: only network errors were caught, so an answer the app couldn't read crashed it.

    @Test
    fun an_answer_missing_what_the_api_promises_is_a_failure_not_a_crash() = runTest {
        val result = api { json("""{"name":"paperclip"}""") }.repo(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_web_page_where_data_was_expected_is_a_failure_not_a_crash() = runTest {
        val result = api { respond("<html>Maintenance</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) }.repo(null, paperclip)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun a_date_that_is_not_one_is_a_failure_not_a_crash() = runTest {
        val body = """{"name":"paperclip","owner":{"login":"paperclipai"},"default_branch":"master","pushed_at":"yesterday"}"""

        assertThat(api { json(body) }.repo(null, paperclip)).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }
}
