package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.PagesChange
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.RepoVisibility
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
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
            // A pull request template on the default branch, with front matter as Forgejo's own has.
            val template = http.post("$api/repos/$owner/$name/contents/.forgejo/pull_request_template.md") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("content", java.util.Base64.getEncoder().encodeToString("---\nname: \"Scratch\"\nabout: \"For the test\"\n---\n\n## Why\n".toByteArray()))
                        put("message", "Add a pull request template")
                        put("branch", "main")
                    },
                )
            }
            check(template.status.isSuccess()) { "template: ${template.status} ${template.bodyAsText().take(300)}" }
            val templates = ForgejoPullRequestApi(http, forge).pullRequestTemplates(token, repo, "main").value("templates")
            assertWithMessage("template").that(templates.map { Triple(it.key, it.name, it.body) }).containsExactly(Triple(".forgejo/pull_request_template.md", "Scratch", "## Why\n"))

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

            // An issue opened from a template wears its labels, put on by name once it is open.
            val label = http.post("$api/repos/$owner/$name/labels") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("name", "from-template")
                        put("color", "#00aabb")
                    },
                )
            }
            check(label.status.isSuccess()) { "label: ${label.status} ${label.bodyAsText().take(300)}" }
            val labelled = issues.create(token, repo, "From a template", "Opened by Forgeline's live test.", labels = listOf("from-template")).value("labelled issue")
            assertWithMessage("labels").that(issues.issue(token, labelled.ref).value("labelled").labels.map { it.name }).containsExactly("from-template")

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

            // A label made from the picker: it is the repository's, and set on the pull request.
            val made = issues.createLabel(token, repo, "made-by-the-live-test", "#0e8a16").value("label made")
            assertWithMessage("label made").that(made.name).isEqualTo("made-by-the-live-test")
            assertWithMessage("label listed").that(issues.labels(token, repo).value("labels").map { it.name }).contains("made-by-the-live-test")
            issues.setLabels(token, ref, listOf("made-by-the-live-test")).value("label set")

            // Subscribed in the owner's name and let go again.
            issues.setSubscribed(token, owner, ref, subscribed = true).value("subscribe")
            assertWithMessage("subscribed").that(issues.isSubscribed(token, owner, ref).value("subscribed?")).isTrue()
            issues.setSubscribed(token, owner, ref, subscribed = false).value("unsubscribe")
            assertWithMessage("unsubscribed").that(issues.isSubscribed(token, owner, ref).value("unsubscribed?")).isFalse()

            // Pointed at another branch, then back at main.
            val branched = http.post("$api/repos/$owner/$name/branches") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("new_branch_name", "other")
                        put("old_branch_name", "main")
                    },
                )
            }
            check(branched.status.isSuccess()) { "branch: ${branched.status} ${branched.bodyAsText().take(300)}" }
            issues.setBase(token, ref, "other").value("to other")
            assertWithMessage("into other").that(issues.issue(token, ref).value("moved").pullRequest?.baseRef).isEqualTo("other")
            issues.setBase(token, ref, "main").value("back to main")
            assertWithMessage("into main").that(issues.issue(token, ref).value("back").pullRequest?.baseRef).isEqualTo("main")

            // A remark on the line the change adds is a thread, which a reply joins.
            val pulls = ForgejoPullRequestApi(http, forge)
            pulls.review(token, ref, ReviewVerdict.COMMENT, "", listOf(LineComment("NOTES.md", oldLine = null, newLine = 1, body = "Why this line?"))).value("remark")
            val thread = pulls.threads(token, ref).value("threads").single()
            assertWithMessage("thread on NOTES").that(thread.path).isEqualTo("NOTES.md")
            assertWithMessage("thread line").that(thread.line).isEqualTo(1)
            pulls.reply(token, ref, thread, "Answered by the live test.").value("reply")
            val after = pulls.threads(token, ref).value("threads after")
            System.err.println("CODEBERG threads after reply: ${after.map { "${it.path}:${it.line} ${it.comments.size}" }}")
            assertWithMessage("reply joined the thread").that(after.single().comments.map { it.body }).containsExactly("Why this line?", "Answered by the live test.").inOrder()
            // The reply is rewritten. Deleting one isn't offered: Forgejo answers 204 and keeps it (2026-10-25).
            val answer = after.single().comments.last()
            pulls.editThreadComment(token, ref, after.single(), answer, "Rewritten by the live test.").value("edit reply")
            val rewritten = pulls.threads(token, ref).value("threads rewritten").single()
            assertWithMessage("reply rewritten").that(rewritten.comments.map { it.body }).containsExactly("Why this line?", "Rewritten by the live test.").inOrder()
            // A reaction on the remark is given, read back with the threads, and taken back.
            val remark = rewritten.comments.first()
            val given = pulls.toggleThreadReaction(token, owner, ref, rewritten, remark, fr.arthurbrugiere.forgeline.core.model.Reaction.HEART).value("react")
            assertWithMessage("reaction given").that(given).containsExactly(fr.arthurbrugiere.forgeline.core.model.Reaction.HEART, 1)
            val reacted = pulls.threads(token, ref).value("threads reacted").single().comments.first()
            assertWithMessage("reaction read").that(reacted.reactions).containsExactly(fr.arthurbrugiere.forgeline.core.model.Reaction.HEART, 1)
            pulls.toggleThreadReaction(token, owner, ref, rewritten, remark, fr.arthurbrugiere.forgeline.core.model.Reaction.HEART).value("unreact")
            assertWithMessage("reaction taken back").that(pulls.threads(token, ref).value("threads unreacted").single().comments.first().reactions).isEmpty()

            // A suggestion on that line is committed to the pull request's branch; once made, the same one is a conflict.
            pulls.review(token, ref, ReviewVerdict.COMMENT, "", listOf(LineComment("NOTES.md", oldLine = null, newLine = 1, body = "```suggestion\nA line the pull request changes.\n```"))).value("suggest")
            val suggested = pulls.threads(token, ref).value("suggested threads").single()
            System.err.println("CODEBERG suggested over: ${suggested.suggestedOver}")
            assertWithMessage("lines suggested over").that(suggested.suggestedOver.map { it.text }).containsExactly("A line the pull request adds.")
            pulls.applySuggestion(token, ref, suggested, listOf("A line the pull request changes."), "Apply suggestion from code review").value("apply suggestion")
            val notes = ForgejoRepoApi(http, forge).fileText(token, repo, "NOTES.md", "change").value("notes after suggestion")
            assertWithMessage("suggestion committed").that(notes).isEqualTo("A line the pull request changes.\n")
            val again = pulls.applySuggestion(token, ref, suggested, listOf("Something else."), "Apply suggestion from code review")
            assertWithMessage("stale suggestion").that(again).isEqualTo(ForgeResult.Failure(ForgeError.Conflict))

            // main moves on: the pull request falls behind, and main is merged into its branch.
            val onMain = http.post("$api/repos/$owner/$name/contents/OTHER.md") {
                header("Authorization", "token $token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("content", java.util.Base64.getEncoder().encodeToString("Notes on main.\n".toByteArray()))
                        put("message", "Add notes on main")
                        put("branch", "main")
                    },
                )
            }
            check(onMain.status.isSuccess()) { "main: ${onMain.status} ${onMain.bodyAsText().take(300)}" }
            val behind = pulls.mergeInfo(token, ref).value("behind info")
            System.err.println("CODEBERG behind: ${behind.behind}")
            assertWithMessage("behind main").that(behind.behind).isTrue()
            pulls.updateBranch(token, ref).value("update")
            var caughtUp = pulls.mergeInfo(token, ref).value("updated info")
            repeat(10) { if (caughtUp.behind) { kotlinx.coroutines.delay(1_000); caughtUp = pulls.mergeInfo(token, ref).value("updated info") } }
            System.err.println("CODEBERG after update, behind: ${caughtUp.behind}")
            assertWithMessage("up to date").that(caughtUp.behind).isFalse()

            // Merged with its branch: the branch is gone after.
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
