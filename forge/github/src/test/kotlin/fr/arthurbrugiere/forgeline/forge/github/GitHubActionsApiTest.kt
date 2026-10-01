package fr.arthurbrugiere.forgeline.forge.github

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.LogEntry
import fr.arthurbrugiere.forgeline.core.model.LogLineKind
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.Workflow
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** Fixtures are real api.github.com responses for paperclipai/paperclip captured on 2026-09-29, some trimmed. */
class GitHubActionsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val paperclip = RepoId("paperclipai", "paperclip")
    private val release = Workflow(1, "Release", ".github/workflows/release.yml")

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/github/repo/$name")) { name }.readText()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubActionsApi(gitHubHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    private fun contentOf(yaml: String) =
        """{"type":"file","encoding":"base64","path":"x","name":"x","content":"${Base64.getEncoder().encodeToString(yaml.toByteArray())}"}"""

    @Test
    fun reads_a_run_with_its_attempt_and_timing() = runTest {
        val run = api { json(fixture("run.json")) }.run(null, paperclip, 36539745670).value()

        assertThat(run.id).isEqualTo(36539745670)
        assertThat(run.workflowName).isEqualTo("PR")
        assertThat(run.status).isEqualTo(RunStatus.COMPLETED)
        assertThat(run.conclusion).isEqualTo(RunConclusion.FAILURE)
        assertThat(run.workflowId).isEqualTo(249194268)
        assertThat(run.attempt).isEqualTo(1)
        assertThat(run.startedAt).isNotNull()
        assertThat(run.updatedAt).isNotNull()
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/actions/runs/36539745670")
    }

    @Test
    fun lists_a_runs_jobs_with_their_steps() = runTest {
        val jobs = api { json(fixture("run_jobs.json")) }.jobs(null, paperclip, 36539745670).value()

        assertThat(jobs.map { it.name }).containsExactly(
            "ci / Select trusted runner", "ci / Verify Paperclip Runner (vitest 1/2)", "ci / e2e",
        ).inOrder()
        val failed = jobs[1]
        assertThat(failed.conclusion).isEqualTo(RunConclusion.FAILURE)
        assertThat(failed.startedAt).isEqualTo(Instant.parse("2026-09-29T07:57:44Z"))
        assertThat(failed.steps.first { it.conclusion == RunConclusion.FAILURE }.name).isEqualTo("Verify Paperclip Runner")
        assertThat(requests.single().url.parameters["per_page"]).isEqualTo("100")
    }

    @Test
    fun a_running_job_reports_its_steps_as_they_go() = runTest {
        // Captured while the job ran: its log doesn't exist yet (the logs endpoint answers 404), its steps do.
        val job = api { json(fixture("job_running.json")) }.job(null, paperclip, 1, 109371394234).value()

        assertThat(job.status).isEqualTo(RunStatus.IN_PROGRESS)
        val current = job.steps.single { it.status == RunStatus.IN_PROGRESS }
        assertThat(current.name).isEqualTo("Verify Paperclip Runner")
        assertThat(current.startedAt).isEqualTo(Instant.parse("2026-09-29T10:50:01Z"))
        assertThat(current.completedAt).isNull()
        assertThat(job.steps.first().completedAt).isEqualTo(Instant.parse("2026-09-29T10:49:07Z"))
        assertThat(job.steps.last().status).isEqualTo(RunStatus.QUEUED)
        assertThat(requests.single().url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/actions/jobs/109371394234")
    }

    @Test
    fun a_job_log_folds_groups_and_marks_errors() = runTest {
        val log = api { respond(fixture("job_log.txt"), HttpStatusCode.OK) }.jobLog("token", paperclip, 109312096849).value()

        val groups = log.entries.filterIsInstance<LogEntry.Group>()
        assertThat(groups.first().title).isEqualTo("Runner Image Provisioner")
        assertThat(groups.first().lines.first().text).isEqualTo("Hosted Compute Agent")
        val errors = log.entries.filterIsInstance<LogEntry.Line>().filter { it.kind == LogLineKind.ERROR }
        assertThat(errors.first().text).startsWith("NativeSessionCloseUnrecoverableError")
        // An error's following lines, which carry no timestamp, belong to it.
        assertThat(errors.any { it.text.contains("runnerd-codex-transport.ts:4314") }).isTrue()
        assertThat(errors.last().text).isEqualTo("Process completed with exit code 1.")
        assertThat(log.entries.filterIsInstance<LogEntry.Line>().any { it.kind == LogLineKind.COMMAND && it.text == "/usr/bin/git version" }).isTrue()
        // Timestamps are gone; ANSI colors stay for the viewer.
        assertThat(log.entries.filterIsInstance<LogEntry.Line>().none { it.text.startsWith("2026-") }).isTrue()
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer token")
    }

    @Test
    fun the_log_redirect_is_followed_without_the_token() = runTest {
        val log = api {
            if (it.url.host == "api.github.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://logs.example/job.txt?sig=abc"))
            } else {
                respond("2026-09-29T07:57:45.0000000Z hello\n", HttpStatusCode.OK)
            }
        }.jobLog("token", paperclip, 1).value()

        assertThat(log.entries).containsExactly(LogEntry.Line("hello"))
        assertThat(requests.map { it.url.host }).containsExactly("api.github.com", "logs.example").inOrder()
        // The signed storage URL must never receive the forge token.
        assertThat(requests[1].headers[HttpHeaders.Authorization]).isNull()
    }

    @Test
    fun workflows_without_a_file_are_left_out() = runTest {
        val workflows = api { json(fixture("workflows.json")) }.workflows(null, paperclip).value()

        assertThat(workflows.map { it.name }).containsExactly("Docker", "PR", "Release")
        assertThat(workflows.first { it.name == "Release" }.path).isEqualTo(".github/workflows/release.yml")
    }

    @Test
    fun reads_the_inputs_a_workflow_asks_for() = runTest {
        val inputs = api { json(contentOf(fixture("workflow_release.yml"))) }.dispatchInputs(null, paperclip, release, "master").value()!!

        val channel = inputs.first { it.name == "channel" }
        assertThat(channel.type).isEqualTo(DispatchInputType.CHOICE)
        assertThat(channel.required).isTrue()
        assertThat(channel.default).isEqualTo("stable")
        assertThat(channel.options).contains("nightly")
        val migrator = inputs.first { it.name == "preview_migrator" }
        assertThat(migrator.type).isEqualTo(DispatchInputType.BOOLEAN)
        assertThat(migrator.default).isEqualTo("false")
        assertThat(requests.single().url.toString())
            .isEqualTo("https://api.github.com/repos/paperclipai/paperclip/contents/.github/workflows/release.yml?ref=master")
    }

    @Test
    fun workflows_state_whether_they_can_be_started_by_hand() = runTest {
        val cases = mapOf(
            "on: workflow_dispatch" to emptyList<String>(),
            "on: [push, workflow_dispatch]" to emptyList(),
            "on:\n  workflow_dispatch:\n  push:" to emptyList(),
            "on:\n  workflow_dispatch:\n    inputs:\n      name: {}" to listOf("name"),
            "on: push" to null,
            "on:\n  pull_request:" to null,
            "not: [valid" to null,
        )
        for ((yaml, expected) in cases) {
            val inputs = api { json(contentOf(yaml)) }.dispatchInputs(null, paperclip, release, "main").value()
            assertThat(inputs?.map { it.name }).isEqualTo(expected)
        }
    }

    @Test
    fun starting_a_workflow_sends_the_ref_and_inputs() = runTest {
        api { respond("", HttpStatusCode.NoContent) }.dispatch("token", paperclip, release, "master", mapOf("channel" to "beta"))

        val request = requests.single()
        assertThat(request.method).isEqualTo(HttpMethod.Post)
        assertThat(request.url.encodedPath).isEqualTo("/repos/paperclipai/paperclip/actions/workflows/1/dispatches")
        assertThat((request.body as TextContent).text).isEqualTo("""{"ref":"master","inputs":{"channel":"beta"}}""")
    }

    @Test
    fun runs_can_be_rerun_whole_or_failed_jobs_only_and_cancelled() = runTest {
        val api = api { respond("", HttpStatusCode.Created) }

        api.rerun("token", paperclip, 7, failedJobsOnly = false)
        api.rerun("token", paperclip, 7, failedJobsOnly = true)
        api.cancel("token", paperclip, 7)

        assertThat(requests.map { it.method to it.url.encodedPath }).containsExactly(
            HttpMethod.Post to "/repos/paperclipai/paperclip/actions/runs/7/rerun",
            HttpMethod.Post to "/repos/paperclipai/paperclip/actions/runs/7/rerun-failed-jobs",
            HttpMethod.Post to "/repos/paperclipai/paperclip/actions/runs/7/cancel",
        ).inOrder()
    }

    @Test
    fun without_write_access_a_rerun_is_refused() = runTest {
        val result = api { json("""{"message":"Must have admin rights to Repository."}""", HttpStatusCode.Forbidden) }
            .rerun("token", paperclip, 7, failedJobsOnly = false)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Http(403, "Must have admin rights to Repository.")))
    }

    @Test
    fun signed_out_a_log_is_unauthorized_without_asking() = runTest {
        var asked = false
        val result = api { asked = true; json("{}") }.jobLog(null, paperclip, 1)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(asked).isFalse()
    }
}
