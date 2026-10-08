package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
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

class GitLabRepoApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabRepoApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun project_parses_details_and_handles_url_encoded_path() = runTest {
        val json = """
            {
                "id": 278964,
                "name": "gitlab",
                "path": "gitlab",
                "path_with_namespace": "gitlab-org/gitlab",
                "description": "GitLab CE and EE codebase",
                "default_branch": "master",
                "web_url": "https://gitlab.com/gitlab-org/gitlab",
                "star_count": 25000,
                "forks_count": 6000,
                "last_activity_at": "2026-10-02T12:00:00Z",
                "archived": false,
                "topics": ["devops", "git"],
                "tag_list": [],
                "jobs_enabled": true,
                "issues_enabled": true,
                "merge_requests_enabled": true
            }
        """.trimIndent()

        val details = api { json(json) }.repo(null, repo).value()
        assertThat(details.id).isEqualTo(repo)
        assertThat(details.description).isEqualTo("GitLab CE and EE codebase")
        assertThat(details.defaultBranch).isEqualTo("master")
        assertThat(details.stars).isEqualTo(25000)
        assertThat(details.forks).isEqualTo(6000)
        assertThat(details.topics).containsExactly("devops", "git")
        assertThat(requests.single().url.toString()).contains("gitlab-org%2Fgitlab")
    }

    @Test
    fun branches_and_tags_are_fetched_and_sorted() = runTest {
        val api = api { req ->
            if (req.url.encodedPath.endsWith("/branches")) {
                json("""[{"name": "main", "default": true}, {"name": "feature", "default": false}]""")
            } else {
                json("""[{"name": "v1.0.0"}, {"name": "v2.0.0"}]""")
            }
        }

        val refs = api.refs(null, repo).value()
        assertThat(refs.branches).containsExactly("feature", "main").inOrder()
        assertThat(refs.tags).containsExactly("v2.0.0", "v1.0.0").inOrder()
    }

    @Test
    fun directory_tree_is_listed() = runTest {
        val json = """
            [
                {"id": "1", "name": "src", "type": "tree", "path": "src"},
                {"id": "2", "name": "README.md", "type": "blob", "path": "README.md"}
            ]
        """.trimIndent()

        val files = api { json(json) }.contents(null, repo, "", "main").value()
        assertThat(files).hasSize(2)
        assertThat(files[0].name).isEqualTo("src")
        assertThat(files[0].type).isEqualTo(RepoFileType.DIR)
        assertThat(files[1].name).isEqualTo("README.md")
        assertThat(files[1].type).isEqualTo(RepoFileType.FILE)
    }

    @Test
    fun raw_file_content_is_returned() = runTest {
        val content = api { respond("hello world", HttpStatusCode.OK) }.fileText(null, repo, "README.md", "main").value()
        assertThat(content).isEqualTo("hello world")
    }

    @Test
    fun issues_are_mapped_to_summary() = runTest {
        val json = """
            [
                {
                    "id": 101,
                    "iid": 1,
                    "project_id": 278964,
                    "title": "Bug in pipeline",
                    "state": "opened",
                    "created_at": "2026-10-01T10:00:00Z",
                    "user_notes_count": 3,
                    "labels": ["bug", "ci"]
                }
            ]
        """.trimIndent()

        val issues = api { json(json) }.issues(null, repo, IssueQuery(open = true)).value()
        assertThat(issues).hasSize(1)
        assertThat(issues[0].number).isEqualTo(1)
        assertThat(issues[0].title).isEqualTo("Bug in pipeline")
        assertThat(issues[0].isPullRequest).isFalse()
        assertThat(issues[0].labels.map { it.name }).containsExactly("bug", "ci")
    }

    @Test
    fun merge_requests_are_mapped_to_summary() = runTest {
        val json = """
            [
                {
                    "id": 201,
                    "iid": 42,
                    "project_id": 278964,
                    "title": "Draft: Add GitLab support",
                    "state": "opened",
                    "created_at": "2026-10-02T10:00:00Z",
                    "user_notes_count": 5,
                    "draft": true,
                    "labels": ["feature"]
                }
            ]
        """.trimIndent()

        val prs = api { json(json) }.pullRequests(null, repo, IssueQuery(open = true)).value()
        assertThat(prs).hasSize(1)
        assertThat(prs[0].number).isEqualTo(42)
        assertThat(prs[0].title).isEqualTo("Draft: Add GitLab support")
        assertThat(prs[0].isPullRequest).isTrue()
        assertThat(prs[0].isDraft).isTrue()
    }

    @Test
    fun a_further_page_of_issues_and_of_merge_requests_is_asked_for_by_its_number() = runTest {
        // Regression: the lists stopped at their first page, with no way to the rest.
        api { json("[]") }.issues(null, repo, IssueQuery(page = 3))
        assertThat(requests.last().url.parameters["page"]).isEqualTo("3")
        assertThat(requests.last().url.parameters["per_page"]).isEqualTo("30")

        requests.clear()
        api { json("[]") }.pullRequests(null, repo, IssueQuery(open = false, page = 2))
        // The merged and the closed are asked apart, each at the same page.
        assertThat(requests.map { it.url.parameters["page"] }).containsExactly("2", "2")
    }

    @Test
    fun a_project_says_whether_its_wiki_is_switched_on() = runTest {
        fun project(wiki: String) = """{"id":1,"name":"gitlab","path":"gitlab","path_with_namespace":"gitlab-org/gitlab","web_url":"https://gitlab.com/gitlab-org/gitlab"$wiki}"""

        assertThat(api { json(project(""","wiki_enabled":true""")) }.repo(null, repo).value().hasWiki).isTrue()
        assertThat(api { json(project(""","wiki_enabled":false""")) }.repo(null, repo).value().hasWiki).isFalse()
        // Not said: not offered.
        assertThat(api { json(project("")) }.repo(null, repo).value().hasWiki).isFalse()
    }
}
