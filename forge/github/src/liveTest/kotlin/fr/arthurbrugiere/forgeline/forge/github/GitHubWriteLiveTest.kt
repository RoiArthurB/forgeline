package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.PagesBuild
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Changes a repository's settings on the real GitHub. Never in CI: it runs on one's own machine, with a token that
 * may write and `GITHUB_LIVE_WRITES=1`. Everything happens in a private scratch repository this test creates and
 * deletes; it touches nothing else of the account. A token without `delete_repo` can't delete it: the test then says
 * so, having checked that the refusal is told apart, and the repository is left to be deleted by hand.
 */
class GitHubWriteLiveTest {
    private val token = System.getenv("LIVE_TEST_PAT").orEmpty()
    private val http = gitHubHttpClient(OkHttp.create())

    private fun <T> ForgeResult<T>.value(what: String): T = (this as? ForgeResult.Success)?.value ?: error("$what: $this")

    @Test
    fun a_repository_s_settings_and_site_are_changed_and_it_is_renamed_and_deleted() = runBlocking<Unit> {
        assumeTrue("LIVE_TEST_PAT not set", token.isNotBlank())
        assumeTrue("GITHUB_LIVE_WRITES not set", System.getenv("GITHUB_LIVE_WRITES") == "1")
        val name = "forgeline-scratch-${System.currentTimeMillis()}"
        val created = http.post("https://api.github.com/user/repos") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", name)
                    put("private", true)
                    put("auto_init", true)
                    put("description", "Scratch repository for Forgeline API tests. Safe to delete.")
                },
            )
        }
        check(created.status.isSuccess()) { "create: ${created.status} ${created.bodyAsText().take(300)}" }
        val (owner, _) = created.body<JsonObject>().getValue("full_name").jsonPrimitive.content.split('/', limit = 2)
        var repo = RepoId(owner, name)
        val settings = GitHubRepoSettingsApi(http)
        var left = true
        try {
            val read = settings.settings(token, repo).value("settings")
            System.err.println("GITHUB scratch settings: $read")
            check(read.visibility == RepoVisibility.PRIVATE && !read.archived) { "as created: $read" }
            // Whoever administers the repository is told how it merges.
            check(RepoSwitch.MERGE_COMMIT in read.switches && RepoSwitch.DELETE_BRANCH_ON_MERGE in read.switches) { "merging: $read" }

            suspend fun change(change: RepoSettingsChange) = settings.change(token, repo, change).value("$change")
            check(change(RepoSettingsChange.Description("Changed by a test")).description == "Changed by a test")
            check(change(RepoSettingsChange.Website("https://example.org")).website == "https://example.org")
            check(change(RepoSettingsChange.Topics(listOf("scratch", "forgeline"))).topics.toSet() == setOf("scratch", "forgeline"))
            check(change(RepoSettingsChange.Switch(RepoSwitch.WIKI, false)).switches[RepoSwitch.WIKI] == false)
            check(change(RepoSettingsChange.Switch(RepoSwitch.PROJECTS, false)).switches[RepoSwitch.PROJECTS] == false)
            check(change(RepoSettingsChange.Switch(RepoSwitch.REBASE_MERGE, false)).switches[RepoSwitch.REBASE_MERGE] == false)
            check(change(RepoSettingsChange.Switch(RepoSwitch.DELETE_BRANCH_ON_MERGE, true)).switches[RepoSwitch.DELETE_BRANCH_ON_MERGE] == true)
            check(change(RepoSettingsChange.Switch(RepoSwitch.TEMPLATE, true)).switches[RepoSwitch.TEMPLATE] == true)
            check(change(RepoSettingsChange.Switch(RepoSwitch.TEMPLATE, false)).switches[RepoSwitch.TEMPLATE] == false)
            check(change(RepoSettingsChange.DefaultBranch(read.defaultBranch)).defaultBranch == read.defaultBranch)

            // A free account publishes from public repositories only: seen by everyone for the time of the site's calls.
            check(change(RepoSettingsChange.Visibility(RepoVisibility.PUBLIC)).visibility == RepoVisibility.PUBLIC)
            val none = settings.pages(token, repo).value("pages")
            check(none != null && !none.published && none.canPublish) { "pages before: $none" }
            val branch = PagesBuild.Branch(read.defaultBranch, "/")
            val published = settings.changePages(token, repo, PagesChange.Publish(branch)).value("publish")
            System.err.println("GITHUB pages published: $published")
            check(published.published && published.build == branch && published.url != null) { "published: $published" }
            val rebuilt = settings.changePages(token, repo, PagesChange.Publish(PagesBuild.Workflow)).value("build with a workflow")
            check(rebuilt.build == PagesBuild.Workflow) { "source changed: $rebuilt" }
            // HTTPS waits for the site's certificate: whether GitHub takes it or says why not, the answer is read.
            System.err.println("GITHUB pages https: ${settings.changePages(token, repo, PagesChange.Https(true))}")
            val unpublished = settings.changePages(token, repo, PagesChange.Unpublish).value("unpublish")
            check(!unpublished.published) { "unpublished: $unpublished" }
            check(change(RepoSettingsChange.Visibility(RepoVisibility.PRIVATE)).visibility == RepoVisibility.PRIVATE)

            check(change(RepoSettingsChange.Archived(true)).archived)
            check(!change(RepoSettingsChange.Archived(false)).archived)

            val renamed = settings.rename(token, repo, "$name-renamed").value("rename")
            check(renamed == RepoId(owner, "$name-renamed")) { "renamed: $renamed" }
            repo = renamed
            delay(1_000)
            check(settings.settings(token, repo).value("settings once renamed").description == "Changed by a test")

            val deletion = settings.delete(token, repo)
            System.err.println("GITHUB deletion: $deletion")
            // A token never given `delete_repo` is told to sign in again, not that the forge failed.
            check(deletion == ForgeResult.Success(RepoDeletion.DELETED) || deletion == ForgeResult.Failure(ForgeError.Unauthorized)) { "deletion: $deletion" }
            left = deletion is ForgeResult.Failure
        } finally {
            if (left) System.err.println("GITHUB scratch repository LEFT, delete it by hand: ${repo.fullName}")
        }
    }

    /**
     * A pull request in a private scratch repository: merged with its branch deleted, the branch offered back and made
     * again where it stood, then the merge reverted by a new pull request.
     */
    @Test
    fun a_merged_pull_request_s_branch_is_restored_and_the_merge_reverted() = runBlocking<Unit> {
        assumeTrue("LIVE_TEST_PAT not set", token.isNotBlank())
        assumeTrue("GITHUB_LIVE_WRITES not set", System.getenv("GITHUB_LIVE_WRITES") == "1")
        val api = "https://api.github.com"
        val name = "forgeline-scratch-${System.currentTimeMillis()}"
        suspend fun send(method: HttpMethod, path: String, body: JsonObject? = null): JsonObject {
            val response = http.request("$api$path") {
                this.method = method
                header("Authorization", "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            check(response.status.isSuccess()) { "$method $path: ${response.status} ${response.bodyAsText().take(300)}" }
            return response.body()
        }
        val created = send(
            HttpMethod.Post, "/user/repos",
            buildJsonObject {
                put("name", name)
                put("private", true)
                put("auto_init", true)
                put("description", "Scratch repository for Forgeline API tests. Safe to delete.")
            },
        )
        val repo = RepoId(created.getValue("owner").jsonObject.getValue("login").jsonPrimitive.content, name)
        val path = "/repos/${repo.fullName}"
        var left = true
        try {
            val base = created.getValue("default_branch").jsonPrimitive.content
            val start = send(HttpMethod.Get, "$path/git/ref/heads/$base").getValue("object").jsonObject.getValue("sha").jsonPrimitive.content
            send(HttpMethod.Post, "$path/git/refs", buildJsonObject { put("ref", "refs/heads/change"); put("sha", start) })
            send(
                HttpMethod.Put, "$path/contents/NOTES.md",
                buildJsonObject {
                    put("message", "Add notes")
                    put("content", java.util.Base64.getEncoder().encodeToString("A line the pull request adds.\n".toByteArray()))
                    put("branch", "change")
                },
            )
            val number = send(HttpMethod.Post, "$path/pulls", buildJsonObject { put("title", "Add notes"); put("head", "change"); put("base", base) })
                .getValue("number").jsonPrimitive.content.toInt()
            val ref = IssueRef(repo, number, isPullRequest = true)
            val pulls = GitHubPullRequestApi(http)

            var info = pulls.mergeInfo(token, ref).value("merge info")
            repeat(10) { if (info.mergeable != true) { delay(1_000); info = pulls.mergeInfo(token, ref).value("merge info") } }
            pulls.merge(token, ref, MergeMethod.MERGE, deleteBranch = true).value("merge")

            val merged = pulls.mergeInfo(token, ref).value("merged info")
            System.err.println("GITHUB merged info: $merged")
            check(merged.deletedBranch?.name == "change" && merged.canRevert) { "merged: $merged" }
            pulls.restoreBranch(token, ref, merged.deletedBranch!!).value("restore")
            val restored = send(HttpMethod.Get, "$path/branches/change").getValue("commit").jsonObject.getValue("sha").jsonPrimitive.content
            check(restored == merged.deletedBranch!!.sha) { "restored at $restored" }
            check(pulls.mergeInfo(token, ref).value("restored info").deletedBranch == null) { "still offered once restored" }

            val revert = pulls.revert(token, ref).value("revert")
            System.err.println("GITHUB revert: $revert")
            check(revert.number > number) { "revert: $revert" }

            val deletion = GitHubRepoSettingsApi(http).delete(token, repo)
            System.err.println("GITHUB deletion: $deletion")
            left = deletion is ForgeResult.Failure
        } finally {
            if (left) System.err.println("GITHUB scratch repository LEFT, delete it by hand: ${repo.fullName}")
        }
    }
}
