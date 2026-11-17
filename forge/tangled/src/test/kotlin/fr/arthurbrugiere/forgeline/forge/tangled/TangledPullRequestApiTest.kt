package fr.arthurbrugiere.forgeline.forge.tangled

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/**
 * Tangled's pull requests: their latest patch, read from the index; their change, diffed by the knot and written out
 * from the files' text; their pipelines, run by the repository's spindle. The answers are Tangled's own, captured
 * 2026-11-17 from tangled.org/core's pull request #4784 (`src/test/resources/tangled`), cut down to two of its files.
 */
class TangledPullRequestApiTest {
    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    private val core = RepoId("tangled.org", "core", ForgeInstance.Tangled)
    private val pull = IssueRef(core, 4784, isPullRequest = true)

    /** The text of each side of the two files the diff names, as the index serves them: line N reads "line N". */
    private val blobs: (HttpRequestData) -> String? = { r ->
        val head = r.url.parameters["ref"] == HEAD
        when (r.url.parameters["path"]) {
            "web/src/lib/api/descriptors.ts" -> (0..93).joinToString("\n") { "line $it" } + if (head) (94..98).joinToString("") { "\nadded $it" } else ""
            "web/src/lib/markup/sanitize.ts" -> (0..110).joinToString("\n") { if (head && (it == 79 || it == 98)) "changed $it" else "line $it" }
            else -> null
        }
    }

    private fun fake(extra: Map<String, (HttpRequestData) -> String?> = emptyMap()) = FakeTangled(
        mapOf(
            "com.atproto.identity.resolveHandle" to resolved,
            "org.tangled.temp.repo.getRepoByName" to coreManifest,
            "org.tangled.temp.review.listPatches" to { fixture("listPatches.json") },
            "org.tangled.git.getDiff" to { fixture("getDiff.json") },
            "org.tangled.temp.git.getBlob" to blobs,
            "org.tangled.temp.git.listCommits" to { fixture("listCommits_range.json") },
            "org.tangled.ci.queryPipelines" to { fixture("queryPipelines.json") },
        ) + extra,
    )

    private fun api(fake: FakeTangled): TangledPullRequestApi {
        val identity = TangledIdentity(fake.client, fake.xrpc)
        val account = TangledAccount(fake.xrpc, identity)
        val repos = TangledRepoApi(fake.xrpc, identity)
        val knot = TangledKnot(fake.xrpc, account, identity)
        return TangledPullRequestApi(fake.xrpc, knot, repos.conversations, TangledIssueApi(fake.xrpc, account, knot, repos.conversations))
    }

    @Test
    fun a_pull_request_s_files_are_its_latest_patch_diffed_by_the_knot() = runTest {
        val fake = fake()

        val files = api(fake).files(null, pull, 1).value()

        assertThat(fake.asked).contains("org.tangled.temp.review.listPatches?limit=500&order=asc&ticket=at://$REPO_DID/org.tangled.track.ticket/4784")
        val diff = fake.to("org.tangled.git.getDiff").single()
        assertThat(diff.url.host).isEqualTo("knot1.tangled.sh")
        assertThat(diff.url.parameters["base"]).isEqualTo(BASE)
        assertThat(diff.url.parameters["head"]).isEqualTo(HEAD)
        assertThat(files.nextPage).isNull()
        val (added, changed) = files.files
        assertThat(added.path).isEqualTo("web/src/lib/api/descriptors.ts")
        assertThat(added.change).isEqualTo(FileChange.MODIFIED)
        assertThat(added.additions).isEqualTo(5)
        assertThat(added.deletions).isEqualTo(0)
        assertThat(added.patch).isEqualTo(
            """
            @@ -92,3 +92,8 @@
             line 91
             line 92
             line 93
            +added 94
            +added 95
            +added 96
            +added 97
            +added 98
            """.trimIndent(),
        )
        assertThat(changed.additions).isEqualTo(2)
        assertThat(changed.deletions).isEqualTo(2)
        assertThat(changed.patch).isEqualTo(
            """
            @@ -77,7 +77,7 @@
             line 76
             line 77
             line 78
            -line 79
            +changed 79
             line 80
             line 81
             line 82
            @@ -96,7 +96,7 @@
             line 95
             line 96
             line 97
            -line 98
            +changed 98
             line 99
             line 100
             line 101
            """.trimIndent(),
        )
        // Each side is read where it is: the base in the repository, the head where the patch came from.
        assertThat(fake.to("org.tangled.temp.git.getBlob").map { it.url.parameters["ref"] }.toSet()).containsExactly(BASE, HEAD)
    }

    @Test
    fun a_file_too_big_or_binary_is_listed_without_its_patch() = runTest {
        val binary = fixture("getDiff.json").replaceFirst(""""isBinary":false""", """"isBinary":true""")
        val fake = fake(mapOf("org.tangled.git.getDiff" to { binary }))

        val files = api(fake).files(null, pull, 1).value().files

        assertThat(files.first().patch).isNull()
        assertThat(files.first().additions).isEqualTo(5)
    }

    @Test
    fun a_pull_request_s_commits_are_those_of_its_patch_oldest_first() = runTest {
        val fake = fake()

        val commits = api(fake).commits(null, pull).value()

        assertThat(commits.map { it.sha }).containsExactly(HEAD)
        assertThat(commits.single().message).isEqualTo("web: render ticket references as inline chips and embed cards\n\nSigned-off-by: eti <eti@eti.tf>")
        assertThat(commits.single().authorName).isEqualTo("eti")
        assertThat(commits.single().date).isEqualTo(Instant.parse("2026-11-16T12:20:09Z"))
        assertThat(fake.to("org.tangled.temp.git.listCommits").single().url.parameters.getAll("ranges")).containsExactly("$BASE..$HEAD")
    }

    @Test
    fun a_pull_request_s_checks_are_the_workflows_its_spindle_ran_on_its_head() = runTest {
        val fake = fake()

        val checks = api(fake).checks(null, pull).value()

        val asked = fake.to("org.tangled.ci.queryPipelines").single()
        assertThat(asked.url.host).isEqualTo("spindle.tangled.network")
        assertThat(asked.url.parameters.getAll("commits")).containsExactly(HEAD)
        assertThat(checks.map { it.name }).containsAtLeast("brand-license.yml", "commit-lint.yml")
        assertThat(checks.map { it.state }.toSet()).containsExactly(CheckState.SUCCESS)
        assertThat(checks.first().url).isEqualTo("https://tangled.org/tangled.org/core/pipelines/3n2hgxw2vnp22/workflow/brand-license.yml")
    }

    @Test
    fun a_repository_without_a_spindle_has_no_checks() = runTest {
        val fake = fake(mapOf("org.tangled.temp.repo.getRepoByName" to coreRepo))

        assertThat(api(fake).checks(null, pull).value()).isEmpty()
        assertThat(fake.to("org.tangled.ci.queryPipelines")).isEmpty()
    }

    @Test
    fun merging_and_verdicts_stay_in_the_browser() = runTest {
        val fake = fake()
        val api = api(fake)

        assertThat(api.mergeInfo("token", pull).value().canMerge).isFalse()
        assertThat(api.merge("token", pull, MergeMethod.MERGE, deleteBranch = false, whenChecksPass = false)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.review("token", pull, ReviewVerdict.APPROVE, "LGTM", emptyList())).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.verdicts).containsExactly(ReviewVerdict.COMMENT)
    }

    @Test
    fun a_repository_s_history_is_paged_by_the_knot_s_cursor() = runTest {
        val fake = fake(mapOf("org.tangled.git.listCommits" to { r -> if (r.url.parameters["cursor"] == null) fixture("knot_listCommits.json") else """{"commits":[]}""" }))
        val api = api(fake)

        val first = api.history(null, core, null, "web", 1).value()
        val second = api.history(null, core, null, "web", 2).value()

        assertThat(first.commits.map { it.sha }).containsExactly("ba47c228245f45e19457e3b5042c92626539d43d", "7994275868432cf880382ac8ab333ddceca4147e").inOrder()
        assertThat(first.commits.first().message).startsWith("web/repo: center the unmodified lines text")
        assertThat(first.nextPage).isEqualTo(2)
        assertThat(second.commits).isEmpty()
        val asked = fake.to("org.tangled.git.listCommits")
        assertThat(asked.map { it.url.host }.toSet()).containsExactly("knot1.tangled.sh")
        assertThat(asked.first().url.parameters["ref"]).isEqualTo("master")
        assertThat(asked.first().url.parameters["path"]).isEqualTo("web")
        assertThat(asked.last().url.parameters["cursor"]).isEqualTo("7994275868432cf880382ac8ab333ddceca4147e")
    }

    @Test
    fun a_file_s_blame_names_the_commit_of_each_run_of_lines() = runTest {
        val fake = fake(mapOf("org.tangled.git.getBlame" to { fixture("getBlame.json") }))

        val blame = api(fake).blame(null, core, "master", "go.mod").value()

        assertThat(blame.map { it.startLine to it.endLine }).containsExactly(1 to 1, 2 to 2, 3 to 3, 4 to 5).inOrder()
        assertThat(blame.first().commit.sha).isEqualTo("54a60448cf5c456650e9954ca9422276c5d73282")
        assertThat(fake.to("org.tangled.git.getBlame").single().url.host).isEqualTo("knot1.tangled.sh")
    }

    @Test
    fun a_commit_is_diffed_against_its_parent() = runTest {
        val fake = fake()

        val commit = api(fake).commit(null, core, HEAD).value()

        assertThat(commit.commit.sha).isEqualTo(HEAD)
        assertThat(commit.files.map { it.path }).containsExactly("web/src/lib/api/descriptors.ts", "web/src/lib/markup/sanitize.ts").inOrder()
        assertThat(fake.to("org.tangled.git.getDiff").single().url.parameters["base"]).isEqualTo(BASE)
    }

    private companion object {
        const val BASE = "03c0553cc4fd89eef6addb0242d16240eec55850"
        const val HEAD = "8639c9e00269667dc3387368ca23f41d74682611"
    }
}
