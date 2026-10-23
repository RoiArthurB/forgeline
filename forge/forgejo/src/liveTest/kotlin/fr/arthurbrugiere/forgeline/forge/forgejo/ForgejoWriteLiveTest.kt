package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Changes a repository's settings on the real Codeberg. Never in CI: it runs on one's own machine, with a token that
 * may write and `CODEBERG_LIVE_WRITES=1`. Everything happens in a private scratch repository this test creates and
 * deletes; it touches nothing else of the account.
 */
class ForgejoWriteLiveTest {
    private val token = System.getenv("CODEBERG_LIVE_TOKEN").orEmpty()
    private val http = forgejoHttpClient(OkHttp.create())
    private val api = "https://codeberg.org/api/v1"
    private val forge = ForgeInstance.Codeberg

    private fun <T> ForgeResult<T>.value(what: String): T = (this as? ForgeResult.Success)?.value ?: error("$what: $this")

    @Test
    fun a_repository_s_settings_are_changed_and_it_is_renamed_and_deleted() = runBlocking<Unit> {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        assumeTrue("CODEBERG_LIVE_WRITES not set", System.getenv("CODEBERG_LIVE_WRITES") == "1")
        val name = "forgeline-scratch-${System.currentTimeMillis()}"
        val created = http.post("$api/user/repos") {
            header("Authorization", "token $token")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", name)
                    put("private", true)
                    put("auto_init", true)
                    put("default_branch", "main")
                    put("description", "Scratch repository for Forgeline API tests. Safe to delete.")
                },
            )
        }
        check(created.status.isSuccess()) { "create: ${created.status} ${created.bodyAsText().take(300)}" }
        val (owner, _) = created.body<JsonObject>().getValue("full_name").jsonPrimitive.content.split('/', limit = 2)
        var repo = RepoId(owner, name, forge)
        var gone = false
        val settings = ForgejoRepoSettingsApi(http, forge)
        try {
            val read = settings.settings(token, repo).value("settings")
            System.err.println("CODEBERG scratch settings: $read")
            check(read.visibility == RepoVisibility.PRIVATE && !read.archived && read.defaultBranch == "main") { "as created: $read" }
            check(RepoSwitch.MERGE_COMMIT in read.switches && read.defaultMerge != null) { "merging: $read" }

            suspend fun change(change: RepoSettingsChange) = settings.change(token, repo, change).value("$change")
            check(change(RepoSettingsChange.Description("Changed by a test")).description == "Changed by a test")
            check(change(RepoSettingsChange.Website("https://example.org")).website == "https://example.org")
            check(change(RepoSettingsChange.Topics(listOf("scratch", "forgeline"))).topics.toSet() == setOf("scratch", "forgeline"))
            check(change(RepoSettingsChange.Switch(RepoSwitch.WIKI, false)).switches[RepoSwitch.WIKI] == false)
            check(change(RepoSettingsChange.Switch(RepoSwitch.WIKI, true)).switches[RepoSwitch.WIKI] == true)
            check(change(RepoSettingsChange.Switch(RepoSwitch.PACKAGES, false)).switches[RepoSwitch.PACKAGES] == false)
            check(change(RepoSettingsChange.Switch(RepoSwitch.REBASE_MERGE, false)).switches[RepoSwitch.REBASE_MERGE] == false)
            check(change(RepoSettingsChange.Switch(RepoSwitch.DELETE_BRANCH_ON_MERGE, true)).switches[RepoSwitch.DELETE_BRANCH_ON_MERGE] == true)
            check(change(RepoSettingsChange.DefaultMerge(MergeMethod.SQUASH)).defaultMerge == MergeMethod.SQUASH)
            check(change(RepoSettingsChange.DefaultBranch("main")).defaultBranch == "main")
            // Seen by everyone for the time of two calls, then private again.
            check(change(RepoSettingsChange.Visibility(RepoVisibility.PUBLIC)).visibility == RepoVisibility.PUBLIC)
            check(change(RepoSettingsChange.Visibility(RepoVisibility.PRIVATE)).visibility == RepoVisibility.PRIVATE)
            check(change(RepoSettingsChange.Archived(true)).archived)
            check(!change(RepoSettingsChange.Archived(false)).archived)
            // Forgejo sets nothing about a website.
            check(settings.pages(token, repo) == ForgeResult.Success(null))
            check(settings.changePages(token, repo, PagesChange.Unpublish) is ForgeResult.Failure)

            val renamed = settings.rename(token, repo, "$name-renamed").value("rename")
            check(renamed == RepoId(owner, "$name-renamed", forge)) { "renamed: $renamed" }
            repo = renamed
            check(settings.settings(token, repo).value("settings once renamed").description == "Changed by a test")

            val deletion = settings.delete(token, repo).value("delete")
            System.err.println("CODEBERG deletion: $deletion")
            check(deletion == RepoDeletion.DELETED)
            gone = true
            check(settings.settings(token, repo) is ForgeResult.Failure) { "still there after its deletion" }
        } finally {
            if (!gone) {
                val deleted = http.delete("$api/repos/${repo.owner}/${repo.name}") { header("Authorization", "token $token") }
                System.err.println("CODEBERG scratch repository ${repo.fullName} deleted by hand: ${deleted.status}")
            }
        }
    }

    /** A pull request in a private scratch repository: made a draft by its title and ready again, nobody asked to review it. */
    @Test
    fun a_pull_request_becomes_a_draft_and_ready_again() = runBlocking<Unit> {
        assumeTrue("CODEBERG_LIVE_TOKEN not set", token.isNotBlank())
        assumeTrue("CODEBERG_LIVE_WRITES not set", System.getenv("CODEBERG_LIVE_WRITES") == "1")
        val name = "forgeline-scratch-${System.currentTimeMillis()}"
        val created = http.post("$api/user/repos") {
            header("Authorization", "token $token")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("name", name)
                    put("private", true)
                    put("auto_init", true)
                    put("default_branch", "main")
                    put("description", "Scratch repository for Forgeline API tests. Safe to delete.")
                },
            )
        }
        check(created.status.isSuccess()) { "create: ${created.status} ${created.bodyAsText().take(300)}" }
        val (owner, _) = created.body<JsonObject>().getValue("full_name").jsonPrimitive.content.split('/', limit = 2)
        val repo = RepoId(owner, name, forge)
        try {
            // A change on a branch of its own, written through the contents API.
            val file = http.post("$api/repos/$owner/$name/contents/NOTES.md") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("content", java.util.Base64.getEncoder().encodeToString("A line the pull request adds.\n".toByteArray()))
                        put("message", "Add notes")
                        put("branch", "main")
                        put("new_branch", "change")
                    },
                )
            }
            check(file.status.isSuccess()) { "file: ${file.status} ${file.bodyAsText().take(300)}" }
            val ref = ForgejoPullRequestApi(http, forge).create(token, repo, NewPullRequest("Say more", "Opened by Forgeline's live test.", head = "change", base = "main")).value("create")
            val issues = ForgejoIssueApi(http, forge)

            issues.setDraft(token, ref, draft = true).value("to draft")
            val drafted = issues.issue(token, ref).value("drafted")
            System.err.println("CODEBERG drafted: ${drafted.title} draft=${drafted.pullRequest?.isDraft}")
            assertWithMessage("draft").that(drafted.pullRequest?.isDraft).isTrue()
            issues.setDraft(token, ref, draft = false).value("ready")
            val ready = issues.issue(token, ref).value("ready")
            assertWithMessage("ready").that(ready.pullRequest?.isDraft).isFalse()
            assertWithMessage("ready title").that(ready.title).isEqualTo("Say more")
            // Nobody to ask in a private repository of one; letting everyone off still answers yes.
            issues.setReviewers(token, ref, emptyList()).value("no reviewers")
            assertWithMessage("no reviewers").that(ready.pullRequest?.reviewers).isEmpty()

            // Merged with its branch: the branch is gone after.
            val pulls = ForgejoPullRequestApi(http, forge)
            var info = pulls.mergeInfo(token, ref).value("merge info")
            repeat(10) { if (info.mergeable != true) { kotlinx.coroutines.delay(1_000); info = pulls.mergeInfo(token, ref).value("merge info") } }
            System.err.println("CODEBERG merge info: $info")
            assertWithMessage("own branch can go").that(info.deleteBranch).isNotNull()
            pulls.merge(token, ref, MergeMethod.MERGE, deleteBranch = true).value("merge")
            val branch = http.get("$api/repos/$owner/$name/branches/change") { header("Authorization", "token $token") }.status
            System.err.println("CODEBERG merged branch: $branch")
            assertWithMessage("branch deleted").that(branch.value).isEqualTo(404)
        } finally {
            val deleted = http.delete("$api/repos/$owner/$name") { header("Authorization", "token $token") }
            System.err.println("CODEBERG scratch repository $owner/$name deleted: ${deleted.status}")
        }
    }
}
