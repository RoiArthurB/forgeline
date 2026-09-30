package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun

/** A repository's CI: runs, their jobs and logs, and starting or stopping them. Writes need a token. */
interface ActionsApi {
    /** Whether finished runs can be started again; Forgejo's API has no re-run. */
    val supportsRerun: Boolean get() = true

    suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun>

    /** The latest attempt's jobs, in the order the forge lists them. */
    suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>>

    /**
     * One job of run [runId], with its steps when the forge tells them: while it runs, the only live view GitHub
     * offers. Forgejo's API doesn't tell steps.
     */
    suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob>

    /**
     * A finished job's log. GitHub serves logs to signed-in users only, even on public repositories (Unauthorized
     * without a token); Forgejo serves public ones to anyone.
     */
    suspend fun jobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLog>

    /** Active workflows defined in the repository. */
    suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>>

    /** What [workflow] asks for when started by hand at [ref]; null when it can't be started by hand. */
    suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?>

    suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit>

    suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit>

    suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit>
}
