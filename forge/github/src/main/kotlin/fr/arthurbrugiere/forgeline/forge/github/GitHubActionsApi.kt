package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStep
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class GitHubActionsApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : ActionsApi {

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = gitHubCall {
        call(token, id, "actions", "runs", runId.toString()).toResult { body<RunResponse>().toModel() }
    }

    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = gitHubCall {
        call(token, id, "actions", "runs", runId.toString(), "jobs", query = mapOf("per_page" to "100"))
            .toResult { body<JobsResponse>().jobs.map { it.toModel() } }
    }

    override suspend fun jobLog(token: String, id: RepoId, jobId: Long): ForgeResult<JobLog> = gitHubCall {
        // Answers a redirect to short-lived blob storage, which the client follows.
        val response = call(token, id, "actions", "jobs", jobId.toString(), "logs")
        if (response.status != HttpStatusCode.OK) return@gitHubCall response.failure()
        val text = response.bodyAsText()
        // Logs run to megabytes: parse off the caller's thread.
        ForgeResult.Success(withContext(Dispatchers.Default) { GitHubLogParser.parse(text) })
    }

    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = gitHubCall {
        call(token, id, "actions", "workflows", query = mapOf("per_page" to "100")).toResult {
            body<WorkflowsResponse>().workflows
                // Dynamic workflows (Copilot, Dependabot) have no file and can't be started by hand.
                .filter { it.state == "active" && it.path.startsWith(".github/workflows/") }
                .map { Workflow(it.id, it.name, it.path) }
        }
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> =
        gitHubCall {
            val response = call(token, id, "contents", *workflow.path.split('/').toTypedArray(), query = mapOf("ref" to ref))
            if (response.status != HttpStatusCode.OK) return@gitHubCall response.failure()
            val yaml = response.body<ContentResponse>().decodedText()
                ?: return@gitHubCall ForgeResult.Failure(ForgeError.Http(413, "Workflow file too large"))
            ForgeResult.Success(WorkflowDispatchParser.inputs(yaml))
        }

    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> =
        gitHubCall {
            val body = buildJsonObject {
                put("ref", ref)
                if (inputs.isNotEmpty()) put("inputs", buildJsonObject { inputs.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
            }
            call(token, id, "actions", "workflows", workflow.id.toString(), "dispatches", method = HttpMethod.Post, body = body).toResult { }
        }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> = gitHubCall {
        call(token, id, "actions", "runs", runId.toString(), if (failedJobsOnly) "rerun-failed-jobs" else "rerun", method = HttpMethod.Post)
            .toResult { }
    }

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> = gitHubCall {
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
        steps = steps.map { RunStep(it.number, it.name, runStatus(it.status), runConclusion(it.conclusion)) },
    )
}

@Serializable
private data class StepResponse(val number: Int, val name: String, val status: String? = null, val conclusion: String? = null)

@Serializable
private data class WorkflowsResponse(val workflows: List<WorkflowResponse>)

@Serializable
private data class WorkflowResponse(val id: Long, val name: String, val path: String, val state: String)
