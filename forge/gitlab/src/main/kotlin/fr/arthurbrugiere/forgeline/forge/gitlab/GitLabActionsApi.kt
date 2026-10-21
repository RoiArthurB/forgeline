package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.JobLogReader
import fr.arthurbrugiere.forgeline.core.forge.logPartOf
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogPart
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import java.io.OutputStream
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GitLabActionsApi(private val httpClient: HttpClient) : ActionsApi {

    override val supportsRerun: Boolean = true

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "pipelines", runId.toString())
            .toResult { body<GitLabPipelineJson>().toRun() }
    }

    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "pipelines", runId.toString(), "jobs", query = mapOf("per_page" to "100"))
            .toResult { body<List<GitLabJobJson>>().map { it.toRunJob() } }
    }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "jobs", jobId.toString())
            .toResult { body<GitLabJobJson>().toRunJob() }
    }

    override val logFormat = LogFormat.GITLAB

    override suspend fun openJobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLogReader> = gitlabCall {
        when (val first = readLog(token, id, jobId, 0L..0L)) {
            is ForgeResult.Failure -> first
            is ForgeResult.Success -> ForgeResult.Success(JobLogReader(first.value.total) { range -> gitlabCall { readLog(token, id, jobId, range) } })
        }
    }

    private suspend fun readLog(token: String?, id: RepoId, jobId: Long, range: LongRange): ForgeResult<LogPart> =
        logRequest(token, id, jobId) { header(HttpHeaders.Range, "bytes=${range.first}-${range.last}") }.execute { response ->
            logPartOf(response.status.value, response.headers[HttpHeaders.ContentRange]) { response.readRawBytes() } ?: response.failure()
        }

    override suspend fun downloadJobLog(token: String?, id: RepoId, jobId: Long, into: OutputStream): ForgeResult<Unit> = gitlabCall {
        logRequest(token, id, jobId).execute { response ->
            if (!response.status.isSuccess()) return@execute response.failure()
            response.bodyAsChannel().writeTo(into)
            ForgeResult.Success(Unit)
        }
    }

    private suspend fun logRequest(token: String?, id: RepoId, jobId: Long, more: HttpRequestBuilder.() -> Unit = {}): HttpStatement =
        httpClient.gitlabStatement(id.forge, token, "projects", id.fullName, "jobs", jobId.toString(), "trace", more = more)

    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = gitlabCall {
        val response = httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "repository", "files", ".gitlab-ci.yml", query = mapOf("ref" to "HEAD"))
        if (response.status == HttpStatusCode.NotFound) return@gitlabCall ForgeResult.Success(emptyList())
        if (!response.status.isSuccess()) return@gitlabCall response.failure()
        ForgeResult.Success(listOf(Workflow(id = 1L, name = "GitLab CI", path = ".gitlab-ci.yml")))
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> =
        ForgeResult.Success(emptyList())

    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> = gitlabCall {
        val payload = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) {
                put("variables", buildJsonArray {
                    inputs.forEach { (key, value) ->
                        add(buildJsonObject {
                            put("key", key)
                            put("value", value)
                        })
                    }
                })
            }
        }
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "pipeline", method = HttpMethod.Post, body = payload)
            .toResult { }
    }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "pipelines", runId.toString(), "retry", method = HttpMethod.Post)
            .toResult { }
    }

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", id.fullName, "pipelines", runId.toString(), "cancel", method = HttpMethod.Post)
            .toResult { }
    }
}
