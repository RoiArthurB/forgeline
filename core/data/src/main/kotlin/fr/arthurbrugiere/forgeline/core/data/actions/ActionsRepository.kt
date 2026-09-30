package fr.arthurbrugiere.forgeline.core.data.actions

import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
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

/**
 * CI, with the account signed in to the repository's forge. Every write needs one: without it they fail as Unauthorized.
 * Logs too on GitHub; Forgejo serves public logs to anyone.
 */
interface ActionsRepository {
    suspend fun run(id: RepoId, runId: Long): ForgeResult<WorkflowRun>

    suspend fun jobs(id: RepoId, runId: Long): ForgeResult<List<RunJob>>

    suspend fun job(id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob>

    suspend fun jobLog(id: RepoId, jobId: Long): ForgeResult<JobLog>

    suspend fun workflows(id: RepoId): ForgeResult<List<Workflow>>

    suspend fun dispatchInputs(id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?>

    suspend fun dispatch(id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit>

    suspend fun rerun(id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit>

    suspend fun cancel(id: RepoId, runId: Long): ForgeResult<Unit>

    /** Whether [id]'s forge can start a finished run again. */
    fun supportsRerun(id: RepoId): Boolean
}

class DefaultActionsRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
) : ActionsRepository {

    override suspend fun run(id: RepoId, runId: Long) = on(id) { api, token -> api.run(token, id, runId) }

    override suspend fun jobs(id: RepoId, runId: Long) = on(id) { api, token -> api.jobs(token, id, runId) }

    override suspend fun job(id: RepoId, runId: Long, jobId: Long) = on(id) { api, token -> api.job(token, id, runId, jobId) }

    override suspend fun jobLog(id: RepoId, jobId: Long) = on(id) { api, token -> api.jobLog(token, id, jobId) }

    override suspend fun workflows(id: RepoId) = on(id) { api, token -> api.workflows(token, id) }

    override suspend fun dispatchInputs(id: RepoId, workflow: Workflow, ref: String) = on(id) { api, token -> api.dispatchInputs(token, id, workflow, ref) }

    override suspend fun dispatch(id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>) =
        signedIn(id) { api, token -> api.dispatch(token, id, workflow, ref, inputs) }

    override suspend fun rerun(id: RepoId, runId: Long, failedJobsOnly: Boolean) = signedIn(id) { api, token -> api.rerun(token, id, runId, failedJobsOnly) }

    override suspend fun cancel(id: RepoId, runId: Long) = signedIn(id) { api, token -> api.cancel(token, id, runId) }

    override fun supportsRerun(id: RepoId): Boolean = clients.actions(id.forge)?.supportsRerun == true

    /** [call] with the repository forge's client and token (null signed out); Unsupported when it has no CI API. */
    private suspend fun <T> on(id: RepoId, call: suspend (ActionsApi, String?) -> ForgeResult<T>): ForgeResult<T> {
        val api = clients.actions(id.forge) ?: return ForgeResult.Failure(ForgeError.Unsupported)
        return call(api, accounts.tokenOn(id.forge))
    }

    private suspend fun <T> signedIn(id: RepoId, call: suspend (ActionsApi, String) -> ForgeResult<T>): ForgeResult<T> =
        on(id) { api, token -> token?.let { call(api, it) } ?: ForgeResult.Failure(ForgeError.Unauthorized) }
}
