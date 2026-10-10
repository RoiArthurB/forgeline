package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.NewPullRequest
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
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

            val ref = pulls.create(token, repo, NewPullRequest("Say more in the README", "Opened by Forgeline's live test.", head = "change", base = "main")).value("create")
            assertWithMessage("opened as a merge request").that(ref.isPullRequest).isTrue()

            // GitLab works a new merge request's change out after answering: asked again until it is there.
            var files = pulls.files(token, ref).value("files").files
            repeat(10) { if (files.isEmpty()) { delay(1_000); files = pulls.files(token, ref).value("files").files } }
            val readme = files.single { it.path == "README.md" }
            val added = parsePatch(readme.patch!!).flatMap { it.lines }.first { it.kind == DiffLineKind.ADDED }
            assertWithMessage("commits").that(pulls.commits(token, ref).value("commits")).hasSize(1)
            assertWithMessage("checks without CI").that(pulls.checks(token, ref).value("checks")).isEmpty()

            pulls.review(token, ref, ReviewVerdict.COMMENT, "One remark.", listOf(LineComment("README.md", added.oldNumber, added.newNumber, "Why this line?"))).value("review")
            // The author approving their own: GitLab allows it unless the project forbids it.
            val approval = pulls.review(token, ref, ReviewVerdict.APPROVE, "", emptyList())
            System.err.println("GITLAB approve own merge request: ${if (approval is ForgeResult.Success) "accepted" else approval}")

            var info = pulls.mergeInfo(token, ref).value("merge info")
            repeat(15) { if (info.mergeable != true) { delay(1_000); info = pulls.mergeInfo(token, ref).value("merge info") } }
            System.err.println("GITLAB merge info: $info")
            assertWithMessage("can merge one's own project").that(info.canMerge).isTrue()
            assertWithMessage("mergeable").that(info.mergeable).isTrue()
            pulls.merge(token, ref, MergeMethod.MERGE).value("merge")

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
}
