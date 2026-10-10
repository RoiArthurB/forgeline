package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Forgeline's GitLab client against the real gitlab.com, on a public project: it only reads. Signed out where
 * gitlab.com answers the API; a history and a blame are asked with GITLAB_LIVE_TOKEN, since gitlab.com met those
 * with a browser check when asked by nobody (seen 2026-10-10: a 403 "Just a moment..." page).
 */
class GitLabPublicLiveTest {
    private val http = gitlabHttpClient(OkHttp.create())
    private val token = System.getenv("GITLAB_LIVE_TOKEN").orEmpty()
    private val runner = RepoId("gitlab-org", "gitlab-runner", ForgeInstance.GitLab)

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun a_merge_request_s_files_commits_and_checks_read_signed_out() = runBlocking<Unit> {
        val pulls = GitLabPullRequestApi(http)
        val open = GitLabRepoApi(http).pullRequests(null, runner, IssueQuery()).value()
        val ref = IssueRef(runner, open.first().number, isPullRequest = true)

        val files = pulls.files(null, ref).value()
        assertWithMessage("files of !${ref.number}").that(files.files).isNotEmpty()
        val patched = files.files.firstOrNull { it.patch != null }
        if (patched != null) assertWithMessage("hunks of ${patched.path}").that(parsePatch(patched.patch!!)).isNotEmpty()
        assertWithMessage("commits").that(pulls.commits(null, ref).value()).isNotEmpty()
        // Signed out the jobs can't be listed: the pipeline stands for them, or nothing ran.
        val checks = pulls.checks(null, ref).value()
        System.err.println("GITLAB !${ref.number}: ${files.files.size} files, ${checks.size} checks")
    }

    @Test
    fun a_history_a_commit_and_a_blame_read_signed_in() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val pulls = GitLabPullRequestApi(http)
        val history = pulls.history(token, runner, ref = "main", path = "go.mod").value()
        assertWithMessage("history of go.mod").that(history.commits).isNotEmpty()
        val commit = pulls.commit(token, runner, history.commits.first().sha).value()
        assertWithMessage("files of ${commit.commit.shortSha}").that(commit.files.map { it.path }).contains("go.mod")

        val blame = pulls.blame(token, runner, "main", "go.mod").value()
        assertWithMessage("blame").that(blame).isNotEmpty()
        assertWithMessage("blame starts at the first line").that(blame.first().startLine).isEqualTo(1)
        // The runs follow each other without a gap.
        assertWithMessage("blame is continuous").that(blame.zipWithNext().all { (a, b) -> b.startLine == a.endLine + 1 }).isTrue()
        System.err.println("GITLAB go.mod: history of ${history.commits.size}, blame in ${blame.size} runs")
    }
}
