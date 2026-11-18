package fr.arthurbrugiere.forgeline.forge.bitbucket

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Pipeline 3201 of atlassian/atlassian-connect-express and its steps, recorded on 2026-11-18. */
class BitbucketActionsApiTest {
    private val mock = BitbucketMock()
    private val api = BitbucketActionsApi(mock.http)

    private val pipelines = """
        image: node:20
        pipelines:
          default:
            - step:
                script: [npm test]
          custom:
            release:
              - variables:
                  - name: VERSION
                  - name: CHANNEL
                    default: beta
                    allowed-values: [beta, stable]
                    description: Where it goes
              - step:
                  script: [npm publish]
            nightly:
              - step:
                  script: [npm run nightly]
    """.trimIndent()

    @Test
    fun a_pipeline_is_a_run_named_by_its_build_number() = runTest {
        mock.get("$ACE/pipelines/3201", fixture("pipeline.json"))

        val run = api.run(null, ace, 3201).value()

        assertThat(run.id).isEqualTo(3201)
        assertThat(run.runNumber).isEqualTo(3201)
        assertThat(run.status).isEqualTo(RunStatus.COMPLETED)
        assertThat(run.conclusion).isEqualTo(RunConclusion.SUCCESS)
        assertThat(run.workflowName).isEqualTo("custom: trust-greenlight-pipeline")
        assertThat(run.branch).isEqualTo("renovate/prettier-3.x")
        assertThat(run.actor?.login).isEqualTo("atlassian-renovate-bot")
        assertThat(run.webUrl).isEqualTo("https://bitbucket.org/atlassian/atlassian-connect-express/pipelines/results/3201")
    }

    @Test
    fun its_steps_are_its_jobs_each_found_again_by_its_id() = runTest {
        mock.get("$ACE/pipelines/3201/steps", fixture("steps.json"))

        val jobs = api.jobs(null, ace, 3201).value()

        assertThat(jobs.map { it.id }).containsExactly(3201000L, 3201001L, 3201002L).inOrder()
        assertThat(jobs[0].name).isEqualTo("Artifactory Bypass Scanner")
        assertThat(jobs[0].conclusion).isEqualTo(RunConclusion.FAILURE)
        assertThat(api.job(null, ace, 3201, 3201001L).value().name).isEqualTo("Scanning for OPTIONAL ASAP authentication")
    }

    @Test
    fun a_step_s_log_is_downloaded_whole() = runTest {
        mock.get("$ACE/pipelines/3201/steps", fixture("steps.json"))
        mock.on(HttpMethod.Get, "$ACE/pipelines/3201/steps/%7B3000e771-2cdd-418d-b16c-aa904b9432a6%7D/log") { respond("+ npm test\nok\n") }
        val log = ByteArrayOutputStream()

        api.downloadJobLog(null, ace, 3201001L, log).value()

        assertThat(log.toString()).isEqualTo("+ npm test\nok\n")
        assertThat(api.openJobLog(null, ace, 3201001L)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }

    @Test
    fun the_pipelines_to_start_are_the_branch_s_own_and_each_custom_one() = runTest {
        mock.get(ACE, """{"mainbranch":{"name":"master"}}""")
        mock.on(HttpMethod.Get, "$ACE/src/master/bitbucket-pipelines.yml") { respond(pipelines) }

        val workflows = api.workflows(null, ace).value()

        assertThat(workflows.map { it.name }).containsExactly("Pipelines", "custom: release", "custom: nightly").inOrder()
        val inputs = api.dispatchInputs(null, ace, workflows[1], "master").value()!!
        assertThat(inputs.map { it.name }).containsExactly("VERSION", "CHANNEL").inOrder()
        assertThat(inputs[0].required).isTrue()
        assertThat(inputs[1].type).isEqualTo(DispatchInputType.CHOICE)
        assertThat(inputs[1].options).containsExactly("beta", "stable").inOrder()
        assertThat(inputs[1].default).isEqualTo("beta")
        assertThat(api.dispatchInputs(null, ace, workflows[0], "master").value()).isEmpty()
    }

    @Test
    fun a_repository_without_pipelines_has_none_to_start() = runTest {
        mock.get(ACE, """{"mainbranch":{"name":"master"}}""")

        assertThat(api.workflows(null, ace).value()).isEmpty()
    }

    @Test
    fun a_custom_pipeline_starts_on_a_branch_with_its_variables() = runTest {
        mock.write(HttpMethod.Post, "$ACE/pipelines", "{}", HttpStatusCode.Created)
        val release = fr.arthurbrugiere.forgeline.core.model.Workflow(1, "custom: release", BitbucketActionsApi.CONFIG)

        api.dispatch(TOKEN, ace, release, "master", mapOf("VERSION" to "1.2.0")).value()

        assertThat(mock.sent(HttpMethod.Post, "$ACE/pipelines").toString()).isEqualTo(
            """{"target":{"type":"pipeline_ref_target","ref_type":"branch","ref_name":"master","selector":{"type":"custom","pattern":"release"}},"variables":[{"key":"VERSION","value":"1.2.0"}]}""",
        )
    }

    @Test
    fun the_branch_s_own_pipeline_starts_without_a_selector() = runTest {
        mock.write(HttpMethod.Post, "$ACE/pipelines", "{}", HttpStatusCode.Created)

        api.dispatch(TOKEN, ace, fr.arthurbrugiere.forgeline.core.model.Workflow(BitbucketActionsApi.BRANCH_PIPELINE, "Pipelines", BitbucketActionsApi.CONFIG), "develop", emptyMap()).value()

        assertThat(mock.sent(HttpMethod.Post, "$ACE/pipelines").toString()).isEqualTo("""{"target":{"type":"pipeline_ref_target","ref_type":"branch","ref_name":"develop"}}""")
    }

    @Test
    fun a_run_is_stopped_and_never_rerun_through_the_api() = runTest {
        mock.write(HttpMethod.Post, "$ACE/pipelines/3201/stopPipeline", "", HttpStatusCode.NoContent)

        api.cancel(TOKEN, ace, 3201).value()

        assertThat(mock.writes).containsExactly("POST $ACE/pipelines/3201/stopPipeline")
        assertThat(api.supportsRerun).isFalse()
        assertThat(api.rerun(TOKEN, ace, 3201, failedJobsOnly = false)).isEqualTo(ForgeResult.Failure(ForgeError.Unsupported))
    }
}
