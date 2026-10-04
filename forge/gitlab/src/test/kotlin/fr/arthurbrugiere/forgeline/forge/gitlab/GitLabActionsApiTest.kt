package fr.arthurbrugiere.forgeline.forge.gitlab

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
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
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GitLabActionsApiTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()
    private val repo = RepoId("gitlab-org", "gitlab", ForgeInstance.GitLab)
    private val workflow = Workflow(1L, "GitLab CI", ".gitlab-ci.yml")

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitLabActionsApi(gitlabHttpClient(MockEngine { requests += it; handler(it) }))

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun fetches_pipeline_run() = runTest {
        val json = """
            {
                "id": 12345,
                "iid": 100,
                "project_id": 278964,
                "sha": "a1b2c3d4",
                "ref": "main",
                "status": "success",
                "created_at": "2026-10-01T10:00:00Z",
                "updated_at": "2026-10-01T10:05:00Z",
                "web_url": "https://gitlab.com/gitlab-org/gitlab/-/pipelines/12345"
            }
        """.trimIndent()

        val run = api { json(json) }.run("token", repo, 12345).value()
        assertThat(run.id).isEqualTo(12345)
        assertThat(run.status).isEqualTo(RunStatus.COMPLETED)
        assertThat(run.conclusion).isEqualTo(RunConclusion.SUCCESS)
        assertThat(run.branch).isEqualTo("main")
        assertThat(requests.single().url.encodedPath).contains("/pipelines/12345")
    }

    @Test
    fun lists_pipeline_jobs() = runTest {
        val json = """
            [
                {
                    "id": 501,
                    "name": "build",
                    "stage": "build",
                    "status": "success",
                    "created_at": "2026-10-01T10:00:00Z"
                },
                {
                    "id": 502,
                    "name": "test",
                    "stage": "test",
                    "status": "failed",
                    "created_at": "2026-10-01T10:01:00Z"
                }
            ]
        """.trimIndent()

        val jobs = api { json(json) }.jobs("token", repo, 12345).value()
        assertThat(jobs).hasSize(2)
        assertThat(jobs[0].id).isEqualTo(501)
        assertThat(jobs[0].name).isEqualTo("build")
        assertThat(jobs[0].conclusion).isEqualTo(RunConclusion.SUCCESS)
        assertThat(jobs[1].id).isEqualTo(502)
        assertThat(jobs[1].name).isEqualTo("test")
        assertThat(jobs[1].conclusion).isEqualTo(RunConclusion.FAILURE)
    }

    @Test
    fun fetches_job_trace_log() = runTest {
        val logText = "Running with gitlab-runner 16.0\nJob succeeded\n"
        val log = api { respond(logText, HttpStatusCode.OK) }.jobLog("token", repo, 501).value()
        assertThat(log.entries).isNotEmpty()
        assertThat(requests.single().url.encodedPath).contains("/jobs/501/trace")
    }

    @Test
    fun triggers_pipeline_dispatch() = runTest {
        val res = api { json("""{"id": 12346}""", HttpStatusCode.Created) }
            .dispatch("token", repo, workflow, "main", mapOf("DEPLOY_ENV" to "staging"))
        assertThat(res).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests.single().method).isEqualTo(HttpMethod.Post)
        assertThat(requests.single().url.encodedPath).contains("/pipeline")
    }

    @Test
    fun retries_and_cancels_pipeline() = runTest {
        val api = api { json("""{"id": 12345}""") }

        val retryRes = api.rerun("token", repo, 12345, failedJobsOnly = false)
        assertThat(retryRes).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests[0].url.encodedPath).contains("/pipelines/12345/retry")

        val cancelRes = api.cancel("token", repo, 12345)
        assertThat(cancelRes).isInstanceOf(ForgeResult.Success::class.java)
        assertThat(requests[1].url.encodedPath).contains("/pipelines/12345/cancel")
    }
}
