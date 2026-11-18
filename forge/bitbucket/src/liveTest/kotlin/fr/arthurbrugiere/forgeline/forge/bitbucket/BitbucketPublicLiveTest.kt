package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Forgeline's Bitbucket client against the real bitbucket.org, signed out, on Atlassian's public
 * atlassian-connect-express: it only reads. It pins what docs/BITBUCKET.md relies on, and what the fixtures of the
 * unit tests were recorded from.
 */
class BitbucketPublicLiveTest {
    private val http = bitbucketHttpClient(OkHttp.create())
    private val apis = BitbucketApis(http)
    private val ace = RepoId("atlassian", "atlassian-connect-express", ForgeInstance.Bitbucket)

    private fun <T> ForgeResult<T>.value(what: String = ""): T = (this as? ForgeResult.Success)?.value ?: error("$what: $this")

    @Test
    fun a_repository_reads_with_its_counts_readme_files_and_tags() = runBlocking<Unit> {
        val details = apis.repos.repo(null, ace).value("repo")
        assertWithMessage("main branch").that(details.defaultBranch).isEqualTo("master")
        assertWithMessage("watchers").that(details.watchers).isGreaterThan(0)
        assertWithMessage("issues").that(details.hasIssues).isFalse()

        val readme = apis.repos.readme(null, ace).value("readme")
        assertWithMessage("readme").that(readme?.markdown.orEmpty()).isNotEmpty()

        val root = apis.repos.contents(null, ace, "", details.defaultBranch).value("root")
        assertWithMessage("folders first").that(root.first().type).isEqualTo(RepoFileType.DIR)
        assertWithMessage("package.json").that(root.map { it.path }).contains("package.json")
        val lib = apis.repos.contents(null, ace, "lib", details.defaultBranch).value("lib")
        assertWithMessage("lib's files").that(lib.all { it.path.startsWith("lib/") }).isTrue()

        val text = apis.repos.fileText(null, ace, "package.json", details.defaultBranch).value("file")
        assertWithMessage("package.json").that(text).contains("atlassian-connect-express")

        val refs = apis.repos.refs(null, ace).value("refs")
        assertWithMessage("branches").that(refs.branches).contains("master")
        assertWithMessage("tags").that(refs.tags).isNotEmpty()
        val releases = apis.repos.releases(null, ace).value("releases")
        assertWithMessage("releases").that(releases).isNotEmpty()
        apis.repos.release(null, ace, releases.first().tag).value("release")
    }

    @Test
    fun a_pull_request_reads_with_its_conversation_files_commits_and_checks() = runBlocking<Unit> {
        val closed = apis.repos.pullRequests(null, ace, IssueQuery(open = false)).value("pulls")
        assertWithMessage("closed pull requests").that(closed).isNotEmpty()
        val ref = IssueRef(ace, closed.first().number, isPullRequest = true)

        val details = apis.issues.issue(null, ref).value("pull")
        assertWithMessage("base").that(details.pullRequest?.baseRef).isNotEmpty()
        val timeline = apis.issues.timeline(null, ref, 1).value("timeline")
        assertWithMessage("timeline").that(timeline.items).isNotEmpty()

        val files = apis.pulls.files(null, ref).value("files")
        assertWithMessage("files").that(files.files).isNotEmpty()
        assertWithMessage("patches").that(files.files.any { it.patch != null }).isTrue()
        assertWithMessage("commits").that(apis.pulls.commits(null, ref).value("commits")).isNotEmpty()
        apis.pulls.checks(null, ref).value("checks")
        apis.pulls.commitChecks(null, ref).value("commit checks")
        apis.pulls.threads(null, ref).value("threads")
    }

    @Test
    fun history_and_a_commit_read() = runBlocking<Unit> {
        val page = apis.pulls.history(null, ace, null, "package.json").value("history")
        assertWithMessage("history").that(page.commits).isNotEmpty()
        assertWithMessage("next page").that(page.nextPage).isEqualTo(2)
        val commit = apis.pulls.commit(null, ace, page.commits.first().sha).value("commit")
        assertWithMessage("files").that(commit.files.map { it.path }).contains("package.json")
    }

    @Test
    fun pipelines_read_with_their_steps_and_logs() = runBlocking<Unit> {
        val runs = apis.repos.workflowRuns(null, ace).value("runs")
        assertWithMessage("runs").that(runs).isNotEmpty()
        val done = runs.first { it.status == RunStatus.COMPLETED }
        apis.actions.run(null, ace, done.id).value("run")
        val jobs = apis.actions.jobs(null, ace, done.id).value("jobs")
        assertWithMessage("steps").that(jobs).isNotEmpty()
        val job = apis.actions.job(null, ace, done.id, jobs.first().id).value("job")
        assertWithMessage("step").that(job.name).isEqualTo(jobs.first().name)
        // A step that ran has a log; Bitbucket keeps none for those it skipped.
        val ran = jobs.firstOrNull { it.startedAt != null } ?: return@runBlocking
        val log = ByteArrayOutputStream()
        val read = apis.actions.downloadJobLog(null, ace, ran.id, log)
        if (read is ForgeResult.Success) assertWithMessage("log").that(log.size()).isGreaterThan(0)
        apis.actions.workflows(null, ace).value("workflows")
    }

    @Test
    fun a_workspace_reads_with_its_repositories() = runBlocking<Unit> {
        val profile = apis.users.user(null, "atlassian").value("workspace")
        assertWithMessage("a team").that(profile.isOrganization).isTrue()
        val repos = apis.users.repos(null, "atlassian").value("repos")
        assertWithMessage("repos").that(repos.map { it.id }).contains(ace)
    }
}
