package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.JobLogReader
import fr.arthurbrugiere.forgeline.core.forge.WorkflowDispatchParser
import fr.arthurbrugiere.forgeline.core.forge.logPartOf
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogPart
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.common.writeTo
import fr.arthurbrugiere.forgeline.forge.forgejo.api.ActionRun
import fr.arthurbrugiere.forgeline.forge.forgejo.api.ActionRunJob
import fr.arthurbrugiere.forgeline.forge.forgejo.api.ListActionRunResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.time.Instant

/**
 * A Forgejo repository's Actions (checked against codeberg.org, 2026-09-30). Unlike GitHub's:
 * - public runs, jobs and logs need no account;
 * - jobs carry no steps or times, and the API has no single-job call: a job is read from its run's list;
 * - runs have an API id and a per-repository number, and web pages use the number ([WorkflowRun.webUrl]);
 * - workflows are files in `.forgejo/workflows` (or `.gitea/`, `.github/`), with no list of their own;
 * - there's no re-run.
 *
 * Gitea's (checked against its API description, v1.24 to v1.27) is the same where both have a call, but it has re-run
 * and no cancel, and lists runs only since v1.26.
 */
class ForgejoActionsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : ActionsApi {

    private val gitea = forge.type == ForgeType.GITEA

    override val supportsRerun: Boolean = gitea

    override val supportsCancel: Boolean = !gitea

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = forgeCall {
        call(token, id, "actions", "runs", runId.toString()).toResult { body<ActionRun>().toModel() }
    }

    /**
     * Only the latest attempt's jobs, like GitHub's list. Older Forgejo servers list runs but not their jobs (a 404
     * for a run that exists): Unsupported, so the run still shows.
     */
    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = forgeCall {
        val response = call(token, id, "actions", "runs", runId.toString(), "jobs")
        if (response.status == HttpStatusCode.NotFound) return@forgeCall ForgeResult.Failure(ForgeError.Unsupported)
        response.toResult {
            val jobs = body<List<ActionRunJob>>()
            val latest = jobs.maxOfOrNull { it.tries } ?: 0
            jobs.filter { it.tries == latest }.map { it.toModel() }
        }
    }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob> =
        when (val jobs = jobs(token, id, runId)) {
            is ForgeResult.Failure -> jobs
            is ForgeResult.Success -> jobs.value.firstOrNull { it.id == jobId }?.let { ForgeResult.Success(it) }
                ?: ForgeResult.Failure(ForgeError.Http(404, "No job $jobId in run $runId"))
        }

    override val logFormat = LogFormat.FORGEJO

    override suspend fun openJobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLogReader> = forgeCall {
        when (val first = readLog(token, id, jobId, 0L..0L)) {
            is ForgeResult.Failure -> first
            is ForgeResult.Success -> ForgeResult.Success(JobLogReader(first.value.total) { range -> forgeCall { readLog(token, id, jobId, range) } })
        }
    }

    private suspend fun readLog(token: String?, id: RepoId, jobId: Long, range: LongRange): ForgeResult<LogPart> =
        logRequest(token, id, jobId) { header(HttpHeaders.Range, "bytes=${range.first}-${range.last}") }.execute { response ->
            logPartOf(response.status.value, response.headers[HttpHeaders.ContentRange]) { response.readRawBytes() } ?: response.failure()
        }

    override suspend fun downloadJobLog(token: String?, id: RepoId, jobId: Long, into: OutputStream): ForgeResult<Unit> = forgeCall {
        logRequest(token, id, jobId).execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute response.failure()
            response.bodyAsChannel().writeTo(into)
            ForgeResult.Success(Unit)
        }
    }

    private suspend fun logRequest(token: String?, id: RepoId, jobId: Long, more: HttpRequestBuilder.() -> Unit = {}): HttpStatement =
        httpClient.forgejoStatement(forge, token, "repos", id.owner, id.name, "actions", "jobs", jobId.toString(), "logs", more = more)

    /** The workflow files, from the first folder Forgejo reads them from that exists. */
    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = forgeCall {
        for (folder in WORKFLOW_FOLDERS) {
            val files = when (val tree = httpClient.forgejoTree(forge, token, id, "HEAD", folder)) {
                is ForgeResult.Failure -> return@forgeCall tree
                is ForgeResult.Success -> tree.value ?: continue
            }
            return@forgeCall ForgeResult.Success(
                files
                    .filter { it.entryType == "blob" && (it.entryPath.endsWith(".yml") || it.entryPath.endsWith(".yaml")) }
                    .map { "$folder/${it.entryPath}".let { path -> Workflow(path.hashCode().toLong(), it.entryPath, path) } },
            )
        }
        ForgeResult.Success(emptyList())
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> =
        forgeCall {
            val response = call(token, id, "raw", *workflow.path.split('/').toTypedArray(), query = mapOf("ref" to ref))
            if (response.status != HttpStatusCode.OK) return@forgeCall response.failure()
            ForgeResult.Success(WorkflowDispatchParser.inputs(response.bodyAsText()))
        }

    /** Forgejo names the workflow by its file name. */
    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> =
        forgeCall {
            val body = buildJsonObject {
                put("ref", ref)
                if (inputs.isNotEmpty()) put("inputs", buildJsonObject { inputs.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
            }
            val file = workflow.path.substringAfterLast('/')
            call(token, id, "actions", "workflows", file, "dispatches", method = HttpMethod.Post, body = body).toResult { }
        }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> {
        if (!gitea) return ForgeResult.Failure(ForgeError.Unsupported)
        return forgeCall {
            call(token, id, "actions", "runs", runId.toString(), if (failedJobsOnly) "rerun-failed-jobs" else "rerun", method = HttpMethod.Post).toResult { }
        }
    }

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> {
        if (gitea) return ForgeResult.Failure(ForgeError.Unsupported)
        return forgeCall {
            call(token, id, "actions", "runs", runId.toString(), "cancel", method = HttpMethod.Post).toResult { }
        }
    }

    /** The newest runs. `page` must be sent: without it Codeberg ignores `limit` and answers every run (56 MB, 2026-09-30). */
    suspend fun runs(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = forgeCall {
        call(token, id, "actions", "runs", query = mapOf("page" to "1", "limit" to "$RUNS")).toResult {
            body<ListActionRunResponse>().workflowRuns.orEmpty().map { it.toModel() }
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

private fun ActionRun.toModel(): WorkflowRun {
    val (status, conclusion) = runState(status)
    return WorkflowRun(
        id = required(id, "a run's id"),
        workflowName = workflowId.orEmpty(),
        title = title.orEmpty(),
        status = status,
        conclusion = conclusion,
        // A pull request's run is on "#12", not on a branch.
        branch = prettyref?.takeUnless { it.startsWith("#") || it.isBlank() },
        event = triggerEvent ?: event.orEmpty(),
        runNumber = indexInRepo.count(),
        createdAt = instant(created) ?: Instant.EPOCH,
        actor = triggerUser?.toModel(),
        startedAt = instant(started),
        updatedAt = instant(updated),
        webUrl = htmlUrl,
    )
}

/** Which attempt at its run a job is: the first when Forgejo doesn't say. */
private val ActionRunJob.tries: Long get() = attempt ?: 1

private fun ActionRunJob.toModel(): RunJob {
    val (status, conclusion) = runState(status)
    return RunJob(required(id, "a job's id"), required(name, "a job's name"), status, conclusion, startedAt = null, completedAt = null, steps = emptyList())
}
