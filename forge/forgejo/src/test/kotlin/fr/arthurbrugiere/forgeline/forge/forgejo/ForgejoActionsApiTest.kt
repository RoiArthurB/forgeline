package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.Workflow
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/** Against real codeberg.org answers for forgejo/website, captured on 2026-09-30 (payloads and repositories trimmed). */
class ForgejoActionsApiTest {
    private val codeberg = Codeberg()
    private val website = RepoId("forgejo", "website", ForgeInstance.Codeberg)

    private fun api(route: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ForgejoActionsApi(codeberg.client { route(it) }, ForgeInstance.Codeberg)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun reads_a_run_with_its_page_on_the_web() = runTest {
        val run = with(codeberg) { api { json(fixture("actions_run.json")) } }.run(null, website, 7368141).value()

        assertThat(run.id).isEqualTo(7368141)
        assertThat(run.runNumber).isEqualTo(4235)
        assertThat(run.workflowName).isEqualTo("push.yml")
        assertThat(run.title).isEqualTo("publish")
        assertThat(run.status).isEqualTo(RunStatus.COMPLETED)
        assertThat(run.conclusion).isEqualTo(RunConclusion.SUCCESS)
        assertThat(run.branch).isEqualTo("main")
        assertThat(run.event).isEqualTo("push")
        assertThat(run.actor?.login).isEqualTo("forgejo-website")
        assertThat(run.createdAt).isEqualTo(Instant.parse("2026-09-29T22:12:14Z"))
        assertThat(run.startedAt).isEqualTo(Instant.parse("2026-09-29T22:12:29Z"))
        // The web page counts runs per repository: the number, not the API id.
        assertThat(run.webUrl).isEqualTo("https://codeberg.org/forgejo/website/actions/runs/4235")
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/website/actions/runs/7368141")
    }

    @Test
    fun the_run_list_always_sends_a_page_so_codeberg_honours_the_limit() = runTest {
        // Regression: without `page`, Codeberg ignored `limit` and answered all 4,235 runs (56 MB).
        val runs = with(codeberg) { api { json(fixture("actions_runs.json")) } }.runs(null, website).value()

        val request = codeberg.requests.single().url
        assertThat(request.parameters["page"]).isEqualTo("1")
        assertThat(request.parameters["limit"]).isEqualTo("20")
        assertThat(runs.map { it.conclusion }).containsExactly(
            RunConclusion.SUCCESS, RunConclusion.CANCELLED, RunConclusion.SUCCESS, RunConclusion.SUCCESS, RunConclusion.SUCCESS,
        ).inOrder()
        // A pull request's run is on "#1121", not on a branch.
        assertThat(runs[2].branch).isNull()
        assertThat(runs[2].event).isEqualTo("pull_request")
    }

    @Test
    fun the_repository_api_lists_runs_the_same_way() = runTest {
        val repos = ForgejoRepoApi(codeberg.client { with(codeberg) { json(fixture("actions_runs.json")) } }, ForgeInstance.Codeberg)

        assertThat(repos.workflowRuns(null, website).value()).hasSize(5)
        assertThat(codeberg.requests.single().url.parameters["page"]).isEqualTo("1")
    }

    @Test
    fun jobs_come_without_steps_and_a_job_is_read_from_its_run() = runTest {
        val api = with(codeberg) { api { json(fixture("actions_jobs.json")) } }

        val jobs = api.jobs(null, website, 7368141).value()
        val sync = api.job(null, website, 7368141, 13499901).value()

        assertThat(jobs.map { it.name }).containsExactly("publish", "sync").inOrder()
        assertThat(jobs.all { it.status == RunStatus.COMPLETED && it.conclusion == RunConclusion.SUCCESS }).isTrue()
        assertThat(jobs.all { it.steps.isEmpty() }).isTrue()
        assertThat(sync.name).isEqualTo("sync")
        assertThat(api.job(null, website, 7368141, 1)).isInstanceOf(ForgeResult.Failure::class.java)
    }

    @Test
    fun only_the_latest_attempts_jobs_are_listed() = runTest {
        val jobs = """[{"id":1,"name":"build","status":"failure","attempt":1},{"id":2,"name":"build","status":"running","attempt":2}]"""

        val listed = with(codeberg) { api { json(jobs) } }.jobs(null, website, 9).value()

        assertThat(listed.single().id).isEqualTo(2)
        assertThat(listed.single().status).isEqualTo(RunStatus.IN_PROGRESS)
    }

    @Test
    fun states_map_onto_githubs() {
        assertThat(runState("waiting")).isEqualTo(RunStatus.QUEUED to null)
        assertThat(runState("blocked")).isEqualTo(RunStatus.QUEUED to null)
        assertThat(runState("running")).isEqualTo(RunStatus.IN_PROGRESS to null)
        assertThat(runState("failure")).isEqualTo(RunStatus.COMPLETED to RunConclusion.FAILURE)
        assertThat(runState("skipped")).isEqualTo(RunStatus.COMPLETED to RunConclusion.SKIPPED)
        assertThat(runState("unknown")).isEqualTo(RunStatus.OTHER to null)
    }

    @Test
    fun a_public_log_is_read_without_an_account_and_folds_groups() = runTest {
        val log = with(codeberg) { api { text(fixture("actions_job_log.txt")) } }.jobLog(null, website, 13330092).value()

        val groups = log.entries.filterIsInstance<LogEntry.Group>()
        assertThat(groups.map { it.title }).contains("Getting Git version info")
        val lines = log.entries.flatMap { entry -> if (entry is LogEntry.Group) entry.lines else listOf(entry as LogEntry.Line) }.map { it.text }
        // Timestamps are gone, the runner's own lines stay.
        assertThat(lines).contains("🏁  Job failed")
        assertThat(lines.none { it.startsWith("2026-") }).isTrue()
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/website/actions/jobs/13330092/logs")
        assertThat(codeberg.requests.single().headers["Authorization"]).isNull()
    }

    @Test
    fun workflows_are_the_files_of_the_first_workflow_folder() = runTest {
        val workflows = with(codeberg) {
            api { request ->
                if (request.url.encodedPath.endsWith("/contents/.forgejo/workflows")) json(fixture("workflows_dir.json")) else status(HttpStatusCode.NotFound)
            }
        }.workflows(null, website).value()

        assertThat(workflows.map { it.name }).containsExactly("links.yml", "pr.yml", "push.yml").inOrder()
        assertThat(workflows.map { it.path }).contains(".forgejo/workflows/push.yml")
        assertThat(workflows.map { it.id }.toSet()).hasSize(3)
    }

    @Test
    fun without_forgejo_workflows_the_github_folder_is_read() = runTest {
        val listing = """[{"name":"ci.yml","path":".github/workflows/ci.yml","type":"file"},{"name":"README.md","path":".github/workflows/README.md","type":"file"}]"""
        val workflows = with(codeberg) {
            api { request -> if (".github" in request.url.encodedPath) json(listing) else status(HttpStatusCode.NotFound) }
        }.workflows(null, website).value()

        assertThat(workflows.map { it.path }).containsExactly(".github/workflows/ci.yml")
        assertThat(codeberg.requests.map { it.url.encodedPath.substringAfter("contents/") })
            .containsExactly(".forgejo/workflows", ".gitea/workflows", ".github/workflows").inOrder()
    }

    @Test
    fun dispatch_inputs_are_read_from_the_workflow_file_at_the_ref() = runTest {
        val yaml = """
            name: release
            on:
              workflow_dispatch:
                inputs:
                  channel:
                    type: choice
                    options: [beta, stable]
            jobs: {}
        """.trimIndent()
        val push = Workflow(1, "push.yml", ".forgejo/workflows/push.yml")

        val inputs = with(codeberg) { api { text(yaml) } }.dispatchInputs(null, website, push, "main").value()

        assertThat(inputs!!.single().type).isEqualTo(DispatchInputType.CHOICE)
        val request = codeberg.requests.single().url
        assertThat(request.encodedPath).isEqualTo("/api/v1/repos/forgejo/website/raw/.forgejo/workflows/push.yml")
        assertThat(request.parameters["ref"]).isEqualTo("main")
    }

    @Test
    fun dispatching_names_the_workflow_by_its_file() = runTest {
        val api = with(codeberg) { api { status(HttpStatusCode.NoContent) } }

        val result = api.dispatch("t", website, Workflow(1, "push.yml", ".forgejo/workflows/push.yml"), "main", mapOf("channel" to "beta"))

        assertThat(result).isEqualTo(ForgeResult.Success(Unit))
        val request = codeberg.requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/repos/forgejo/website/actions/workflows/push.yml/dispatches")
        assertThat(request.headers["Authorization"]).isEqualTo("token t")
        val body = (request.body as TextContent).text
        assertThat(body).contains("\"ref\":\"main\"")
        assertThat(body).contains("\"channel\":\"beta\"")
    }

    @Test
    fun cancel_is_supported_rerun_is_not() = runTest {
        val api = with(codeberg) { api { status(HttpStatusCode.NoContent) } }

        assertThat(api.cancel("t", website, 7368141)).isEqualTo(ForgeResult.Success(Unit))
        assertThat(codeberg.requests.single().url.encodedPath).isEqualTo("/api/v1/repos/forgejo/website/actions/runs/7368141/cancel")
        assertThat(api.supportsRerun).isFalse()
        assertThat(api.rerun("t", website, 7368141, failedJobsOnly = false)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        // Unsupported answers without asking the forge.
        assertThat(codeberg.requests).hasSize(1)
    }

    @Test
    fun an_older_server_without_a_job_list_says_so_instead_of_failing() = runTest {
        // Regression: a self-hosted Forgejo listed runs but answered 404 for a run's jobs, and the run wouldn't open.
        val api = with(codeberg) {
            api { request -> if (request.url.encodedPath.endsWith("/jobs")) status(HttpStatusCode.NotFound) else json(fixture("actions_run.json")) }
        }

        assertThat(api.run(null, website, 7368141)).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(api.jobs(null, website, 7368141)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
        assertThat(api.job(null, website, 7368141, 1)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }
}
