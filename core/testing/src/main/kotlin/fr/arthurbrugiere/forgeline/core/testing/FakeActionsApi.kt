package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.forge.JobLogReader
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogPart
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import java.io.OutputStream

/** Records every call as "method:owner/name:extra"; unknown runs and jobs answer 404. */
class FakeActionsApi(
    override val supportsRerun: Boolean = true,
    override val logFormat: LogFormat = LogFormat.GITHUB,
) : ActionsApi {
    val runs = mutableMapOf<Long, WorkflowRun>()
    val jobs = mutableMapOf<Long, List<RunJob>>()
    /** Each job's log, as the forge stores it. */
    val logs = mutableMapOf<Long, String>()

    /** Whether logs are served in parts, as GitHub's are; when not, they can only be downloaded whole. */
    var logsInParts = true

    /** How many parts of logs were read, and how many bytes: what reading a step costs. */
    var partsRead = 0
    var bytesRead = 0L
    var workflows: List<Workflow> = emptyList()
    val inputs = mutableMapOf<Long, List<DispatchInput>?>()
    var writeFailure: ForgeError? = null
    val calls = mutableListOf<String>()

    private val notFound = ForgeResult.Failure(ForgeError.Http(404, "Not Found"))

    private fun <T> found(value: T?): ForgeResult<T> = value?.let { ForgeResult.Success(it) } ?: notFound

    private fun write(call: String): ForgeResult<Unit> {
        calls += call
        return writeFailure?.let { ForgeResult.Failure(it) } ?: ForgeResult.Success(Unit)
    }

    override suspend fun run(token: String?, id: RepoId, runId: Long) = found(runs[runId]).also { calls += "run:${id.fullName}:$runId" }

    override suspend fun jobs(token: String?, id: RepoId, runId: Long) = found(jobs[runId]).also { calls += "jobs:${id.fullName}:$runId" }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long) =
        found(jobs[runId]?.lastOrNull { it.id == jobId }).also { calls += "job:${id.fullName}:$runId/$jobId" }

    /** Like GitHub's: without a token, Unauthorized. */
    var logsNeedToken = true

    override suspend fun openJobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLogReader> {
        calls += "openLog:${id.fullName}:$jobId"
        if (token == null && logsNeedToken) return ForgeResult.Failure(ForgeError.Unauthorized)
        val log = logs[jobId]?.toByteArray() ?: return notFound
        if (!logsInParts) return ForgeResult.Failure(ForgeError.Unsupported)
        return ForgeResult.Success(
            JobLogReader(log.size.toLong()) { range ->
                partsRead++
                bytesRead += range.last - range.first + 1
                ForgeResult.Success(LogPart(log.copyOfRange(range.first.toInt(), range.last.toInt() + 1), range.first, log.size.toLong()))
            },
        )
    }

    override suspend fun downloadJobLog(token: String?, id: RepoId, jobId: Long, into: OutputStream): ForgeResult<Unit> {
        calls += "downloadLog:${id.fullName}:$jobId"
        if (token == null && logsNeedToken) return ForgeResult.Failure(ForgeError.Unauthorized)
        val log = logs[jobId] ?: return notFound
        into.write(log.toByteArray())
        bytesRead += log.toByteArray().size
        return ForgeResult.Success(Unit)
    }

    override suspend fun workflows(token: String?, id: RepoId) = ForgeResult.Success(workflows).also { calls += "workflows:${id.fullName}" }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> {
        calls += "inputs:${id.fullName}:${workflow.id}@$ref"
        return ForgeResult.Success(if (workflow.id in inputs) inputs[workflow.id] else emptyList())
    }

    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>) =
        write("dispatch:${id.fullName}:${workflow.id}@$ref:$inputs")

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean) =
        write("rerun:${id.fullName}:$runId" + if (failedJobsOnly) ":failed" else "")

    override suspend fun cancel(token: String, id: RepoId, runId: Long) = write("cancel:${id.fullName}:$runId")
}

fun workflowRun(
    id: Long,
    title: String = "Fix the build",
    status: fr.arthurbrugiere.forgeline.core.model.RunStatus = fr.arthurbrugiere.forgeline.core.model.RunStatus.COMPLETED,
    conclusion: fr.arthurbrugiere.forgeline.core.model.RunConclusion? = fr.arthurbrugiere.forgeline.core.model.RunConclusion.SUCCESS,
) = WorkflowRun(
    id = id,
    workflowName = "CI",
    title = title,
    status = status,
    conclusion = conclusion,
    branch = "main",
    event = "push",
    runNumber = 42,
    createdAt = java.time.Instant.parse("2026-09-29T08:00:00Z"),
    actor = fr.arthurbrugiere.forgeline.core.model.ForgeUser("octocat", null, null),
    workflowId = 1,
    startedAt = java.time.Instant.parse("2026-09-29T08:00:00Z"),
    updatedAt = java.time.Instant.parse("2026-09-29T08:04:30Z"),
)
