package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertWithMessage
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.JobLogSteps
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Forgeline's GitLab client against the real gitlab.com, signed in. It only ever reads, and is skipped without
 * GITLAB_LIVE_TOKEN. What it prints names no private data beyond counts.
 */
class GitLabSignedInLiveTest {
    private val token = System.getenv("GITLAB_LIVE_TOKEN").orEmpty()
    private val http = gitlabHttpClient(OkHttp.create())

    private fun <T> ForgeResult<T>.value(): T = (this as? ForgeResult.Success)?.value ?: error("$this")

    @Test
    fun the_signed_in_person_s_work_reads() = runBlocking {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val me = GitLabAuthApi(http).fetchAuthenticatedUser(token).value()
        val search = GitLabSearchApi(http)

        for (kind in WorkKind.entries) {
            val started = System.currentTimeMillis()
            val found = search.work(token, me.login, kind)
            assertWithMessage("$kind: ${(found as? ForgeResult.Failure)?.error}").that(found).isInstanceOf(ForgeResult.Success::class.java)
            System.err.println("GITLAB work $kind: ${found.value().size} in ${System.currentTimeMillis() - started} ms")
            // Whatever shows is open, on gitlab.com, in a project its address named.
            assertWithMessage("$kind open").that(found.value().all { it.issue.state == IssueState.OPEN }).isTrue()
            assertWithMessage("$kind forge").that(found.value().all { it.repo.forge == ForgeInstance.GitLab && it.repo.name.isNotBlank() }).isTrue()
            if (kind != WorkKind.ASSIGNED) assertWithMessage("$kind merge requests").that(found.value().all { it.issue.isPullRequest }).isTrue()
        }
    }

    /** fdroid/wiki: gitlab.com answers a wiki's pages to someone signed in, and its home in Markdown. */
    @Test
    fun a_wiki_lists_its_pages_and_reads_its_home() = runBlocking {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val fdroid = RepoId("fdroid", "wiki", ForgeInstance.GitLab)
        val api = GitLabRepoApi(http)

        val pages = api.wikiPages(token, fdroid).value()
        assertWithMessage("pages").that(pages.size).isGreaterThan(10)
        assertWithMessage("a page in a folder").that(pages.any { '/' in it.key }).isTrue()
        val home = api.wikiPage(token, fdroid, api.wikiHome).value()
        assertWithMessage("home").that(home.body).contains("F-Droid")
        val nested = api.wikiPage(token, fdroid, pages.first { '/' in it.key }.key).value()
        assertWithMessage("nested page").that(nested.body).isNotEmpty()
    }

    /** fdroid/fdroidserver#1355 names !1848 as what closed it (2026-10-25). */
    @Test
    fun the_merge_requests_that_close_an_issue_are_still_told() = runBlocking {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val issue = fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("fdroid", "fdroidserver", ForgeInstance.GitLab), 1355, isPullRequest = false)

        val closers = GitLabIssueApi(http).closingPulls(token, issue).value()

        assertWithMessage("!1848").that(closers.map { it.ref.number to it.ref.repo.fullName }).contains(1848 to "fdroid/fdroidserver")
    }

    /** fdroid/fdroidserver#1355 was closed by 38c3cbc on 2026-07-22, which gitlab.com tells twice. */
    @Test
    fun an_issue_closed_by_a_commit_says_which_once() = runBlocking {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val issue = fr.arthurbrugiere.forgeline.core.model.IssueRef(RepoId("fdroid", "fdroidserver", ForgeInstance.GitLab), 1355, isPullRequest = false)

        val changes = GitLabIssueApi(http).timeline(token, issue, page = 1).value().items.filterIsInstance<fr.arthurbrugiere.forgeline.core.model.TimelineItem.StateChanged>()

        assertWithMessage("closes").that(changes.count { it.change == fr.arthurbrugiere.forgeline.core.model.StateChange.CLOSED }).isEqualTo(1)
        val commit = changes.firstNotNullOf { it.commit }
        assertWithMessage("sha").that(commit.sha).startsWith("38c3cbc")
        assertWithMessage("message").that(commit.message).startsWith("Don't ignore errors in sign_jar with use_old_algos")
    }

    /**
     * fdroid/fdroidclient: a finished job's trace reads into steps named as GitLab names them, whether or not its runner
     * stamps each line (gitlab.com's did on 2026-10-23, and wrote each header on the line after its section's marker).
     */
    @Test
    fun a_real_job_s_trace_reads_into_its_steps() = runBlocking {
        assumeTrue("GITLAB_LIVE_TOKEN not set", token.isNotBlank())
        val fdroid = RepoId("fdroid", "fdroidclient", ForgeInstance.GitLab)
        val actions = GitLabActionsApi(http)
        val run = GitLabRepoApi(http).workflowRuns(token, fdroid).value().first { it.conclusion == RunConclusion.SUCCESS }
        val job = actions.jobs(token, fdroid, run.id).value().first { it.conclusion == RunConclusion.SUCCESS }

        val log = ByteArrayOutputStream().also { actions.downloadJobLog(token, fdroid, job.id, it).value() }.toByteArray()
        val steps = JobLogSteps.index(log.inputStream(), LogFormat.GITLAB)

        System.err.println("GITLAB job ${job.id}: ${steps.map { it.title }}")
        assertWithMessage("steps").that(steps.map { it.title }).contains("Getting source from Git repository")
        assertWithMessage("lines").that(steps.sumOf { it.lines ?: 0 }).isEqualTo(String(log).count { it == '\n' } + if (log.last() == '\n'.code.toByte()) 0 else 1)
    }
}
