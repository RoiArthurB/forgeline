package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkKind
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

class GitLabSearchApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabSearchApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun searches_repositories() = runTest {
        val json = """
            [
                {
                    "id": 1,
                    "name": "project-a",
                    "path": "project-a",
                    "path_with_namespace": "group/project-a",
                    "web_url": "https://gitlab.com/group/project-a",
                    "star_count": 10
                }
            ]
        """.trimIndent()

        val results = api { json(json) }.repositories(null, "project-a").value()
        assertThat(results.items).hasSize(1)
        assertThat(results.items[0].id.fullName).isEqualTo("group/project-a")
        assertThat(results.items[0].stars).isEqualTo(10)
    }

    @Test
    fun first_issues_are_asked_under_the_labels_each_project_gives_them() = runTest {
        val repo = RepoId("inkscape", "inkscape", fr.arthurbrugiere.forgeline.core.model.ForgeInstance.GitLab)
        val labels = """[{"name": "bug"}, {"name": "easy-fix"}, {"name": "help wanted"}]"""
        val issues = """[{"id": 9, "iid": 42, "project_id": 3, "title": "Fix a typo", "state": "opened", "created_at": "2026-10-20T10:00:00Z", "web_url": "https://gitlab.com/inkscape/inkscape/-/issues/42", "labels": ["easy-fix"]}]"""

        val found = api { if (it.url.encodedPath.endsWith("/labels")) json(labels) else json(issues) }.firstIssues(null, listOf(repo)).value()

        assertThat(found.single().issue.number).isEqualTo(42)
        assertThat(found.single().repo).isEqualTo(repo)
        val asked = requests.single { it.url.encodedPath.endsWith("/issues") }.url
        assertThat(asked.parameters["labels"]).isEqualTo("easy-fix")
        assertThat(asked.parameters["state"]).isEqualTo("opened")
    }

    @Test
    fun a_project_s_labels_are_read_page_after_page() = runTest {
        val repo = RepoId("inkscape", "inkscape", fr.arthurbrugiere.forgeline.core.model.ForgeInstance.GitLab)
        val engine = MockEngine { request ->
            requests += request
            val page = request.url.parameters["page"]
            when {
                request.url.encodedPath.endsWith("/issues") -> respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                page == "1" -> respond("""[{"name": "bug"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("2")))
                else -> respond("""[{"name": "easy-fix"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        GitLabSearchApi(gitlabHttpClient(engine)).firstIssues(null, listOf(repo)).value()

        assertThat(requests.single { it.url.encodedPath.endsWith("/issues") }.url.parameters["labels"]).isEqualTo("easy-fix")
    }

    @Test
    fun a_project_without_a_first_issue_label_is_asked_nothing_more() = runTest {
        val repo = RepoId("group", "project", fr.arthurbrugiere.forgeline.core.model.ForgeInstance.GitLab)

        val found = api { json("""[{"name": "bug"}]""") }.firstIssues(null, listOf(repo)).value()

        assertThat(found).isEmpty()
        assertThat(requests).hasSize(1)
    }

    @Test
    fun a_topic_lists_the_projects_tagged_with_it_the_most_starred_first() = runTest {
        val json = """[{"id": 1, "name": "HomelabOS", "path": "HomelabOS", "path_with_namespace": "NickBusey/HomelabOS", "web_url": "https://gitlab.com/NickBusey/HomelabOS", "star_count": 1290}]"""

        val results = api { json(json) }.topic(null, "selfhosted").value()

        assertThat(results.items.single().id.fullName).isEqualTo("NickBusey/HomelabOS")
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/api/v4/projects")
        assertThat(url.parameters["topic"]).isEqualTo("selfhosted")
        assertThat(url.parameters["order_by"]).isEqualTo("star_count")
        assertThat(url.parameters["sort"]).isEqualTo("desc")
    }

    @Test
    fun searches_issues_and_merge_requests() = runTest {
        val api = api { req ->
            if (req.url.parameters["scope"] == "merge_requests") {
                json(
                    """
                    [
                        {
                            "id": 20,
                            "iid": 2,
                            "project_id": 1,
                            "title": "MR fix",
                            "state": "opened",
                            "created_at": "2026-10-01T12:00:00Z",
                            "web_url": "https://gitlab.com/group/project-a/-/merge_requests/2"
                        }
                    ]
                    """.trimIndent()
                )
            } else {
                json(
                    """
                    [
                        {
                            "id": 10,
                            "iid": 1,
                            "project_id": 1,
                            "title": "Issue bug",
                            "state": "opened",
                            "created_at": "2026-10-01T10:00:00Z",
                            "web_url": "https://gitlab.com/group/project-a/-/issues/1"
                        }
                    ]
                    """.trimIndent()
                )
            }
        }

        val results = api.issues(null, "bug").value()
        assertThat(results.items).hasSize(2)
        // Ordered by createdAt descending: MR (12:00) then Issue (10:00)
        assertThat(results.items[0].issue.title).isEqualTo("MR fix")
        assertThat(results.items[0].issue.isPullRequest).isTrue()
        assertThat(results.items[0].repo.fullName).isEqualTo("group/project-a")

        assertThat(results.items[1].issue.title).isEqualTo("Issue bug")
        assertThat(results.items[1].issue.isPullRequest).isFalse()
        assertThat(results.items[1].repo.fullName).isEqualTo("group/project-a")
    }

    @Test
    fun searches_users() = runTest {
        val json = """
            [
                {
                    "id": 123,
                    "username": "tanuki",
                    "avatar_url": "https://gitlab.com/avatar.png"
                }
            ]
        """.trimIndent()

        val results = api { json(json) }.users(null, "tanuki").value()
        assertThat(results.items).hasSize(1)
        assertThat(results.items[0].login).isEqualTo("tanuki")
        assertThat(results.items[0].avatarUrl).isEqualTo("https://gitlab.com/avatar.png")
    }

    private val mergeRequest = """[{"id":20,"iid":2,"project_id":1,"title":"MR fix","state":"opened","created_at":"2026-10-01T12:00:00Z","web_url":"https://gitlab.com/group/sub/project-a/-/merge_requests/2"}]"""
    private val assignedIssue = """[{"id":10,"iid":1,"project_id":1,"title":"Issue bug","state":"opened","created_at":"2026-10-02T10:00:00Z","web_url":"https://gitlab.com/group/project-a/-/issues/1"}]"""

    @Test
    fun reviews_asked_of_someone_are_the_open_merge_requests_naming_them_reviewer() = runTest {
        val found = api { json(mergeRequest) }.work("tok", "tanuki", WorkKind.REVIEW_REQUESTED).value()

        assertThat(found.single().repo.fullName).isEqualTo("group/sub/project-a")
        assertThat(found.single().issue.isPullRequest).isTrue()
        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/api/v4/merge_requests")
        assertThat(url.parameters["reviewer_username"]).isEqualTo("tanuki")
        assertThat(url.parameters["scope"]).isEqualTo("all")
        assertThat(url.parameters["state"]).isEqualTo("opened")
        assertThat(url.parameters["order_by"]).isEqualTo("updated_at")
    }

    @Test
    fun one_s_own_merge_requests_are_those_one_opened() = runTest {
        api { json(mergeRequest) }.work("tok", "tanuki", WorkKind.OWN_PULL_REQUESTS).value()

        val url = requests.single().url
        assertThat(url.encodedPath).isEqualTo("/api/v4/merge_requests")
        assertThat(url.parameters["scope"]).isEqualTo("created_by_me")
        assertThat(url.parameters["state"]).isEqualTo("opened")
    }

    @Test
    fun what_is_assigned_is_both_issues_and_merge_requests_newest_first() = runTest {
        val found = api { json(if (it.url.encodedPath.endsWith("/issues")) assignedIssue else mergeRequest) }.work("tok", "tanuki", WorkKind.ASSIGNED).value()

        assertThat(found.map { it.issue.title }).containsExactly("Issue bug", "MR fix").inOrder()
        assertThat(requests.map { it.url.encodedPath }).containsExactly("/api/v4/issues", "/api/v4/merge_requests")
        assertThat(requests.map { it.url.parameters["scope"] }.toSet()).containsExactly("assigned_to_me")
    }

    @Test
    fun one_of_the_two_assigned_lists_failing_leaves_the_other() = runTest {
        val api = api { if (it.url.encodedPath.endsWith("/issues")) json("{}", HttpStatusCode.InternalServerError) else json(mergeRequest) }

        assertThat(api.work("tok", "tanuki", WorkKind.ASSIGNED).value().map { it.issue.title }).containsExactly("MR fix")
    }

    @Test
    fun both_assigned_lists_failing_is_a_failure() = runTest {
        val result = api { json("{}", HttpStatusCode.InternalServerError) }.work("tok", "tanuki", WorkKind.ASSIGNED)

        assertThat(result).isInstanceOf(ForgeResult.Failure::class.java)
    }
}
