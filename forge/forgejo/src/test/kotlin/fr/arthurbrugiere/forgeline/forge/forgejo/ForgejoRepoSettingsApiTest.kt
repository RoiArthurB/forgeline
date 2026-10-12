package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import org.junit.Test

/** Fields are those of Codeberg's API description (`Repository`, `EditRepoOption`), read on 2026-10-12. */
class ForgejoRepoSettingsApiTest {
    private val codeberg = Codeberg()
    private val repo = RepoId("forgejo", "tools", ForgeInstance.Codeberg)
    private val base = "/api/v1/repos/forgejo/tools"

    private val repoJson = """{"name":"tools","description":"Tools","website":"https://tools.example","topics":["go"],"default_branch":"forgejo","private":false,
        "archived":false,"template":false,"has_issues":true,"has_pull_requests":true,"has_wiki":false,"has_projects":true,"has_actions":true,
        "has_releases":true,"has_packages":false,"allow_merge_commits":true,"allow_squash_merge":true,"allow_rebase":false,"allow_rebase_explicit":false,
        "allow_fast_forward_only_merge":true,"default_delete_branch_after_merge":false,"default_merge_style":"squash"}"""

    private fun api(route: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { with(codeberg) { json(repoJson) } }) =
        ForgejoRepoSettingsApi(codeberg.client { route(it) }, ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private val HttpRequestData.sent get() = (body as TextContent).text

    @Test
    fun the_settings_are_what_the_repository_says_of_itself() = runTest {
        val settings = api().settings("tok", repo).value()

        assertThat(settings.description).isEqualTo("Tools")
        assertThat(settings.website).isEqualTo("https://tools.example")
        assertThat(settings.hasWebsite).isTrue()
        assertThat(settings.topics).containsExactly("go")
        assertThat(settings.defaultBranch).isEqualTo("forgejo")
        assertThat(settings.switches).containsExactly(
            RepoSwitch.ISSUES, true, RepoSwitch.PULL_REQUESTS, true, RepoSwitch.WIKI, false, RepoSwitch.PROJECTS, true, RepoSwitch.CI, true,
            RepoSwitch.RELEASES, true, RepoSwitch.PACKAGES, false, RepoSwitch.TEMPLATE, false,
            RepoSwitch.MERGE_COMMIT, true, RepoSwitch.SQUASH_MERGE, true, RepoSwitch.REBASE_MERGE, false, RepoSwitch.FAST_FORWARD_MERGE, true,
            RepoSwitch.DELETE_BRANCH_ON_MERGE, false,
        ).inOrder()
        // Forgejo merges several ways and proposes one of them.
        assertThat(settings.mergeStyle).isNull()
        assertThat(settings.defaultMerge).isEqualTo(MergeMethod.SQUASH)
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo(base)
    }

    @Test
    fun a_repository_without_pull_requests_says_nothing_of_merging() = runTest {
        val settings = api { with(codeberg) { json("""{"description":"","default_branch":"main","has_issues":true,"has_pull_requests":false,"default_merge_style":"fast-forward-only"}""") } }
            .settings("tok", repo).value()

        assertThat(settings.switches).containsExactly(RepoSwitch.ISSUES, true, RepoSwitch.PULL_REQUESTS, false)
        // A way of merging the app has no name for is left as it is.
        assertThat(settings.defaultMerge).isNull()
    }

    @Test
    fun each_change_patches_its_one_field() = runTest {
        val api = api()
        val changes = listOf(
            RepoSettingsChange.Description("Faster") to """{"description":"Faster"}""",
            RepoSettingsChange.Website("") to """{"website":""}""",
            RepoSettingsChange.DefaultBranch("dev") to """{"default_branch":"dev"}""",
            RepoSettingsChange.Switch(RepoSwitch.PULL_REQUESTS, false) to """{"has_pull_requests":false}""",
            RepoSettingsChange.Switch(RepoSwitch.CI, false) to """{"has_actions":false}""",
            RepoSettingsChange.Switch(RepoSwitch.PACKAGES, true) to """{"has_packages":true}""",
            RepoSettingsChange.Switch(RepoSwitch.TEMPLATE, true) to """{"template":true}""",
            RepoSettingsChange.Switch(RepoSwitch.REBASE_MERGE, true) to """{"allow_rebase":true}""",
            RepoSettingsChange.Switch(RepoSwitch.FAST_FORWARD_MERGE, false) to """{"allow_fast_forward_only_merge":false}""",
            RepoSettingsChange.Switch(RepoSwitch.DELETE_BRANCH_ON_MERGE, true) to """{"default_delete_branch_after_merge":true}""",
            RepoSettingsChange.DefaultMerge(MergeMethod.REBASE) to """{"default_merge_style":"rebase"}""",
        )

        for ((change, body) in changes) {
            codeberg.requests.clear()
            assertThat(api.change("tok", repo, change).value().defaultBranch).isEqualTo("forgejo")
            assertThat(codeberg.requests.single().method).isEqualTo(HttpMethod.Patch)
            assertThat(codeberg.requests.single().url.encodedPath).isEqualTo(base)
            assertThat(codeberg.requests.single().sent).isEqualTo(body)
        }
    }

    @Test
    fun topics_are_replaced_as_a_whole_then_the_repository_read_again() = runTest {
        val settings = api { with(codeberg) { if (it.url.encodedPath.endsWith("/topics")) status(HttpStatusCode.NoContent) else json(repoJson) } }
            .change("tok", repo, RepoSettingsChange.Topics(listOf("a", "b"))).value()

        assertThat(codeberg.requests.map { "${it.method.value} ${it.url.encodedPath}" }).containsExactly("PUT $base/topics", "GET $base").inOrder()
        assertThat(codeberg.requests.first().sent).isEqualTo("""{"topics":["a","b"]}""")
        assertThat(settings.topics).containsExactly("go")
    }

    @Test
    fun what_forgejo_has_no_setting_for_is_asked_of_no_one() = runTest {
        val api = api()

        for (change in listOf(
            RepoSettingsChange.Switch(RepoSwitch.DISCUSSIONS, true), RepoSettingsChange.Switch(RepoSwitch.AUTO_MERGE, true),
            RepoSettingsChange.Style(MergeStyle.FAST_FORWARD),
        )) {
            assertThat(api.change("tok", repo, change)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        }
        assertThat(codeberg.requests).isEmpty()
    }

    @Test
    fun a_refused_change_fails() = runTest {
        val result = api { with(codeberg) { json("""{"message":"forbidden"}""", HttpStatusCode.Forbidden) } }.change("tok", repo, RepoSettingsChange.Description("x"))
        val topics = api { with(codeberg) { status(HttpStatusCode.Forbidden) } }.change("tok", repo, RepoSettingsChange.Topics(listOf("a")))

        assertThat(result).isInstanceOf(ForgeResult.Failure::class.java)
        assertThat(topics).isInstanceOf(ForgeResult.Failure::class.java)
    }

    @Test
    fun a_repository_is_public_or_private_and_says_whether_it_is_archived() = runTest {
        val public = api().settings("tok", repo).value()
        val hidden = api { with(codeberg) { json("""{"private":true,"archived":true,"default_branch":"main"}""") } }.settings("tok", repo).value()

        assertThat(public.visibility).isEqualTo(RepoVisibility.PUBLIC)
        assertThat(public.visibilities).containsExactly(RepoVisibility.PUBLIC, RepoVisibility.PRIVATE).inOrder()
        assertThat(public.archived).isFalse()
        assertThat(hidden.visibility).isEqualTo(RepoVisibility.PRIVATE)
        assertThat(hidden.archived).isTrue()
    }

    @Test
    fun visibility_and_archiving_patch_their_field() = runTest {
        val api = api()

        api.change("tok", repo, RepoSettingsChange.Visibility(RepoVisibility.PRIVATE)).value()
        api.change("tok", repo, RepoSettingsChange.Visibility(RepoVisibility.PUBLIC)).value()
        api.change("tok", repo, RepoSettingsChange.Archived(true)).value()

        assertThat(codeberg.requests.map { it.sent }).containsExactly("""{"private":true}""", """{"private":false}""", """{"archived":true}""").inOrder()
    }

    @Test
    fun a_renamed_repository_is_where_forgejo_says_it_now_is() = runTest {
        val renamed = api { with(codeberg) { json("""{"name":"kit","full_name":"forgejo/kit"}""") } }.rename("tok", repo, "kit").value()

        assertThat(renamed).isEqualTo(RepoId("forgejo", "kit", ForgeInstance.Codeberg))
        assertThat(codeberg.requests.single().method).isEqualTo(HttpMethod.Patch)
        assertThat(codeberg.requests.single().sent).isEqualTo("""{"name":"kit"}""")
    }

    @Test
    fun a_repository_is_deleted_for_good_or_the_refusal_said() = runTest {
        val deleted = api { with(codeberg) { status(HttpStatusCode.NoContent) } }.delete("tok", repo).value()
        assertThat(deleted).isEqualTo(RepoDeletion.DELETED)
        assertThat(codeberg.requests.single().method).isEqualTo(HttpMethod.Delete)
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo(base)

        assertThat(api { with(codeberg) { status(HttpStatusCode.Forbidden) } }.delete("tok", repo)).isInstanceOf(ForgeResult.Failure::class.java)
    }

    @Test
    fun forgejo_sets_nothing_about_a_website() = runTest {
        val api = api()

        // Codeberg Pages serves a branch or a repository named for it: there is no setting to read or change.
        assertThat(api.pages("tok", repo)).isEqualTo(ForgeResult.Success(null))
        assertThat(api.changePages("tok", repo, PagesChange.Unpublish)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(codeberg.requests).isEmpty()
    }
}
