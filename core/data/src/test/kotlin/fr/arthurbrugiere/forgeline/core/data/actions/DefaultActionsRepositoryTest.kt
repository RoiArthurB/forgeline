package fr.arthurbrugiere.forgeline.core.data.actions

import fr.arthurbrugiere.forgeline.core.testing.FakeForgeClients
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.testing.FakeAccountRepository
import fr.arthurbrugiere.forgeline.core.testing.FakeActionsApi
import fr.arthurbrugiere.forgeline.core.testing.workflowRun
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultActionsRepositoryTest {
    private val api = FakeActionsApi()
    private val accounts = FakeAccountRepository()
    private val repository = DefaultActionsRepository(FakeForgeClients(actions = api), accounts)
    private val repo = RepoId("octo", "repo")

    @Test
    fun runs_and_jobs_are_readable_signed_out() = runTest {
        api.runs[7] = workflowRun(7)

        assertThat(repository.run(repo, 7)).isEqualTo(ForgeResult.Success(workflowRun(7)))
    }

    @Test
    fun signed_out_logs_and_writes_fail_without_reaching_the_forge() = runTest {
        api.logs[1] = JobLog(emptyList())

        assertThat(repository.jobLog(repo, 1)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.rerun(repo, 7, failedJobsOnly = true)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.cancel(repo, 7)).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(repository.dispatch(repo, Workflow(1, "CI", ".github/workflows/ci.yml"), "main", emptyMap()))
            .isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun signed_in_logs_and_writes_reach_the_forge() = runTest {
        accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", null, null), "ghp_token")
        api.logs[1] = JobLog(emptyList())

        assertThat(repository.jobLog(repo, 1)).isEqualTo(ForgeResult.Success(JobLog(emptyList())))
        repository.rerun(repo, 7, failedJobsOnly = true)

        assertThat(api.calls).containsExactly("log:octo/repo:1", "rerun:octo/repo:7:failed").inOrder()
    }

    @Test
    fun a_forge_without_actions_says_so() = runTest {
        val repository = DefaultActionsRepository(FakeForgeClients().apply { put(ForgeInstance.Codeberg, FakeForgeClients(actions = null)) }, accounts)

        assertThat(repository.run(RepoId("forgejo", "forgejo", ForgeInstance.Codeberg), 7)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }
}
