package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class ForgejoRepoApiTest {
    private val codeberg = Codeberg()
    private val forgejo = RepoId("forgejo", "forgejo", ForgeInstance.Codeberg)

    private fun api(route: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(String) -> io.ktor.client.request.HttpResponseData) =
        ForgejoRepoApi(codeberg.client { route(it.url.encodedPath) }, ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun a_repository_with_its_issues_switched_off_says_so() = runTest {
        val repo = with(codeberg) { api { json(fixture("repo.json").replace("\"has_issues\":true", "\"has_issues\":false")) } }.repo(null, forgejo).value()

        assertThat(repo.hasIssues).isFalse()
    }

    @Test
    fun reads_a_repository_on_its_forge() = runTest {
        val repo = with(codeberg) { api { json(fixture("repo.json")) } }.repo(null, forgejo).value()

        assertThat(repo.id).isEqualTo(forgejo)
        assertThat(repo.description).isEqualTo("Beyond coding. We forge.")
        assertThat(repo.homepage).isEqualTo("https://forgejo.org")
        assertThat(repo.topics).containsExactly("forge", "forgejo", "git", "self-hosted")
        assertThat(repo.defaultBranch).isEqualTo("forgejo")
        assertThat(repo.language).isEqualTo("Go")
        assertThat(repo.license).isNull()
        assertThat(repo.pushedAt).isEqualTo(Instant.parse("2026-09-29T14:34:10Z"))
        assertThat(repo.hasActions).isTrue()
        assertThat(repo.hasIssues).isTrue()
        assertThat(codeberg.requests.single().url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo")
    }

    @Test
    fun the_readme_is_found_among_the_root_files_and_read_raw() = runTest {
        val api = with(codeberg) {
            api { path -> if (path.endsWith("/contents")) json(fixture("contents_root.json")) else text(fixture("readme.md")) }
        }

        val readme = api.readme(null, forgejo, "v16.0/forgejo").value()!!

        assertThat(readme.path).isEqualTo("README.md")
        assertThat(readme.markdown).startsWith("<div align=\"center\">")
        assertThat(codeberg.requests.map { it.url.toString() }).containsExactly(
            "https://codeberg.org/api/v1/repos/forgejo/forgejo/contents?ref=v16.0%2Fforgejo",
            "https://codeberg.org/api/v1/repos/forgejo/forgejo/raw/README.md?ref=v16.0%2Fforgejo",
        ).inOrder()
    }

    @Test
    fun a_repository_without_readme_has_none() = runTest {
        val readme = with(codeberg) { api { json("""[{"name":"main.go","path":"main.go","type":"file","size":10}]""") } }.readme(null, forgejo).value()

        assertThat(readme).isNull()
        assertThat(codeberg.requests).hasSize(1)
    }

    @Test
    fun lists_a_folder_with_folders_first() = runTest {
        val entries = with(codeberg) { api { json(fixture("contents_root.json")) } }.contents(null, forgejo, "", "forgejo").value()

        assertThat(entries).hasSize(68)
        val firstFile = entries.indexOfFirst { it.type == RepoFileType.FILE }
        assertThat(entries.take(firstFile).all { it.type == RepoFileType.DIR }).isTrue()
        assertThat(entries.first().name).isEqualTo(".devcontainer")
    }

    @Test
    fun lists_every_branch_and_tag_newest_tags_first() = runTest {
        val refs = with(codeberg) { api { path -> json(fixture(if (path.endsWith("/heads")) "refs_heads.json" else "refs_tags.json")) } }
            .refs(null, forgejo).value()

        assertThat(refs.branches).contains("v9.0/forgejo")
        assertThat(refs.tags.first()).isEqualTo("v9.0.3")
        assertThat(refs.tags.last()).isEqualTo("v0.9.99")
        assertThat(codeberg.requests.map { it.url.encodedPath }).containsExactly(
            "/api/v1/repos/forgejo/forgejo/git/refs/heads",
            "/api/v1/repos/forgejo/forgejo/git/refs/tags",
        )
    }

    @Test
    fun open_issues_and_pull_requests_are_listed_apart() = runTest {
        val api = with(codeberg) { api { path -> json(fixture(if (path.endsWith("/pulls")) "pulls.json" else "issues.json")) } }

        val issues = api.issues(null, forgejo).value()
        val pulls = api.pullRequests(null, forgejo).value()

        assertThat(issues.map { it.number }).containsExactly(14601, 14600, 14595).inOrder()
        assertThat(issues.none { it.isPullRequest }).isTrue()
        assertThat(issues.first().labels.first().name).isEqualTo("bug/new-report")
        assertThat(pulls.map { it.number }).containsExactly(14602, 14599, 14598).inOrder()
        assertThat(pulls.all { it.isPullRequest && it.state == IssueState.OPEN }).isTrue()
    }

    @Test
    fun releases_leave_drafts_out() = runTest {
        val releases = with(codeberg) { api { json(fixture("releases.json")) } }.releases(null, forgejo).value()

        assertThat(releases.map { it.tag }).containsExactly("v16.0.5", "v15.0.9", "v16.0.4").inOrder()
    }

    @Test
    fun a_file_is_read_raw_at_its_ref() = runTest {
        val text = with(codeberg) { api { text("package main\n") } }.fileText(null, forgejo, "cmd/main.go", "forgejo").value()

        assertThat(text).isEqualTo("package main\n")
        assertThat(codeberg.requests.single().url.toString()).isEqualTo("https://codeberg.org/api/v1/repos/forgejo/forgejo/raw/cmd/main.go?ref=forgejo")
    }

    @Test
    fun a_token_is_sent_the_forgejo_way() = runTest {
        with(codeberg) { api { json(fixture("repo.json")) } }.repo("cb_token", forgejo)

        assertThat(codeberg.requests.single().headers[HttpHeaders.Authorization]).isEqualTo("token cb_token")
    }

    @Test
    fun a_missing_repository_is_a_404() = runTest {
        val result = with(codeberg) { api { json("""{"message":"The target couldn't be found."}""", HttpStatusCode.NotFound) } }.repo(null, forgejo)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "The target couldn't be found.")))
    }

    @Test
    fun an_exhausted_rate_limit_is_reported_as_such() = runTest {
        val result = with(codeberg) { api { json("{}", HttpStatusCode.TooManyRequests, mapOf("RateLimit-Remaining" to "0")) } }.repo(null, forgejo)

        assertThat((result as ForgeResult.Failure).error).isInstanceOf(ForgeError.RateLimited::class.java)
    }

    // Regression: only network errors were caught, so an answer the app couldn't read crashed it. A self-hosted
    // server behind a proxy can answer its API paths with a web page.

    @Test
    fun a_web_page_where_data_was_expected_is_a_failure_not_a_crash() = runTest {
        val result = with(codeberg) { api { text("<html>Maintenance</html>") } }.repo(null, forgejo)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    @Test
    fun an_answer_missing_what_the_api_promises_is_a_failure_not_a_crash() = runTest {
        val result = with(codeberg) { api { json("""{"description":"no name, no owner"}""") } }.repo(null, forgejo)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unreadable))
    }

    private val asked get() = codeberg.requests.last().url

    @Test
    fun closed_issues_and_words_are_asked_of_the_issue_list() = runTest {
        val api = with(codeberg) { api { json(fixture("issues.json")) } }

        api.issues(null, forgejo, IssueQuery(open = false))
        assertThat(asked.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/issues")
        assertThat(asked.parameters["state"]).isEqualTo("closed")
        assertThat(asked.parameters["type"]).isEqualTo("issues")
        assertThat(asked.parameters["q"]).isNull()

        api.issues(null, forgejo, IssueQuery(text = " webhook "))
        assertThat(asked.parameters["state"]).isEqualTo("open")
        assertThat(asked.parameters["q"]).isEqualTo("webhook")
    }

    @Test
    fun closed_pull_requests_come_from_the_pulls_list() = runTest {
        with(codeberg) { api { json(fixture("pulls.json")) } }.pullRequests(null, forgejo, IssueQuery(open = false))

        assertThat(asked.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/pulls")
        assertThat(asked.parameters["state"]).isEqualTo("closed")
    }

    @Test
    fun words_among_pull_requests_go_through_the_issue_list_which_says_merged() = runTest {
        // Forgejo's shape for a pull request in the issue list (checked on Codeberg, 2026-10-02).
        val found = """[{"number":14609,"title":"fix: Slack PR notifications","state":"closed","created_at":"2026-09-30T05:00:00+02:00",
            "pull_request":{"merged":true,"draft":false}},
            {"number":14599,"title":"fix: Slack again","state":"closed","created_at":"2026-09-30T03:00:00+02:00","pull_request":{"merged":false,"draft":false}}]"""

        val pulls = with(codeberg) { api { json(found) } }.pullRequests(null, forgejo, IssueQuery(open = false, text = "slack")).value()

        assertThat(pulls.map { it.state }).containsExactly(IssueState.MERGED, IssueState.CLOSED).inOrder()
        assertThat(pulls.all { it.isPullRequest }).isTrue()
        assertThat(asked.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/issues")
        assertThat(asked.parameters["type"]).isEqualTo("pulls")
        assertThat(asked.parameters["q"]).isEqualTo("slack")
        assertThat(asked.parameters["state"]).isEqualTo("closed")
    }

    // Fixture: forgejo/forgejo's pinned issue, captured 2026-10-02 with its long description emptied.
    @Test
    fun pinned_issues_are_listed() = runTest {
        val pinned = with(codeberg) { api { json(fixture("pinned.json")) } }.pinnedIssues(null, forgejo).value()

        assertThat(pinned.map { it.number }).containsExactly(2779)
        assertThat(pinned.single().title).isEqualTo("Dependency Dashboard")
        assertThat(pinned.single().author?.login).isEqualTo("forgejo-renovate-action")
        assertThat(asked.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/issues/pinned")
    }

    @Test
    fun a_pinned_pull_request_is_not_an_issue() = runTest {
        val mixed = """[{"number":1,"title":"Pinned issue","state":"open","created_at":"2026-09-30T05:00:00+02:00"},
            {"number":2,"title":"Pinned pull","state":"open","created_at":"2026-09-30T05:00:00+02:00","pull_request":{"merged":false,"draft":false}}]"""

        assertThat(with(codeberg) { api { json(mixed) } }.pinnedIssues(null, forgejo).value().map { it.number }).containsExactly(1)
    }

    @Test
    fun a_release_lists_its_files_its_source_and_its_page() = runTest {
        val releases = with(codeberg) { api { json(fixture("releases.json")) } }.releases(null, forgejo).value()
        val release = releases.first()

        val asset = release.assets.first()
        assertThat(asset.name).isEqualTo("forgejo-16.0.5-linux-amd64")
        assertThat(asset.sizeBytes).isEqualTo(122_204_040)
        assertThat(asset.downloads).isEqualTo(4321)
        assertThat(asset.url).isEqualTo("https://codeberg.org/forgejo/forgejo/releases/download/v16.0.5/forgejo-16.0.5-linux-amd64")
        assertThat(release.zipUrl).isEqualTo("https://codeberg.org/forgejo/forgejo/archive/v16.0.5.zip")
        assertThat(release.tarUrl).isEqualTo("https://codeberg.org/forgejo/forgejo/archive/v16.0.5.tar.gz")
        assertThat(release.webUrl).isEqualTo("https://codeberg.org/forgejo/forgejo/releases/tag/v16.0.5")
        assertThat(releases.map { it.isLatest }.first()).isTrue()
        assertThat(releases.count { it.isLatest }).isEqualTo(1)
    }

    @Test
    fun a_release_that_hides_its_archives_offers_no_source() = runTest {
        val hidden = codeberg.fixture("releases.json").replace("\"hide_archive_links\":false", "\"hide_archive_links\":true")

        val release = with(codeberg) { api { json(hidden) } }.releases(null, forgejo).value().first()

        assertThat(release.zipUrl).isNull()
        assertThat(release.tarUrl).isNull()
    }

    @Test
    fun one_release_is_asked_for_by_its_tag_even_one_with_slashes() = runTest {
        val one = kotlinx.serialization.json.Json.parseToJsonElement(codeberg.fixture("releases.json")).let { (it as kotlinx.serialization.json.JsonArray).first().toString() }
        val api = with(codeberg) { api { json(one) } }

        val release = api.release(null, forgejo, "v16.0.5").value()
        api.release(null, forgejo, "v16.0/forgejo")

        assertThat(release.tag).isEqualTo("v16.0.5")
        assertThat(release.assets).isNotEmpty()
        assertThat(codeberg.requests.map { it.url.encodedPath }).containsExactly(
            "/api/v1/repos/forgejo/forgejo/releases/tags/v16.0.5", "/api/v1/repos/forgejo/forgejo/releases/tags/v16.0/forgejo",
        ).inOrder()
    }

    @Test
    fun a_further_page_of_issues_and_of_pull_requests_is_asked_for_by_its_number() = runTest {
        // Regression: the lists stopped at their first 30, with no way to the rest.
        with(codeberg) { api { json(fixture("issues.json")) } }.issues(null, forgejo, IssueQuery(page = 3))
        assertThat(asked.parameters["page"]).isEqualTo("3")
        assertThat(asked.parameters["limit"]).isEqualTo("30")

        with(codeberg) { api { json(fixture("pulls.json")) } }.pullRequests(null, forgejo, IssueQuery(open = false, page = 2))
        assertThat(asked.encodedPath).isEqualTo("/api/v1/repos/forgejo/forgejo/pulls")
        assertThat(asked.parameters["page"]).isEqualTo("2")
    }
}
