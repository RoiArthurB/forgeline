package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
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
}
