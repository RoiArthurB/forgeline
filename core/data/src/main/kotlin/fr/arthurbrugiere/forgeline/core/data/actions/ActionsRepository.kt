package fr.arthurbrugiere.forgeline.core.data.actions

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** CI for the active account. Logs and every write need a signed-in account: without one they fail as Unauthorized. */
interface ActionsRepository {
    suspend fun run(id: RepoId, runId: Long): ForgeResult<WorkflowRun>

    suspend fun jobs(id: RepoId, runId: Long): ForgeResult<List<RunJob>>

    suspend fun job(id: RepoId, jobId: Long): ForgeResult<RunJob>

    suspend fun jobLog(id: RepoId, jobId: Long): ForgeResult<JobLog>

    suspend fun workflows(id: RepoId): ForgeResult<List<Workflow>>

    suspend fun dispatchInputs(id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?>

    suspend fun dispatch(id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit>

    suspend fun rerun(id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit>

    suspend fun cancel(id: RepoId, runId: Long): ForgeResult<Unit>
}

class DefaultActionsRepository @Inject constructor(
    private val api: ActionsApi,
    private val accounts: AccountRepository,
) : ActionsRepository {

    override suspend fun run(id: RepoId, runId: Long) = api.run(token(), id, runId)

    override suspend fun jobs(id: RepoId, runId: Long) = api.jobs(token(), id, runId)

    override suspend fun job(id: RepoId, jobId: Long) = api.job(token(), id, jobId)

    override suspend fun jobLog(id: RepoId, jobId: Long) = signedIn { api.jobLog(it, id, jobId) }

    override suspend fun workflows(id: RepoId) = api.workflows(token(), id)

    override suspend fun dispatchInputs(id: RepoId, workflow: Workflow, ref: String) = api.dispatchInputs(token(), id, workflow, ref)

    override suspend fun dispatch(id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>) =
        signedIn { api.dispatch(it, id, workflow, ref, inputs) }

    override suspend fun rerun(id: RepoId, runId: Long, failedJobsOnly: Boolean) = signedIn { api.rerun(it, id, runId, failedJobsOnly) }

    override suspend fun cancel(id: RepoId, runId: Long) = signedIn { api.cancel(it, id, runId) }

    private suspend fun <T> signedIn(call: suspend (String) -> ForgeResult<T>): ForgeResult<T> =
        token()?.let { call(it) } ?: ForgeResult.Failure(ForgeError.Unauthorized)

    private suspend fun token(): String? = accounts.activeAccount.first()?.let { accounts.token(it.id) }
}
