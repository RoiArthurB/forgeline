package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ActionsLogParser
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.WorkflowDispatchParser
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
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

/**
 * A Forgejo repository's Actions (checked against codeberg.org, 2026-09-30). Unlike GitHub's:
 * - public runs, jobs and logs need no account;
 * - jobs carry no steps or times, and the API has no single-job call: a job is read from its run's list;
 * - runs have an API id and a per-repository number, and web pages use the number ([WorkflowRun.webUrl]);
 * - workflows are files in `.forgejo/workflows` (or `.gitea/`, `.github/`), with no list of their own;
 * - there's no re-run.
 */
class ForgejoActionsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : ActionsApi {

    override val supportsRerun: Boolean = false

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = forgejoCall {
        call(token, id, "actions", "runs", runId.toString()).toResult { body<RunJson>().toModel() }
    }

    /** Only the latest attempt's jobs, like GitHub's list. */
    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = forgejoCall {
        call(token, id, "actions", "runs", runId.toString(), "jobs").toResult {
            val jobs = body<List<JobJson>>()
            val latest = jobs.maxOfOrNull { it.attempt } ?: 0
            jobs.filter { it.attempt == latest }.map { it.toModel() }
        }
    }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob> =
        when (val jobs = jobs(token, id, runId)) {
            is ForgeResult.Failure -> jobs
            is ForgeResult.Success -> jobs.value.firstOrNull { it.id == jobId }?.let { ForgeResult.Success(it) }
                ?: ForgeResult.Failure(ForgeError.Http(404, "No job $jobId in run $runId"))
        }

    override suspend fun jobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLog> = forgejoCall {
        val response = call(token, id, "actions", "jobs", jobId.toString(), "logs")
        if (response.status != HttpStatusCode.OK) return@forgejoCall response.failure()
        val text = response.bodyAsText()
        // The runner writes GitHub's log format; logs run to megabytes, so parse off the caller's thread.
        ForgeResult.Success(withContext(Dispatchers.Default) { ActionsLogParser.parse(text) })
    }

    /** The workflow files, from the first folder Forgejo reads them from that exists. */
    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = forgejoCall {
        for (folder in WORKFLOW_FOLDERS) {
            val response = call(token, id, "contents", *folder.split('/').toTypedArray())
            if (response.status == HttpStatusCode.NotFound) continue
            return@forgejoCall response.toResult {
                body<List<ContentJson>>()
                    .filter { it.type == "file" && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
                    .map { Workflow(it.path.hashCode().toLong(), it.name, it.path) }
            }
        }
        ForgeResult.Success(emptyList())
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> =
        forgejoCall {
            val response = call(token, id, "raw", *workflow.path.split('/').toTypedArray(), query = mapOf("ref" to ref))
            if (response.status != HttpStatusCode.OK) return@forgejoCall response.failure()
            ForgeResult.Success(WorkflowDispatchParser.inputs(response.bodyAsText()))
        }

    /** Forgejo names the workflow by its file name. */
    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> =
        forgejoCall {
            val body = buildJsonObject {
                put("ref", ref)
                if (inputs.isNotEmpty()) put("inputs", buildJsonObject { inputs.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
            }
            val file = workflow.path.substringAfterLast('/')
            call(token, id, "actions", "workflows", file, "dispatches", method = HttpMethod.Post, body = body).toResult { }
        }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> =
        ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> = forgejoCall {
        call(token, id, "actions", "runs", runId.toString(), "cancel", method = HttpMethod.Post).toResult { }
    }

    /** The newest runs. `page` must be sent: without it Codeberg ignores `limit` and answers every run (56 MB, 2026-09-30). */
    suspend fun runs(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = forgejoCall {
        call(token, id, "actions", "runs", query = mapOf("page" to "1", "limit" to "$RUNS")).toResult {
            body<RunsJson>().runs.map { it.toModel() }
        }
    }

    private suspend fun call(
        token: String?,
        id: RepoId,
        vararg segments: String,
        method: HttpMethod = HttpMethod.Get,
        query: Map<String, String> = emptyMap(),
        body: kotlinx.serialization.json.JsonObject? = null,
    ): HttpResponse = httpClient.forgejoApi(forge, token, "repos", id.owner, id.name, *segments, method = method, query = query, body = body)

    private companion object {
        const val RUNS = 20
        val WORKFLOW_FOLDERS = listOf(".forgejo/workflows", ".gitea/workflows", ".github/workflows")
    }
}

/** Forgejo's run and job states: `waiting`, `blocked`, `running`, then `success`, `failure`, `cancelled`, `skipped`. */
internal fun runState(value: String?): Pair<RunStatus, RunConclusion?> = when (value) {
    "waiting", "blocked" -> RunStatus.QUEUED to null
    "running" -> RunStatus.IN_PROGRESS to null
    "success" -> RunStatus.COMPLETED to RunConclusion.SUCCESS
    "failure" -> RunStatus.COMPLETED to RunConclusion.FAILURE
    "cancelled" -> RunStatus.COMPLETED to RunConclusion.CANCELLED
    "skipped" -> RunStatus.COMPLETED to RunConclusion.SKIPPED
    else -> RunStatus.OTHER to null
}

@Serializable
private data class RunsJson(@SerialName("workflow_runs") val runs: List<RunJson> = emptyList())

@Serializable
private data class RunJson(
    val id: Long,
    val title: String = "",
    @SerialName("workflow_id") val workflowId: String = "",
    @SerialName("index_in_repo") val number: Int = 0,
    val prettyref: String? = null,
    val event: String? = null,
    @SerialName("trigger_event") val triggerEvent: String? = null,
    val status: String? = null,
    val created: String? = null,
    val started: String? = null,
    val updated: String? = null,
    @SerialName("trigger_user") val triggerUser: UserJson? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
) {
    fun toModel(): WorkflowRun {
        val (status, conclusion) = runState(status)
        return WorkflowRun(
            id = id,
            workflowName = workflowId,
            title = title,
            status = status,
            conclusion = conclusion,
            // A pull request's run is on "#12", not on a branch.
            branch = prettyref?.takeUnless { it.startsWith("#") || it.isBlank() },
            event = triggerEvent ?: event.orEmpty(),
            runNumber = number,
            createdAt = instant(created) ?: Instant.EPOCH,
            actor = triggerUser?.toModel(),
            startedAt = instant(started),
            updatedAt = instant(updated),
            webUrl = htmlUrl,
        )
    }
}

@Serializable
private data class JobJson(val id: Long, val name: String, val status: String? = null, val attempt: Int = 1) {
    fun toModel(): RunJob {
        val (status, conclusion) = runState(status)
        return RunJob(id, name, status, conclusion, startedAt = null, completedAt = null, steps = emptyList())
    }
}
