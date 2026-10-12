package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.PagesBuild
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
import org.junit.Test

/** Answers are cut down from gitlab.com's for a project its reader owns, read on 2026-10-12. */
class GitLabRepoSettingsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("group/sub", "app", ForgeInstance.GitLab)
    private val base = "/api/v4/projects/group%2Fsub%2Fapp"

    private val projectJson = """{"id":87459020,"path_with_namespace":"group/sub/app","description":"Scratch project","visibility":"private","archived":false,
        "default_branch":"main","topics":["ci"],"issues_access_level":"enabled","merge_requests_access_level":"enabled","wiki_access_level":"disabled",
        "builds_access_level":"private","releases_access_level":"enabled","pages_access_level":"private","merge_method":"rebase_merge",
        "squash_option":"default_off","remove_source_branch_after_merge":true,"only_allow_merge_if_pipeline_succeeds":false,
        "only_allow_merge_if_all_discussions_are_resolved":true}"""

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json(projectJson) }) =
        GitLabRepoSettingsApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun a_project_s_settings_say_what_is_on_for_whom_and_the_one_way_it_merges() = runTest {
        val settings = api().settings("tok", repo).value()

        assertThat(settings.description).isEqualTo("Scratch project")
        // A project has no website of its own.
        assertThat(settings.hasWebsite).isFalse()
        assertThat(settings.topics).containsExactly("ci")
        assertThat(settings.defaultBranch).isEqualTo("main")
        assertThat(settings.switches).containsExactly(
            RepoSwitch.ISSUES, true, RepoSwitch.PULL_REQUESTS, true, RepoSwitch.WIKI, false, RepoSwitch.CI, true, RepoSwitch.RELEASES, true,
            RepoSwitch.SQUASH_MERGE, true, RepoSwitch.DELETE_BRANCH_ON_MERGE, true, RepoSwitch.REQUIRE_PIPELINE, false, RepoSwitch.REQUIRE_RESOLVED_THREADS, true,
        ).inOrder()
        assertThat(settings.membersOnly).containsExactly(RepoSwitch.CI)
        assertThat(settings.mergeStyle).isEqualTo(MergeStyle.SEMI_LINEAR)
        assertThat(settings.defaultMerge).isNull()
        assertThat(requests.single().url.encodedPath).isEqualTo(base)
    }

    @Test
    fun squashing_gitlab_never_allows_shows_as_off() = runTest {
        val settings = api { json("""{"description":null,"default_branch":"main","squash_option":"never","merge_method":"ff"}""") }.settings("tok", repo).value()

        assertThat(settings.switches).containsExactly(RepoSwitch.SQUASH_MERGE, false)
        assertThat(settings.mergeStyle).isEqualTo(MergeStyle.FAST_FORWARD)
        assertThat(settings.description).isEmpty()
    }

    @Test
    fun each_change_puts_its_one_field() = runTest {
        val api = api()
        val changes = listOf(
            RepoSettingsChange.Description("Faster") to """{"description":"Faster"}""",
            RepoSettingsChange.Topics(listOf("a", "b")) to """{"topics":["a","b"]}""",
            RepoSettingsChange.DefaultBranch("dev") to """{"default_branch":"dev"}""",
            RepoSettingsChange.Style(MergeStyle.FAST_FORWARD) to """{"merge_method":"ff"}""",
            RepoSettingsChange.Style(MergeStyle.MERGE_COMMIT) to """{"merge_method":"merge"}""",
            // A feature switched on is on for everyone who can see the project.
            RepoSettingsChange.Switch(RepoSwitch.WIKI, true) to """{"wiki_access_level":"enabled"}""",
            RepoSettingsChange.Switch(RepoSwitch.CI, false) to """{"builds_access_level":"disabled"}""",
            RepoSettingsChange.Switch(RepoSwitch.PULL_REQUESTS, false) to """{"merge_requests_access_level":"disabled"}""",
            RepoSettingsChange.Switch(RepoSwitch.SQUASH_MERGE, false) to """{"squash_option":"never"}""",
            RepoSettingsChange.Switch(RepoSwitch.SQUASH_MERGE, true) to """{"squash_option":"default_off"}""",
            RepoSettingsChange.Switch(RepoSwitch.REQUIRE_PIPELINE, true) to """{"only_allow_merge_if_pipeline_succeeds":true}""",
            RepoSettingsChange.Switch(RepoSwitch.DELETE_BRANCH_ON_MERGE, false) to """{"remove_source_branch_after_merge":false}""",
        )

        for ((change, body) in changes) {
            requests.clear()
            assertThat(api.change("tok", repo, change).value().defaultBranch).isEqualTo("main")
            assertThat(requests.single().method).isEqualTo(HttpMethod.Put)
            assertThat(requests.single().url.encodedPath).isEqualTo(base)
            assertThat(requests.single().sent).isEqualTo(body)
        }
    }

    @Test
    fun what_gitlab_has_no_setting_for_is_asked_of_no_one() = runTest {
        val api = api()

        for (change in listOf(
            RepoSettingsChange.Website("https://a.example"), RepoSettingsChange.Switch(RepoSwitch.DISCUSSIONS, true),
            RepoSettingsChange.Switch(RepoSwitch.MERGE_COMMIT, false), RepoSettingsChange.DefaultMerge(MergeMethod.SQUASH),
        )) {
            assertThat(api.change("tok", repo, change)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        }
        assertThat(requests).isEmpty()
    }

    @Test
    fun a_refused_change_fails() = runTest {
        val result = api { json("""{"message":"403 Forbidden"}""", HttpStatusCode.Forbidden) }.change("tok", repo, RepoSettingsChange.Description("x"))

        assertThat(result).isInstanceOf(ForgeResult.Failure::class.java)
    }

    @Test
    fun a_project_is_public_internal_or_private_and_says_whether_it_is_archived() = runTest {
        val settings = api().settings("tok", repo).value()

        assertThat(settings.visibility).isEqualTo(RepoVisibility.PRIVATE)
        assertThat(settings.visibilities).containsExactly(RepoVisibility.PUBLIC, RepoVisibility.INTERNAL, RepoVisibility.PRIVATE).inOrder()
        assertThat(settings.archived).isFalse()
    }

    @Test
    fun visibility_is_put_and_archiving_is_a_call_of_its_own() = runTest {
        val api = api()

        api.change("tok", repo, RepoSettingsChange.Visibility(RepoVisibility.INTERNAL)).value()
        api.change("tok", repo, RepoSettingsChange.Archived(true)).value()
        api.change("tok", repo, RepoSettingsChange.Archived(false)).value()

        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PUT $base", "POST $base/archive", "POST $base/unarchive").inOrder()
        assertThat(requests.first().sent).isEqualTo("""{"visibility":"internal"}""")
    }

    @Test
    fun a_renamed_project_takes_the_name_as_its_path_too() = runTest {
        val renamed = api { json("""{"id":1,"name":"kit","path":"kit","path_with_namespace":"group/sub/kit"}""") }.rename("tok", repo, "kit").value()

        assertThat(renamed).isEqualTo(RepoId("group/sub", "kit", ForgeInstance.GitLab))
        assertThat(requests.single().method).isEqualTo(HttpMethod.Put)
        assertThat(requests.single().sent).isEqualTo("""{"name":"kit","path":"kit"}""")
    }

    @Test
    fun a_project_gitlab_keeps_a_while_is_scheduled_for_deletion() = runTest {
        // gitlab.com, read on 2026-10-12: the project stays, under another path, marked with the day it was deleted.
        val deletion = api {
            when {
                it.method == HttpMethod.Delete -> json("""{"message":"202 Accepted"}""", HttpStatusCode.Accepted)
                it.url.encodedPath.endsWith("/87459020") -> json("""{"id":87459020,"path":"app-deletion_scheduled-87459020","marked_for_deletion_on":"2026-10-10"}""")
                else -> json(projectJson)
            }
        }.delete("tok", repo).value()

        assertThat(deletion).isEqualTo(RepoDeletion.SCHEDULED)
        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" })
            .containsExactly("GET $base", "DELETE /api/v4/projects/87459020", "GET /api/v4/projects/87459020").inOrder()
    }

    @Test
    fun a_project_that_is_no_longer_there_is_deleted() = runTest {
        val deletion = api {
            when {
                it.method == HttpMethod.Delete -> json("""{"message":"202 Accepted"}""", HttpStatusCode.Accepted)
                it.url.encodedPath.endsWith("/87459020") -> json("""{"message":"404 Project Not Found"}""", HttpStatusCode.NotFound)
                else -> json(projectJson)
            }
        }.delete("tok", repo).value()

        assertThat(deletion).isEqualTo(RepoDeletion.DELETED)
    }

    @Test
    fun a_refused_deletion_fails() = runTest {
        val refused = api { if (it.method == HttpMethod.Delete) json("""{"message":"403 Forbidden"}""", HttpStatusCode.Forbidden) else json(projectJson) }.delete("tok", repo)
        val unknown = api { json("""{"message":"404 Project Not Found"}""", HttpStatusCode.NotFound) }.delete("tok", repo)

        assertThat(refused).isInstanceOf(ForgeResult.Failure::class.java)
        assertThat(unknown).isInstanceOf(ForgeResult.Failure::class.java)
        // What couldn't be read isn't asked to be deleted.
        assertThat(requests.last().method).isEqualTo(HttpMethod.Get)
    }

    // gitlab.com's answers for a project that publishes nothing yet, read on 2026-10-12, and for one that does.
    private val idleJson = """{"url":"https://app-be7c9b.gitlab.io","deployments":[],"is_unique_domain_enabled":true,"force_https":true,"pages_primary_domain":null}"""
    private val liveJson = """{"url":"https://app-be7c9b.gitlab.io","deployments":[{"created_at":"2026-10-01T10:00:00Z","url":"https://app-be7c9b.gitlab.io","path_prefix":""}],"force_https":false}"""

    @Test
    fun a_project_s_site_is_published_once_its_ci_deployed_one() = runTest {
        val idle = api { json(idleJson) }.pages("tok", repo).value()
        val live = api { json(liveJson) }.pages("tok", repo).value()
        val none = api { json("""{"message":"404 Not Found"}""", HttpStatusCode.NotFound) }.pages("tok", repo).value()

        // Nothing switches it on from here: a job of the project's CI does.
        assertThat(idle).isEqualTo(PagesSite(published = false, url = "https://app-be7c9b.gitlab.io", httpsEnforced = true))
        assertThat(live).isEqualTo(PagesSite(published = true, url = "https://app-be7c9b.gitlab.io", httpsEnforced = false))
        assertThat(none).isNull()
        assertThat(requests.last().url.encodedPath).isEqualTo("$base/pages")
    }

    @Test
    fun https_is_patched_and_unpublishing_deletes_then_reads_again() = runTest {
        val api = api { if (it.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent) else json(idleJson) }

        assertThat(api.changePages("tok", repo, PagesChange.Https(true)).value().httpsEnforced).isTrue()
        assertThat(requests.single().method).isEqualTo(HttpMethod.Patch)
        assertThat(requests.single().sent).isEqualTo("""{"pages_https_only":true}""")

        requests.clear()
        assertThat(api.changePages("tok", repo, PagesChange.Unpublish).value().published).isFalse()
        assertThat(requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("DELETE $base/pages", "GET $base/pages").inOrder()
    }

    @Test
    fun what_gitlab_s_api_does_not_set_about_a_site_is_asked_of_no_one() = runTest {
        val api = api()

        assertThat(api.changePages("tok", repo, PagesChange.Publish(PagesBuild.Workflow))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.changePages("tok", repo, PagesChange.Domain("a.example"))).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(requests).isEmpty()
    }
}
