package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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

        val issues = api.openIssues(null, forgejo).value()
        val pulls = api.openPullRequests(null, forgejo).value()

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
}
