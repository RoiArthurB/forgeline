package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.FeedDetail
import fr.arthurbrugiere.forgeline.core.forge.FeedDetailKey
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ConversationPreview
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReleasePreview
import fr.arthurbrugiere.forgeline.core.model.ReleaseRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoPreview
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant

/** The answer mirrors GitHub's to the same query on 2026-10-16, a missing repository included. */
class GitHubFeedDetailsTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    private val zed = FeedDetailKey.Release(ReleaseRef(RepoId("zed-industries", "zed"), "v1.25.0-pre"))
    private val pull = FeedDetailKey.Conversation(IssueRef(RepoId("astral-sh", "uv"), 22500, isPullRequest = true))
    private val issue = FeedDetailKey.Conversation(IssueRef(RepoId("astral-sh", "uv"), 1))
    private val gone = FeedDetailKey.Repo(RepoId("does-not", "exist"))
    private val ghostty = FeedDetailKey.Repo(RepoId("ghostty-org", "ghostty"))

    private val answer = """
        {"data":{
          "r0":{"owner":{"avatarUrl":"https://avatars.githubusercontent.com/u/79345384?v=4"},"usesCustomOpenGraphImage":false,
            "openGraphImageUrl":"https://opengraph.githubassets.com/1/zed-industries/zed",
            "releases":{"nodes":[{"tagName":"v1.25.0-pre","isPrerelease":true,"isDraft":false},{"tagName":"v1.24.3-pre","isPrerelease":true,"isDraft":false},
              {"tagName":"v1.24.2","isPrerelease":false,"isDraft":false}]},
            "release":{"description":"This week's release includes automatic unfolding of folded code.\n\n## Features\n\n![Unfolding](https://zed.dev/img/unfold.png)",
              "reactionGroups":[{"content":"THUMBS_UP","reactors":{"totalCount":7}},{"content":"THUMBS_DOWN","reactors":{"totalCount":0}},{"content":"HOORAY","reactors":{"totalCount":8}}]}},
          "r1":{"issueOrPullRequest":{"__typename":"PullRequest","title":"Ignore `Requires-Python` upper bounds in `uv pip check`","comments":{"totalCount":1},
            "labels":{"nodes":[{"name":"compatibility","color":"C2E0C6"}]},"additions":56,"deletions":12,"changedFiles":3}},
          "r2":{"issueOrPullRequest":{"__typename":"Issue","title":"Add basic GitHub Actions CI","comments":{"totalCount":0},"labels":{"nodes":[]}}},
          "r3":null,
          "r4":{"description":"👻 Ghostty is a fast, feature-rich, and cross-platform terminal emulator.","primaryLanguage":{"name":"Zig"},"stargazerCount":62274,"pushedAt":"2026-10-16T05:02:11Z"}
        },
        "errors":[{"type":"NOT_FOUND","path":["r3"],"message":"Could not resolve to a Repository with the name 'does-not/exist'."}]}
    """.trimIndent()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = answer) = GitHubFeedApi(
        gitHubHttpClient(MockEngine { requests += it; respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }),
    )

    @Test
    fun one_request_answers_every_kind_of_row() = runTest {
        val result = api().details("tok", listOf(zed, pull, issue, gone, ghostty))

        assertThat(requests).hasSize(1)
        val details = (result as ForgeResult.Success).value
        assertThat(details[zed]).isEqualTo(
            FeedDetail.Release(
                ReleasePreview(
                    summary = "This week's release includes automatic unfolding of folded code.",
                    // The pre-release in between says nothing of the step: the last stable one does.
                    previousTag = "v1.24.2",
                    // Zed has no image of its own, only GitHub's generated card: the notes' picture is used instead.
                    imageUrl = "https://zed.dev/img/unfold.png",
                    logoUrl = "https://avatars.githubusercontent.com/u/79345384?v=4",
                    reactions = mapOf(Reaction.THUMBS_UP to 7, Reaction.HOORAY to 8),
                ),
            ),
        )
        assertThat(details[pull]).isEqualTo(
            FeedDetail.Conversation(
                ConversationPreview("Ignore `Requires-Python` upper bounds in `uv pip check`", listOf(Label("compatibility", "C2E0C6")), 1, 56, 12, 3),
            ),
        )
        assertThat(details[issue]).isEqualTo(FeedDetail.Conversation(ConversationPreview("Add basic GitHub Actions CI", emptyList(), 0)))
        assertThat(details[ghostty]).isEqualTo(
            FeedDetail.Repo(RepoPreview("👻 Ghostty is a fast, feature-rich, and cross-platform terminal emulator.", "Zig", 62274, Instant.parse("2026-10-16T05:02:11Z"))),
        )
        // A repository that's gone is left out, without failing the others.
        assertThat(details).doesNotContainKey(gone)
    }

    @Test
    fun a_project_s_own_image_comes_before_the_notes_picture() = runTest {
        val custom = answer.replace("\"usesCustomOpenGraphImage\":false", "\"usesCustomOpenGraphImage\":true")
        val details = (api(body = custom).details("tok", listOf(zed)) as ForgeResult.Success).value

        assertThat((details[zed] as FeedDetail.Release).preview.imageUrl).isEqualTo("https://opengraph.githubassets.com/1/zed-industries/zed")
    }

    @Test
    fun what_each_row_asks_travels_as_variables_never_inside_the_query() = runTest {
        val quoted = FeedDetailKey.Release(ReleaseRef(RepoId("acme", "rocket"), "v1\") { evil }"))
        api().details("tok", listOf(quoted, pull))

        val sent = Json.parseToJsonElement((requests.single().body as TextContent).text).jsonObject
        assertThat(sent["query"]!!.jsonPrimitive.content).doesNotContain("evil")
        val variables = sent["variables"]!!.jsonObject
        assertThat(variables["t0"]!!.jsonPrimitive.content).isEqualTo("v1\") { evil }")
        assertThat(variables["c1"]!!.jsonPrimitive.int).isEqualTo(22500)
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer tok")
    }

    @Test
    fun many_rows_go_in_several_requests_sent_together() = runTest {
        val keys = (1..60).map { FeedDetailKey.Repo(RepoId("acme", "repo$it")) }
        api(body = """{"data":{}}""").details("tok", keys)

        assertThat(requests).hasSize(3)
    }

    @Test
    fun a_refusal_is_reported() = runTest {
        val result = api(HttpStatusCode.Unauthorized, """{"message":"Bad credentials"}""").details("tok", listOf(pull))

        assertThat((result as ForgeResult.Failure).error).isEqualTo(ForgeError.Unauthorized)
    }
}
