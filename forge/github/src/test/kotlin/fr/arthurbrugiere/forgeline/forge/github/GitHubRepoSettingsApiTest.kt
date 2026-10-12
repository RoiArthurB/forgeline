package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
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

/** Answers are cut down from api.github.com's for a repository its reader administers, read on 2026-10-12 (REST, version 2022-11-28). */
class GitHubRepoSettingsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("octo", "tools")

    private val repoJson = """{"name":"tools","private":false,"visibility":"public","archived":false,
        "description":"A fast client","homepage":null,"topics":["android","kotlin"],"default_branch":"main",
        "has_issues":true,"has_projects":true,"has_wiki":false,"has_discussions":false,"has_pages":true,"is_template":false,
        "allow_merge_commit":true,"allow_squash_merge":true,"allow_rebase_merge":false,"allow_auto_merge":false,"delete_branch_on_merge":true,
        "permissions":{"admin":true,"push":true,"pull":true}}"""

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json(repoJson) }) =
        GitHubRepoSettingsApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.path get() = url.encodedPath

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun the_settings_are_what_the_repository_says_of_itself() = runTest {
        val settings = api().settings("tok", repo).value()

        assertThat(settings.description).isEqualTo("A fast client")
        assertThat(settings.website).isEmpty()
        assertThat(settings.hasWebsite).isTrue()
        assertThat(settings.topics).containsExactly("android", "kotlin").inOrder()
        assertThat(settings.defaultBranch).isEqualTo("main")
        assertThat(settings.switches).containsExactly(
            RepoSwitch.ISSUES, true, RepoSwitch.WIKI, false, RepoSwitch.DISCUSSIONS, false, RepoSwitch.PROJECTS, true, RepoSwitch.TEMPLATE, false,
            RepoSwitch.MERGE_COMMIT, true, RepoSwitch.SQUASH_MERGE, true, RepoSwitch.REBASE_MERGE, false, RepoSwitch.AUTO_MERGE, false,
            RepoSwitch.DELETE_BRANCH_ON_MERGE, true,
        ).inOrder()
        // GitHub merges several ways and proposes none.
        assertThat(settings.mergeStyle).isNull()
        assertThat(settings.defaultMerge).isNull()
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools")
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun the_merge_settings_github_keeps_from_who_cannot_change_them_are_left_out() = runTest {
        val settings = api { json("""{"name":"tools","description":null,"default_branch":"main","has_issues":true,"has_wiki":true}""") }.settings("tok", repo).value()

        assertThat(settings.description).isEmpty()
        assertThat(settings.switches.keys).containsExactly(RepoSwitch.ISSUES, RepoSwitch.WIKI)
    }

    @Test
    fun each_change_patches_its_one_field() = runTest {
        val api = api()
        val changes = listOf(
            RepoSettingsChange.Description("Faster") to """{"description":"Faster"}""",
            RepoSettingsChange.Website("https://tools.example") to """{"homepage":"https://tools.example"}""",
            RepoSettingsChange.DefaultBranch("dev") to """{"default_branch":"dev"}""",
            RepoSettingsChange.Switch(RepoSwitch.WIKI, true) to """{"has_wiki":true}""",
            RepoSettingsChange.Switch(RepoSwitch.DISCUSSIONS, true) to """{"has_discussions":true}""",
            RepoSettingsChange.Switch(RepoSwitch.TEMPLATE, true) to """{"is_template":true}""",
            RepoSettingsChange.Switch(RepoSwitch.REBASE_MERGE, true) to """{"allow_rebase_merge":true}""",
            RepoSettingsChange.Switch(RepoSwitch.DELETE_BRANCH_ON_MERGE, false) to """{"delete_branch_on_merge":false}""",
        )

        for ((change, body) in changes) {
            requests.clear()
            // What GitHub answers is the repository as it now is.
            assertThat(api.change("tok", repo, change).value().defaultBranch).isEqualTo("main")
            assertThat(requests.single().method).isEqualTo(HttpMethod.Patch)
            assertThat(requests.single().path).isEqualTo("/repos/octo/tools")
            assertThat(requests.single().sent).isEqualTo(body)
        }
    }

    @Test
    fun topics_are_replaced_as_a_whole_then_the_repository_read_again() = runTest {
        val settings = api { if (it.path.endsWith("/topics")) json("""{"names":["a","b"]}""") else json(repoJson) }
            .change("tok", repo, RepoSettingsChange.Topics(listOf("a", "b"))).value()

        assertThat(requests.map { "${it.method.value} ${it.path}" }).containsExactly("PUT /repos/octo/tools/topics", "GET /repos/octo/tools").inOrder()
        assertThat(requests.first().sent).isEqualTo("""{"names":["a","b"]}""")
        assertThat(settings.topics).containsExactly("android", "kotlin")
    }

    @Test
    fun what_github_has_no_setting_for_is_asked_of_no_one() = runTest {
        val api = api()

        for (change in listOf(
            RepoSettingsChange.Switch(RepoSwitch.CI, false), RepoSettingsChange.Switch(RepoSwitch.PULL_REQUESTS, false),
            RepoSettingsChange.Style(MergeStyle.FAST_FORWARD), RepoSettingsChange.DefaultMerge(MergeMethod.SQUASH),
        )) {
            assertThat(api.change("tok", repo, change)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        }
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_refused_change_fails() = runTest {
        // Switching off the last way of merging.
        val result = api { json("""{"message":"Validation Failed"}""", HttpStatusCode.UnprocessableEntity) }
            .change("tok", repo, RepoSettingsChange.Switch(RepoSwitch.MERGE_COMMIT, false))
        val topics = api { json("""{"message":"Forbidden"}""", HttpStatusCode.Forbidden) }.change("tok", repo, RepoSettingsChange.Topics(listOf("a")))

        assertThat(result).isInstanceOf(ForgeResult.Failure::class.java)
        assertThat(topics).isInstanceOf(ForgeResult.Failure::class.java)
    }
}
