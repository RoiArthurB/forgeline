package fr.arthurbrugiere.forgeline.forge.forgejo

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

/**
 * Against real gitea.com answers for gitea/cli, captured on 2026-11-09 (repositories and steps trimmed). Gitea's runs
 * and jobs come in GitHub's shape, not Forgejo's. Regression: they were read as Forgejo's, so a run had no title and an
 * unknown state, and a run's jobs didn't read at all.
 */
class GiteaActionsApiTest {
    private val server = Codeberg()
    private val cli = RepoId("gitea", "cli", ForgeInstance.Gitea)

    private fun gitea(name: String) = requireNotNull(javaClass.getResource("/gitea/$name")) { name }.readText()

    private fun api(route: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ForgejoActionsApi(server.client { route(it) }, ForgeInstance.Gitea)

    private fun <T> ForgeResult<T>.value(): T = (this as ForgeResult.Success).value

    @Test
    fun reads_a_run_in_giteas_shape() = runTest {
        val run = with(server) { api { json(gitea("actions_run.json")) } }.run(null, cli, 1217993).value()

        assertThat(run.id).isEqualTo(1217993)
        assertThat(run.runNumber).isEqualTo(564)
        assertThat(run.title).isEqualTo("chore(deps): update dependencies (#107)")
        assertThat(run.workflowName).isEqualTo("release-nightly.yml")
        assertThat(run.status).isEqualTo(RunStatus.COMPLETED)
        assertThat(run.conclusion).isEqualTo(RunConclusion.SUCCESS)
        assertThat(run.branch).isEqualTo("main")
        assertThat(run.event).isEqualTo("push")
        assertThat(run.attempt).isEqualTo(1)
        assertThat(run.actor?.login).isEqualTo("lunny")
        assertThat(run.createdAt).isEqualTo(Instant.parse("2026-11-09T00:29:04Z"))
        assertThat(run.startedAt).isEqualTo(Instant.parse("2026-11-09T00:29:06Z"))
        // Gitea's pages name a run by its id.
        assertThat(run.webUrl).isEqualTo("https://gitea.com/gitea/cli/actions/runs/1217993")
    }

    @Test
    fun a_pull_requests_run_is_on_no_branch() = runTest {
        val run = with(server) { api { json(gitea("actions_run_1217896.json")) } }.run(null, cli, 1217896).value()

        assertThat(run.event).isEqualTo("pull_request")
        assertThat(run.branch).isNull()
        assertThat(run.workflowName).isEqualTo("test-pr.yml")
    }

    @Test
    fun lists_runs_signed_in() = runTest {
        val runs = with(server) { api { json(gitea("actions_runs.json")) } }.runs("token", cli).value()

        assertThat(runs.single().title).isEqualTo("chore(deps): update dependencies (#107)")
        assertThat(server.requests.single().url.parameters["page"]).isEqualTo("1")
    }

    @Test
    fun jobs_come_with_their_steps_and_times() = runTest {
        val jobs = with(server) { api { json(gitea("actions_jobs.json")) } }.jobs(null, cli, 1217993).value()

        assertThat(jobs.map { it.name }).containsExactly("goreleaser", "release-image").inOrder()
        assertThat(jobs.all { it.status == RunStatus.COMPLETED && it.conclusion == RunConclusion.SUCCESS }).isTrue()
        val first = jobs.first()
        assertThat(first.id).isEqualTo(1689460)
        assertThat(first.startedAt).isNotNull()
        assertThat(first.steps.map { it.name }).containsExactly("Set up job", "Run actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1").inOrder()
        assertThat(first.steps.first().conclusion).isEqualTo(RunConclusion.SUCCESS)
    }

    @Test
    fun only_the_latest_attempts_jobs_are_listed() = runTest {
        val jobs = """{"jobs":[{"id":1,"name":"build","status":"completed","conclusion":"failure","run_attempt":1},{"id":2,"name":"build","status":"in_progress","run_attempt":2}],"total_count":2}"""

        val listed = with(server) { api { json(jobs) } }.jobs(null, cli, 9).value()

        assertThat(listed.single().id).isEqualTo(2)
        assertThat(listed.single().status).isEqualTo(RunStatus.IN_PROGRESS)
        assertThat(listed.single().conclusion).isNull()
    }

    @Test
    fun states_map_onto_githubs() {
        assertThat(giteaState("queued", null)).isEqualTo(RunStatus.QUEUED to null)
        assertThat(giteaState("waiting", null)).isEqualTo(RunStatus.QUEUED to null)
        assertThat(giteaState("in_progress", null)).isEqualTo(RunStatus.IN_PROGRESS to null)
        assertThat(giteaState("completed", "failure")).isEqualTo(RunStatus.COMPLETED to RunConclusion.FAILURE)
        assertThat(giteaState("completed", "cancelled")).isEqualTo(RunStatus.COMPLETED to RunConclusion.CANCELLED)
        assertThat(giteaState("completed", "skipped")).isEqualTo(RunStatus.COMPLETED to RunConclusion.SKIPPED)
        assertThat(giteaState("completed", "something new")).isEqualTo(RunStatus.COMPLETED to RunConclusion.OTHER)
        assertThat(giteaState("unknown", null)).isEqualTo(RunStatus.OTHER to null)
    }

    /**
     * Regression: gitea.com asks for a token to list runs (seen 2026-11-09), but still shows each run, and its jobs
     * ("tasks", each with its run's page), to anyone.
     */
    @Test
    fun signed_out_where_the_run_list_is_refused_the_runs_come_from_the_jobs_list() = runTest {
        val runs = with(server) {
            api { request ->
                when (request.url.encodedPath) {
                    "/api/v1/repos/gitea/cli/actions/runs" -> json("""{"message":"token is required"}""", HttpStatusCode.Unauthorized)
                    "/api/v1/repos/gitea/cli/actions/tasks" -> json(gitea("actions_tasks.json"))
                    "/api/v1/repos/gitea/cli/actions/runs/1217993" -> json(gitea("actions_run.json"))
                    "/api/v1/repos/gitea/cli/actions/runs/1217896" -> json(gitea("actions_run_1217896.json"))
                    else -> status(HttpStatusCode.NotFound)
                }
            }
        }.runs(null, cli).value()

        // Five jobs of two runs: each run once, newest first.
        assertThat(runs.map { it.id }).containsExactly(1217993L, 1217896L).inOrder()
        assertThat(runs.map { it.runNumber }).containsExactly(564, 563).inOrder()
        assertThat(server.requests.count { it.url.encodedPath.endsWith("/actions/runs/1217993") }).isEqualTo(1)
        assertThat(server.requests.all { it.headers["Authorization"] == null }).isTrue()
    }

    @Test
    fun signed_in_a_refused_run_list_is_still_a_failure() = runTest {
        val result = with(server) { api { json("""{"message":"token is required"}""", HttpStatusCode.Unauthorized) } }.runs("token", cli)

        assertThat(result).isEqualTo(ForgeResult.Failure(ForgeError.Unauthorized))
        assertThat(server.requests).hasSize(1)
    }

    @Test
    fun forgejo_signed_out_is_never_asked_for_its_jobs_list() = runTest {
        val website = RepoId("forgejo", "website", ForgeInstance.Codeberg)
        val forgejo = ForgejoActionsApi(server.client { with(server) { json("""{"message":"no"}""", HttpStatusCode.Unauthorized) } }, ForgeInstance.Codeberg)

        assertThat(forgejo.runs(null, website)).isInstanceOf(ForgeResult.Failure::class.java)
        assertThat(server.requests).hasSize(1)
    }
}
