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
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.PagesBuild
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.PagesSite
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

    @Test
    fun who_sees_it_is_public_or_private_and_internal_only_where_it_already_is() = runTest {
        val public = api().settings("tok", repo).value()
        val internal = api { json("""{"visibility":"internal","private":true,"archived":true,"default_branch":"main"}""") }.settings("tok", repo).value()
        // An older server says only whether it is private.
        val private = api { json("""{"private":true,"default_branch":"main"}""") }.settings("tok", repo).value()

        assertThat(public.visibility).isEqualTo(RepoVisibility.PUBLIC)
        assertThat(public.visibilities).containsExactly(RepoVisibility.PUBLIC, RepoVisibility.PRIVATE).inOrder()
        assertThat(public.archived).isFalse()
        assertThat(internal.visibility).isEqualTo(RepoVisibility.INTERNAL)
        assertThat(internal.visibilities).containsExactly(RepoVisibility.PUBLIC, RepoVisibility.INTERNAL, RepoVisibility.PRIVATE).inOrder()
        assertThat(internal.archived).isTrue()
        assertThat(private.visibility).isEqualTo(RepoVisibility.PRIVATE)
    }

    @Test
    fun visibility_and_archiving_patch_their_field() = runTest {
        val api = api()

        api.change("tok", repo, RepoSettingsChange.Visibility(RepoVisibility.PRIVATE)).value()
        api.change("tok", repo, RepoSettingsChange.Archived(true)).value()

        assertThat(requests.map { it.sent }).containsExactly("""{"visibility":"private"}""", """{"archived":true}""").inOrder()
        assertThat(requests.all { it.method == HttpMethod.Patch && it.path == "/repos/octo/tools" }).isTrue()
    }

    @Test
    fun a_renamed_repository_is_where_github_says_it_now_is() = runTest {
        val renamed = api { json("""{"name":"kit","full_name":"octo/kit"}""") }.rename("tok", repo, "kit").value()

        assertThat(renamed).isEqualTo(RepoId("octo", "kit"))
        assertThat(requests.single().method).isEqualTo(HttpMethod.Patch)
        assertThat(requests.single().sent).isEqualTo("""{"name":"kit"}""")
    }

    @Test
    fun a_repository_is_deleted_for_good() = runTest {
        val deleted = api { respond("", HttpStatusCode.NoContent) }.delete("tok", repo).value()

        assertThat(deleted).isEqualTo(RepoDeletion.DELETED)
        assertThat(requests.single().method).isEqualTo(HttpMethod.Delete)
        assertThat(requests.single().path).isEqualTo("/repos/octo/tools")
    }

    @Test
    fun a_sign_in_without_the_permission_to_delete_is_told_apart_from_a_refusal() = runTest {
        // As api.github.com answered a token without the scope on 2026-10-12: it lists the token's scopes, and none it wanted.
        fun MockRequestHandleScope.forbidden(scopes: String?) = respond(
            """{"message":"Must have admin rights to Repository."}""", HttpStatusCode.Forbidden,
            headersOf(*listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), scopes?.let { "X-OAuth-Scopes" to listOf(it) }, "X-Accepted-OAuth-Scopes" to listOf("")).toTypedArray()),
        )

        // Signed in before deleting was asked for: signing in again gives it.
        assertThat(api { forbidden("gist, read:org, repo, workflow") }.delete("tok", repo)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        // A fine-grained token lists no scopes: signing in again wouldn't change what it may do.
        assertThat(api { forbidden(null) }.delete("tok", repo)).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Must have admin rights to Repository.")))
        // Allowed to delete, and still refused: not one's repository to delete.
        assertThat(api { forbidden("delete_repo, repo") }.delete("tok", repo))
            .isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Must have admin rights to Repository.")))
    }

    @Test
    fun deleting_is_among_what_a_sign_in_asks_for() {
        assertThat(GitHubAuthApi.SCOPES).containsExactly("notifications", "read:user", "user:follow", "repo", "delete_repo").inOrder()
    }

    // Cut down from api.github.com's for a repository that publishes with a workflow, read on 2026-10-12.
    private val pagesJson = """{"url":"https://api.github.com/repos/octo/tools/pages","status":null,"cname":null,"html_url":"http://octo.example/tools/",
        "build_type":"workflow","source":{"branch":"main","path":"/"},"public":true,"https_enforced":false}"""

    private val HttpRequestData.call get() = "${method.value} $path"

    @Test
    fun a_published_site_says_where_it_is_and_what_it_is_built_from() = runTest {
        val workflow = api { json(pagesJson) }.pages("tok", repo).value()
        val branch = api { json("""{"cname":"tools.example","html_url":"https://tools.example/","build_type":"legacy","source":{"branch":"gh-pages","path":"/docs"},"https_enforced":true}""") }
            .pages("tok", repo).value()

        assertThat(workflow).isEqualTo(PagesSite(published = true, url = "http://octo.example/tools/", canPublish = true, build = PagesBuild.Workflow, httpsEnforced = false, customDomain = ""))
        assertThat(branch).isEqualTo(
            PagesSite(published = true, url = "https://tools.example/", canPublish = true, build = PagesBuild.Branch("gh-pages", "/docs"), httpsEnforced = true, customDomain = "tools.example"),
        )
        assertThat(requests.last().call).isEqualTo("GET /repos/octo/tools/pages")
    }

    @Test
    fun a_repository_that_publishes_nothing_can_be_made_to() = runTest {
        val site = api { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }.pages("tok", repo).value()

        assertThat(site).isEqualTo(PagesSite(published = false, canPublish = true))
    }

    @Test
    fun a_site_is_created_once_then_changed() = runTest {
        var exists = false
        val api = api {
            when {
                it.method == HttpMethod.Post -> { exists = true; json(pagesJson, HttpStatusCode.Created) }
                it.method == HttpMethod.Put -> respond("", HttpStatusCode.NoContent)
                exists -> json(pagesJson)
                else -> json("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            }
        }

        val published = api.changePages("tok", repo, PagesChange.Publish(PagesBuild.Workflow)).value()
        assertThat(published.published).isTrue()
        assertThat(requests.map { it.call }).containsExactly("GET /repos/octo/tools/pages", "POST /repos/octo/tools/pages", "GET /repos/octo/tools/pages").inOrder()
        assertThat(requests[1].sent).isEqualTo("""{"build_type":"workflow"}""")

        requests.clear()
        api.changePages("tok", repo, PagesChange.Publish(PagesBuild.Branch("gh-pages", "/docs"))).value()
        assertThat(requests.map { it.call }).containsExactly("GET /repos/octo/tools/pages", "PUT /repos/octo/tools/pages", "GET /repos/octo/tools/pages").inOrder()
        assertThat(requests[1].sent).isEqualTo("""{"build_type":"legacy","source":{"branch":"gh-pages","path":"/docs"}}""")
    }

    @Test
    fun https_and_the_domain_are_put_and_an_empty_domain_takes_it_away() = runTest {
        val api = api { if (it.method == HttpMethod.Put) respond("", HttpStatusCode.NoContent) else json(pagesJson) }

        api.changePages("tok", repo, PagesChange.Https(true)).value()
        api.changePages("tok", repo, PagesChange.Domain("tools.example")).value()
        api.changePages("tok", repo, PagesChange.Domain(" ")).value()

        assertThat(requests.filter { it.method == HttpMethod.Put }.map { it.sent })
            .containsExactly("""{"https_enforced":true}""", """{"cname":"tools.example"}""", """{"cname":null}""").inOrder()
    }

    @Test
    fun an_unpublished_site_is_read_as_gone() = runTest {
        val site = api { if (it.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent) else json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }
            .changePages("tok", repo, PagesChange.Unpublish).value()

        assertThat(site).isEqualTo(PagesSite(published = false, canPublish = true))
        assertThat(requests.map { it.call }).containsExactly("DELETE /repos/octo/tools/pages", "GET /repos/octo/tools/pages").inOrder()
    }

    @Test
    fun a_refused_change_to_the_site_fails() = runTest {
        // No certificate yet for the domain: HTTPS can't be enforced.
        val result = api { if (it.method == HttpMethod.Put) json("""{"message":"The certificate does not exist yet"}""", HttpStatusCode.NotFound) else json(pagesJson) }
            .changePages("tok", repo, PagesChange.Https(true))

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(404, "The certificate does not exist yet")))
    }

    @Test
    fun whether_a_sign_in_may_delete_is_read_from_the_scopes_github_lists() = runTest {
        fun MockRequestHandleScope.user(scopes: String?) = respond(
            """{"login":"octocat"}""", HttpStatusCode.OK,
            headersOf(*listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), scopes?.let { "X-OAuth-Scopes" to listOf(it) }).toTypedArray()),
        )

        assertThat(api { user("gist, read:org, repo, workflow") }.mayDelete("tok")).isEqualTo(ForgeResult.Success(false))
        assertThat(api { user("delete_repo, notifications, read:user, repo, user:follow") }.mayDelete("tok")).isEqualTo(ForgeResult.Success(true))
        // A fine-grained token lists none: it isn't judged before the deletion itself.
        assertThat(api { user(null) }.mayDelete("tok")).isEqualTo(ForgeResult.Success(true))
        assertThat(requests.last().path).isEqualTo("/user")
        assertThat(api { json("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized) }.mayDelete("tok")).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
    }
}
