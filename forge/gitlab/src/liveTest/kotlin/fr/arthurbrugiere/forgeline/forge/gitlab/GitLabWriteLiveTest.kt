package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.MergeStyle
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSettingsChange
import fr.arthurbrugiere.forgeline.core.model.RepoSwitch
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What Forgeline writes to a merge request, against the real gitlab.com: opening one, remarking on a line, approving
 * and merging. Everything happens in a private scratch project this test creates and deletes; it touches nothing
 * else of the account. Run by hand only: it needs GITLAB_LIVE_TOKEN and GITLAB_LIVE_WRITES=1.
 */
class GitLabWriteLiveTest {
    private val token = System.getenv("GITLAB_LIVE_TOKEN").orEmpty()
    private val http = gitlabHttpClient(OkHttp.create())
    private val api = "https://gitlab.com/api/v4"

    private fun <T> ForgeResult<T>.value(what: String): T = (this as? ForgeResult.Success)?.value ?: error("$what: $this")

    private suspend fun send(path: String, body: JsonObject): HttpResponse = http.post("$api/$path") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }.also { check(it.status.isSuccess()) { "$path: ${it.status} ${it.bodyAsText().take(300)}" } }

    @Test
    fun a_merge_request_is_opened_reviewed_and_merged() = runBlocking<Unit> {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        assumeTrue("GITLAB_LIVE_WRITES not set", System.getenv("GITLAB_LIVE_WRITES") == "1")
        val name = "forgeline-scratch-${System.currentTimeMillis()}"
        val project = send(
            "projects",
            buildJsonObject {
                put("name", name)
                put("visibility", "private")
                put("initialize_with_readme", true)
                put("description", "Scratch project for Forgeline API tests. Safe to delete.")
            },
        ).body<JsonObject>()
        val id = project.getValue("id").jsonPrimitive.content
        val (owner, path) = project.getValue("path_with_namespace").jsonPrimitive.content.split('/', limit = 2)
        val repo = RepoId(owner, path, ForgeInstance.GitLab)
        try {
            // A merge request template on the default branch first, which the change then starts from.
            send(
                "projects/$id/repository/commits",
                buildJsonObject {
                    put("branch", "main")
                    put("commit_message", "Add a merge request template")
                    put(
                        "actions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("action", "create")
                                    put("file_path", ".gitlab/merge_request_templates/Default.md")
                                    put("content", "## Why\n\nFrom %{source_branch}.\n")
                                },
                            )
                        },
                    )
                },
            )
            send(
                "projects/$id/repository/commits",
                buildJsonObject {
                    put("branch", "change")
                    put("start_branch", "main")
                    put("commit_message", "Say more in the README")
                    put(
                        "actions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("action", "update")
                                    put("file_path", "README.md")
                                    put("content", "# $name\n\nA line the merge request adds.\n")
                                },
                            )
                        },
                    )
                },
            )
            val pulls = GitLabPullRequestApi(http)
            val templates = pulls.pullRequestTemplates(token, repo, "main").value("templates")
            assertWithMessage("templates").that(templates.map { it.key }).contains("Default")
            val default = pulls.pullRequestTemplate(token, repo, templates.first { it.key == "Default" }).value("Default template")
            assertWithMessage("template text").that(default.body).isEqualTo("## Why\n\nFrom %{source_branch}.\n")

            val ref = pulls.create(token, repo, NewPullRequest("Say more in the README", "Opened by Forgeline's live test.", head = "change", base = "main")).value("create")
            assertWithMessage("opened as a merge request").that(ref.isPullRequest).isTrue()

            // GitLab works a new merge request's change out after answering: asked again until it is there.
            var files = pulls.files(token, ref).value("files").files
            repeat(10) { if (files.isEmpty()) { delay(1_000); files = pulls.files(token, ref).value("files").files } }
            val readme = files.single { it.path == "README.md" }
            val added = parsePatch(readme.patch!!).flatMap { it.lines }.first { it.kind == DiffLineKind.ADDED }
            assertWithMessage("commits").that(pulls.commits(token, ref).value("commits")).hasSize(1)
            assertWithMessage("checks without CI").that(pulls.checks(token, ref).value("checks")).isEmpty()

            // A draft by its title and back, then oneself asked to review it and let off again.
            val issues = GitLabIssueApi(http, ForgeInstance.GitLab)
            // An issue opened from a template wears its labels; GitLab makes a label it doesn't know yet.
            val labelled = issues.create(token, repo, "From a template", "Opened by Forgeline's live test.", labels = listOf("from-template", "scratch")).value("labelled issue")
            assertWithMessage("labels").that(issues.issue(token, labelled.ref).value("labelled").labels.map { it.name }).containsExactly("from-template", "scratch")
            issues.setDraft(token, ref, draft = true).value("to draft")
            val drafted = issues.issue(token, ref).value("drafted")
            assertWithMessage("draft").that(drafted.pullRequest?.isDraft).isTrue()
            assertWithMessage("draft title").that(drafted.title).isEqualTo("Draft: Say more in the README")
            issues.setDraft(token, ref, draft = false).value("ready")
            val ready = issues.issue(token, ref).value("ready")
            assertWithMessage("ready").that(ready.pullRequest?.isDraft).isFalse()
            assertWithMessage("ready title").that(ready.title).isEqualTo("Say more in the README")
            val me = repo.owner
            issues.setReviewers(token, ref, listOf(me)).value("ask")
            assertWithMessage("reviewers").that(issues.issue(token, ref).value("asked").pullRequest?.reviewers?.map { it.login }).containsExactly(me)
            issues.setReviewers(token, ref, emptyList()).value("let off")
            assertWithMessage("no reviewers").that(issues.issue(token, ref).value("let off").pullRequest?.reviewers).isEmpty()

            // Its author hears of it from the start; unsubscribed, then subscribed again, twice (already so is done).
            issues.setSubscribed(token, me, ref, subscribed = false).value("unsubscribe")
            assertWithMessage("unsubscribed").that(issues.isSubscribed(token, me, ref).value("unsubscribed?")).isFalse()
            issues.setSubscribed(token, me, ref, subscribed = true).value("subscribe")
            issues.setSubscribed(token, me, ref, subscribed = true).value("subscribe again")
            assertWithMessage("subscribed").that(issues.isSubscribed(token, me, ref).value("subscribed?")).isTrue()

            // Pointed at another branch, then back at main.
            send("projects/$id/repository/branches?branch=other&ref=main", buildJsonObject { })
            issues.setBase(token, ref, "other").value("to other")
            assertWithMessage("into other").that(issues.issue(token, ref).value("moved").pullRequest?.baseRef).isEqualTo("other")
            issues.setBase(token, ref, "main").value("back to main")
            // A label made from the picker: it is the repository's, and set on the pull request.
            val made = issues.createLabel(token, repo, "made-by-the-live-test", "#0e8a16").value("label made")
            assertWithMessage("label made").that(made.name).isEqualTo("made-by-the-live-test")
            assertWithMessage("label listed").that(issues.labels(token, repo).value("labels").map { it.name }).contains("made-by-the-live-test")
            issues.setLabels(token, ref, listOf("made-by-the-live-test")).value("label set")

            assertWithMessage("into main").that(issues.issue(token, ref).value("back").pullRequest?.baseRef).isEqualTo("main")

            pulls.review(token, ref, ReviewVerdict.COMMENT, "One remark.", listOf(LineComment("README.md", added.oldNumber, added.newNumber, "Why this line?"))).value("review")
            // The author approving their own: GitLab allows it unless the project forbids it.
            val approval = pulls.review(token, ref, ReviewVerdict.APPROVE, "", emptyList())
            System.err.println("GITLAB approve own merge request: ${if (approval is ForgeResult.Success) "accepted" else approval}")

            // The remark on a line is a thread: answered, resolved, and read back so.
            val thread = pulls.threads(token, ref).value("threads").single()
            assertWithMessage("thread on README").that(thread.path).isEqualTo("README.md")
            assertWithMessage("thread can be resolved").that(thread.canResolve).isTrue()
            pulls.reply(token, ref, thread, "Answered by the live test.").value("reply")
            pulls.setResolved(token, ref, thread, resolved = true).value("resolve")
            val after = pulls.threads(token, ref).value("threads after").single()
            System.err.println("GITLAB thread after: ${after.comments.size} comments, resolved=${after.resolved}")
            assertWithMessage("reply in thread").that(after.comments.map { it.body }).contains("Answered by the live test.")
            assertWithMessage("resolved").that(after.resolved).isTrue()
            // The reply is rewritten, then taken back; the remark it answered stays.
            val answer = after.comments.first { it.body == "Answered by the live test." }
            pulls.editThreadComment(token, ref, after, answer, "Rewritten by the live test.").value("edit reply")
            val rewritten = pulls.threads(token, ref).value("threads rewritten").single()
            assertWithMessage("reply rewritten").that(rewritten.comments.map { it.body }).contains("Rewritten by the live test.")
            pulls.deleteThreadComment(token, ref, rewritten, rewritten.comments.first { it.id == answer.id }).value("delete reply")
            assertWithMessage("reply taken back").that(pulls.threads(token, ref).value("threads less").single().comments.none { it.id == answer.id }).isTrue()
            // Left to its thread, not repeated in the conversation.
            val said = GitLabIssueApi(http, ForgeInstance.GitLab).timeline(token, ref, 1).value("timeline before merge").items
            assertWithMessage("not in the conversation").that(said.filterIsInstance<fr.arthurbrugiere.forgeline.core.model.TimelineItem.Comment>().map { it.body }).doesNotContain("Why this line?")

            // A remark from the line the change leaves above the one it adds to that one, and one on the whole file.
            val newSide = parsePatch(readme.patch!!).flatMap { it.lines }.filter { it.kind != DiffLineKind.REMOVED }
            val last = added
            val first = newSide[newSide.indexOf(added) - 1]
            pulls.review(
                token, ref, ReviewVerdict.COMMENT, "",
                listOf(
                    LineComment("README.md", last.oldNumber, last.newNumber, "These lines.", startOldLine = first.oldNumber, startNewLine = first.newNumber),
                    LineComment("README.md", null, null, "On the whole file.", onFile = true),
                ),
            ).value("range and file")
            val placed = pulls.threads(token, ref).value("threads placed")
            System.err.println("GITLAB range and file threads: ${placed.map { "${it.comments.first().body}@${it.startLine}-${it.line}" }}")
            val range = placed.single { it.comments.first().body == "These lines." }
            assertWithMessage("range ends").that(range.line).isEqualTo(last.newNumber)
            assertWithMessage("range starts").that(range.startLine).isEqualTo(first.newNumber)
            assertWithMessage("file remark").that(placed.map { it.comments.first().body }).contains("On the whole file.")

            // A suggestion on the same line, applied by GitLab itself; once applied it can't be again.
            pulls.review(token, ref, ReviewVerdict.COMMENT, "", listOf(LineComment("README.md", added.oldNumber, added.newNumber, "Better:\n```suggestion:-0+0\nA line the suggestion wrote.\n```"))).value("suggestion")
            val suggesting = pulls.threads(token, ref).value("threads with suggestion").single { it.comments.first().body.startsWith("Better:") }
            val kept = suggesting.comments.first().suggestions.single()
            System.err.println("GITLAB kept suggestion: $kept")
            assertWithMessage("replaces").that(kept.over).containsExactly("A line the merge request adds.")
            assertWithMessage("appliable").that(kept.appliable).isTrue()
            pulls.applySuggestion(token, ref, suggesting, listOf("A line the suggestion wrote."), "Apply suggestion from code review").value("apply suggestion")
            val readmeNow = http.get("$api/projects/$id/repository/files/README.md/raw?ref=change") { header("Authorization", "Bearer $token") }.bodyAsText()
            assertWithMessage("README after the suggestion").that(readmeNow).isEqualTo("# $name\n\nA line the suggestion wrote.\n")
            val again = pulls.applySuggestion(token, ref, suggesting, listOf("A line the suggestion wrote."), "Apply suggestion from code review")
            assertWithMessage("applied twice").that(again).isEqualTo(ForgeResult.Failure(fr.arthurbrugiere.forgeline.core.forge.ForgeError.Conflict))
            val appliedNow = pulls.threads(token, ref).value("threads after suggestion").single { it.id == suggesting.id }.comments.first().suggestions.single()
            assertWithMessage("applied").that(appliedNow.applied).isTrue()

            // main moves on: the merge request falls behind, and is rebased onto it.
            send(
                "projects/$id/repository/commits",
                buildJsonObject {
                    put("branch", "main")
                    put("commit_message", "Add notes on main")
                    put("actions", buildJsonArray { add(buildJsonObject { put("action", "create"); put("file_path", "NOTES.md"); put("content", "Notes.\n") }) })
                },
            )
            var behind = pulls.mergeInfo(token, ref).value("behind info")
            repeat(15) { if (!behind.behind) { delay(1_000); behind = pulls.mergeInfo(token, ref).value("behind info") } }
            System.err.println("GITLAB behind: ${behind.behind}")
            assertWithMessage("behind main").that(behind.behind).isTrue()
            pulls.updateBranch(token, ref).value("rebase")
            repeat(20) { if (behind.behind) { delay(1_000); behind = pulls.mergeInfo(token, ref).value("rebased info") } }
            System.err.println("GITLAB after rebase, behind: ${behind.behind}")
            assertWithMessage("up to date").that(behind.behind).isFalse()

            var info = pulls.mergeInfo(token, ref).value("merge info")
            repeat(15) { if (info.mergeable != true) { delay(1_000); info = pulls.mergeInfo(token, ref).value("merge info") } }
            System.err.println("GITLAB merge info: $info")
            assertWithMessage("can merge one's own project").that(info.canMerge).isTrue()
            assertWithMessage("mergeable").that(info.mergeable).isTrue()
            assertWithMessage("own branch can go").that(info.deleteBranch).isNotNull()
            pulls.merge(token, ref, MergeMethod.MERGE, deleteBranch = true).value("merge")
            // The branch went with it.
            var branch = http.get("$api/projects/${repo.owner}%2F${repo.name}/repository/branches/change") { header("Authorization", "Bearer $token") }.status
            repeat(10) { if (branch.isSuccess()) { delay(1_000); branch = http.get("$api/projects/${repo.owner}%2F${repo.name}/repository/branches/change") { header("Authorization", "Bearer $token") }.status } }
            System.err.println("GITLAB merged branch: $branch")
            assertWithMessage("branch deleted").that(branch.value).isEqualTo(404)

            // Offered back, made again where it stood, then no longer offered.
            val deleted = pulls.mergeInfo(token, ref).value("merged info").deletedBranch
            System.err.println("GITLAB deleted branch: $deleted")
            assertWithMessage("deleted branch").that(deleted?.name).isEqualTo("change")
            pulls.restoreBranch(token, ref, deleted!!).value("restore")
            val restored = http.get("$api/projects/${repo.owner}%2F${repo.name}/repository/branches/change") { header("Authorization", "Bearer $token") }.bodyAsText()
            assertWithMessage("restored where it stood").that(restored).contains(deleted.sha)
            assertWithMessage("no longer offered").that(pulls.mergeInfo(token, ref).value("restored info").deletedBranch).isNull()

            val merged = GitLabIssueApi(http, ForgeInstance.GitLab).issue(token, ref).value("merged conversation")
            assertWithMessage("state").that(merged.pullRequest?.isMerged).isTrue()
            // What was said went with it: the note and the remark on the line.
            val timeline = GitLabIssueApi(http, ForgeInstance.GitLab).timeline(token, ref, 1).value("timeline")
            System.err.println("GITLAB timeline holds ${timeline.items.size} entries")
            assertWithMessage("timeline").that(timeline.items.size).isAtLeast(2)
        } finally {
            val deleted = http.delete("$api/projects/$id") { header("Authorization", "Bearer $token") }
            System.err.println("GITLAB scratch project deleted: ${deleted.status}")
        }
    }

    /** In a scratch project of its own, created here and deleted at the end: each setting is changed, and read back as changed. */
    @Test
    fun a_project_s_settings_are_changed() = runBlocking<Unit> {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        assumeTrue("GITLAB_LIVE_WRITES not set", System.getenv("GITLAB_LIVE_WRITES") == "1")
        val project = send(
            "projects",
            buildJsonObject {
                put("name", "forgeline-scratch-settings-${System.currentTimeMillis()}")
                put("visibility", "private")
                put("initialize_with_readme", true)
                put("description", "Scratch project for Forgeline API tests. Safe to delete.")
            },
        ).body<JsonObject>()
        val id = project.getValue("id").jsonPrimitive.content
        val (owner, path) = project.getValue("path_with_namespace").jsonPrimitive.content.split('/', limit = 2)
        val repo = RepoId(owner, path, ForgeInstance.GitLab)
        val settings = GitLabRepoSettingsApi(http, ForgeInstance.GitLab)
        try {
            val read = settings.settings(token, repo).value("settings")
            System.err.println("GITLAB settings: $read")
            assertWithMessage("no website").that(read.hasWebsite).isFalse()
            assertWithMessage("switches").that(read.switches.keys).containsAtLeast(RepoSwitch.ISSUES, RepoSwitch.PULL_REQUESTS, RepoSwitch.WIKI, RepoSwitch.CI, RepoSwitch.SQUASH_MERGE)
            assertWithMessage("merge style").that(read.mergeStyle).isEqualTo(MergeStyle.MERGE_COMMIT)

            suspend fun change(change: RepoSettingsChange) = settings.change(token, repo, change).value("$change")
            assertThat(change(RepoSettingsChange.Description("Changed by a test")).description).isEqualTo("Changed by a test")
            assertThat(change(RepoSettingsChange.Topics(listOf("scratch", "forgeline"))).topics).containsExactly("scratch", "forgeline")
            assertThat(change(RepoSettingsChange.Switch(RepoSwitch.WIKI, false)).switches[RepoSwitch.WIKI]).isFalse()
            val wikiOn = change(RepoSettingsChange.Switch(RepoSwitch.WIKI, true))
            assertThat(wikiOn.switches[RepoSwitch.WIKI]).isTrue()
            assertThat(wikiOn.membersOnly).doesNotContain(RepoSwitch.WIKI)
            assertThat(change(RepoSettingsChange.Switch(RepoSwitch.SQUASH_MERGE, false)).switches[RepoSwitch.SQUASH_MERGE]).isFalse()
            assertThat(change(RepoSettingsChange.Switch(RepoSwitch.DELETE_BRANCH_ON_MERGE, false)).switches[RepoSwitch.DELETE_BRANCH_ON_MERGE]).isFalse()
            assertThat(change(RepoSettingsChange.Switch(RepoSwitch.REQUIRE_RESOLVED_THREADS, true)).switches[RepoSwitch.REQUIRE_RESOLVED_THREADS]).isTrue()
            assertThat(change(RepoSettingsChange.Style(MergeStyle.FAST_FORWARD)).mergeStyle).isEqualTo(MergeStyle.FAST_FORWARD)
            // Read again from scratch: it all held.
            val after = settings.settings(token, repo).value("settings after")
            assertThat(after.description).isEqualTo("Changed by a test")
            assertThat(after.mergeStyle).isEqualTo(MergeStyle.FAST_FORWARD)

            // Its website: none until its CI publishes one, and what is said of it can be set already.
            val site = settings.pages(token, repo).value("pages")
            System.err.println("GITLAB pages: $site")
            assertWithMessage("pages").that(site).isNotNull()
            assertWithMessage("published").that(site!!.published).isFalse()
            val https = settings.changePages(token, repo, fr.arthurbrugiere.forgeline.core.model.PagesChange.Https(false))
            System.err.println("GITLAB pages https off: $https")
            assertThat((https as ForgeResult.Success).value.httpsEnforced).isFalse()

            // What can't be taken back lightly, on this project alone: archived and back, renamed, then deleted.
            assertThat(change(RepoSettingsChange.Archived(true)).archived).isTrue()
            assertThat(change(RepoSettingsChange.Archived(false)).archived).isFalse()
            assertThat(after.visibility).isEqualTo(fr.arthurbrugiere.forgeline.core.model.RepoVisibility.PRIVATE)
            val renamed = settings.rename(token, repo, "$path-renamed").value("rename")
            assertThat(renamed).isEqualTo(RepoId(owner, "$path-renamed", ForgeInstance.GitLab))
            assertThat(settings.settings(token, renamed).value("settings once renamed").description).isEqualTo("Changed by a test")
            val deletion = settings.delete(token, renamed).value("delete")
            System.err.println("GITLAB deletion: $deletion")
            // gitlab.com keeps a deleted project a while before it is gone.
            assertThat(deletion).isEqualTo(fr.arthurbrugiere.forgeline.core.model.RepoDeletion.SCHEDULED)
        } finally {
            val deleted = http.delete("$api/projects/$id") { header("Authorization", "Bearer $token") }
            System.err.println("GITLAB scratch project deleted: ${deleted.status}")
        }
    }
}
