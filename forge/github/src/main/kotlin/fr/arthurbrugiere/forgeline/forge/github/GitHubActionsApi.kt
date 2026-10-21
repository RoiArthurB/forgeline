package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.JobLogReader
import fr.arthurbrugiere.forgeline.core.forge.WorkflowDispatchParser
import fr.arthurbrugiere.forgeline.core.forge.logPartOf
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogPart
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.common.writeTo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.time.Instant

class GitHubActionsApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : ActionsApi {

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = forgeCall {
        call(token, id, "actions", "runs", runId.toString()).toResult { body<RunResponse>().toModel() }
    }

    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = forgeCall {
        call(token, id, "actions", "runs", runId.toString(), "jobs", query = mapOf("per_page" to "100"))
            .toResult { body<JobsResponse>().jobs.map { it.toModel() } }
    }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob> = forgeCall {
        call(token, id, "actions", "jobs", jobId.toString()).toResult { body<JobResponse>().toModel() }
    }

    override val logFormat = LogFormat.GITHUB

    /**
     * The API answers with a redirect to blob storage, signed for ten minutes (checked 2026-10-17). Parts are read from
     * there, sparing the API's rate limit; once the signature runs out, storage refuses and the log is opened again.
     */
    override suspend fun openJobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLogReader> = forgeCall {
        // Signed-in users only, even on public repositories.
        if (token == null) return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        when (val opened = locateLog(token, id, jobId)) {
            is ForgeResult.Failure -> opened
            is ForgeResult.Success -> {
                var location = opened.value.first
                ForgeResult.Success(
                    JobLogReader(opened.value.second) { range ->
                        forgeCall {
                            val read = readLog(location, token, range)
                            val expired = (read as? ForgeResult.Failure)?.error.let { it is ForgeError.Http && it.status == 403 }
                            if (!expired) return@forgeCall read
                            when (val again = locateLog(token, id, jobId)) {
                                is ForgeResult.Failure -> again
                                is ForgeResult.Success -> readLog(again.value.first.also { location = it }, token, range)
                            }
                        }
                    },
                )
            }
        }
    }

    /** Where the log's bytes are read from, and how many there are. */
    private suspend fun locateLog(token: String, id: RepoId, jobId: Long): ForgeResult<Pair<Url, Long>> =
        logRequest(token, id, jobId) { header(HttpHeaders.Range, "bytes=0-0") }.execute { response ->
            when (val part = logPartOf(response.status.value, response.headers[HttpHeaders.ContentRange]) { response.readRawBytes() } ?: response.failure()) {
                is ForgeResult.Failure -> part
                is ForgeResult.Success -> ForgeResult.Success(response.call.request.url to part.value.total)
            }
        }

    /** Bytes [range] from [location]: the API itself carries the token, storage's signed address never does. */
    private suspend fun readLog(location: Url, token: String, range: LongRange): ForgeResult<LogPart> =
        httpClient.prepareGet(location) {
            if (location.host == Url(apiBaseUrl).host) gitHubHeaders(token)
            header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
        }.execute { response ->
            logPartOf(response.status.value, response.headers[HttpHeaders.ContentRange]) { response.readRawBytes() } ?: response.failure()
        }

    override suspend fun downloadJobLog(token: String?, id: RepoId, jobId: Long, into: OutputStream): ForgeResult<Unit> = forgeCall {
        if (token == null) return@forgeCall ForgeResult.Failure(ForgeError.Unauthorized)
        logRequest(token, id, jobId).execute { response ->
            if (!response.status.isSuccess()) return@execute response.failure()
            response.bodyAsChannel().writeTo(into)
            ForgeResult.Success(Unit)
        }
    }

    private suspend fun logRequest(token: String, id: RepoId, jobId: Long, more: HttpRequestBuilder.() -> Unit = {}): HttpStatement =
        httpClient.prepareGet {
            url {
                takeFrom(apiBaseUrl)
                appendPathSegments("repos", id.owner, id.name, "actions", "jobs", jobId.toString(), "logs")
            }
            gitHubHeaders(token)
            // A log runs to tens of thousands of lines: read as it comes, never kept with the answers.
            header(HttpHeaders.CacheControl, "no-store")
            more()
        }

    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = forgeCall {
        call(token, id, "actions", "workflows", query = mapOf("per_page" to "100")).toResult {
            body<WorkflowsResponse>().workflows
                // Dynamic workflows (Copilot, Dependabot) have no file and can't be started by hand.
                .filter { it.state == "active" && it.path.startsWith(".github/workflows/") }
                .map { Workflow(it.id, it.name, it.path) }
        }
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> =
        forgeCall {
            val response = call(token, id, "contents", *workflow.path.split('/').toTypedArray(), query = mapOf("ref" to ref))
            if (response.status != HttpStatusCode.OK) return@forgeCall response.failure()
            val yaml = response.body<ContentResponse>().decodedText()
                ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(413, "Workflow file too large"))
            ForgeResult.Success(WorkflowDispatchParser.inputs(yaml))
        }

    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> =
        forgeCall {
            val body = buildJsonObject {
                put("ref", ref)
                if (inputs.isNotEmpty()) put("inputs", buildJsonObject { inputs.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
            }
            call(token, id, "actions", "workflows", workflow.id.toString(), "dispatches", method = HttpMethod.Post, body = body).toResult { }
        }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> = forgeCall {
        call(token, id, "actions", "runs", runId.toString(), if (failedJobsOnly) "rerun-failed-jobs" else "rerun", method = HttpMethod.Post)
            .toResult { }
    }

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> = forgeCall {
        call(token, id, "actions", "runs", runId.toString(), "cancel", method = HttpMethod.Post).toResult { }
    }

    private suspend fun call(
        token: String?,
        id: RepoId,
        vararg segments: String,
        method: HttpMethod = HttpMethod.Get,
        query: Map<String, String> = emptyMap(),
        body: kotlinx.serialization.json.JsonObject? = null,
    ): HttpResponse = httpClient.gitHubApi(apiBaseUrl, token, "repos", id.owner, id.name, *segments, method = method, query = query, body = body)
}

@Serializable
private data class JobsResponse(val jobs: List<JobResponse>)

@Serializable
private data class JobResponse(
    val id: Long,
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val steps: List<StepResponse> = emptyList(),
) {
    fun toModel() = RunJob(
        id = id,
        name = name,
        status = runStatus(status),
        conclusion = runConclusion(conclusion),
        startedAt = startedAt?.let(Instant::parse),
        completedAt = completedAt?.let(Instant::parse),
        steps = steps.map {
            RunStep(
                it.number, it.name, runStatus(it.status), runConclusion(it.conclusion),
                it.startedAt?.let(Instant::parse), it.completedAt?.let(Instant::parse),
            )
        },
    )
}

@Serializable
private data class StepResponse(
    val number: Int,
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
private data class WorkflowsResponse(val workflows: List<WorkflowResponse>)

@Serializable
private data class WorkflowResponse(val id: Long, val name: String, val path: String, val state: String)
