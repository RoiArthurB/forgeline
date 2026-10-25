package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.ArtifactDocument
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunArtifact
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import java.io.OutputStream

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

    /** How this forge's job logs mark their steps. */
    val logFormat: LogFormat

    /**
     * Opens a finished job's log to be read in parts ([JobLogReader]), a step without the rest. Unsupported when the
     * forge can only send it whole (the answer is left unread): then [downloadJobLog].
     *
     * GitHub serves logs to signed-in users only, even on public repositories (Unauthorized without a token); Forgejo
     * serves public ones to anyone.
     */
    suspend fun openJobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLogReader>

    /** A finished job's whole log, written to [into] as it comes rather than held: logs run to tens of megabytes. */
    suspend fun downloadJobLog(token: String?, id: RepoId, jobId: Long, into: OutputStream): ForgeResult<Unit>

    /** The files [runId] kept, to be had once it's done; none where the forge can't list them. */
    suspend fun artifacts(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunArtifact>> = ForgeResult.Success(emptyList())

    /**
     * The Markdown files in [artifact]'s archive ([ArtifactArchive]): the report a workflow wrote, where the forge can't
     * give the run's summary. Unsupported where it can't hand an archive over.
     */
    suspend fun artifactDocuments(token: String?, id: RepoId, artifact: RunArtifact): ForgeResult<List<ArtifactDocument>> =
        ForgeResult.Failure(ForgeError.Unsupported)

    /** Active workflows defined in the repository. */
    suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>>

    /** What [workflow] asks for when started by hand at [ref]; null when it can't be started by hand. */
    suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?>

    suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit>

    suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit>

    suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit>
}
