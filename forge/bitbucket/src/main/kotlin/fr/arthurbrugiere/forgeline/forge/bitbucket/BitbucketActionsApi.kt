package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.JobLogReader
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.DispatchInputType
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PaginatedPipelineSteps
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Pipeline
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.PipelineStep
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Repository
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.common.writeTo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.OutputStream

/**
 * Bitbucket Pipelines. A run is a pipeline, named by its build number; its jobs are its steps. The pipelines a
 * repository defines are read from its `bitbucket-pipelines.yml`: the one each branch runs, and the custom ones,
 * which are started by hand with the variables they declare. A stopped run can't be run again through the API
 * (Bitbucket's "Rerun" is its web page's), and a step's log comes whole.
 */
class BitbucketActionsApi(
    private val http: HttpClient,
    private val people: BitbucketPeople = BitbucketPeople(),
) : ActionsApi {
    override val supportsRerun: Boolean = false

    // Logs have no marks for their parts: each comes as one step, as GitHub's would without its groups.
    override val logFormat: LogFormat = LogFormat.GITHUB

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = forgeCall {
        http.bitbucket(token, *id.api("pipelines", "$runId")).toResult { body<Pipeline>().toRun(id, people) }
    }

    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = forgeCall {
        steps(token, id, runId).map { steps -> steps.mapIndexed { index, step -> step.toJob(runId, index) } }
    }

    private suspend fun steps(token: String?, id: RepoId, runId: Long): ForgeResult<List<PipelineStep>> =
        http.bitbucket(token, *id.api("pipelines", "$runId", "steps"), query = listOf("pagelen" to "100"))
            .toResult { body<PaginatedPipelineSteps>().propertyValues.orEmpty() }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob> = forgeCall {
        when (val steps = steps(token, id, runId)) {
            is ForgeResult.Failure -> steps
            is ForgeResult.Success -> steps.value.getOrNull(indexOfStep(jobId))?.let { ForgeResult.Success(it.toJob(runId, indexOfStep(jobId))) }
                ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }
    }

    /** A step's log comes whole from Bitbucket: it is downloaded instead. */
    override suspend fun openJobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLogReader> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun downloadJobLog(token: String?, id: RepoId, jobId: Long, into: OutputStream): ForgeResult<Unit> = forgeCall {
        val runId = runOfStep(jobId)
        val step = when (val steps = steps(token, id, runId)) {
            is ForgeResult.Failure -> return@forgeCall steps
            is ForgeResult.Success -> steps.value.getOrNull(indexOfStep(jobId))?.uuid ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(404, null))
        }
        http.bitbucketStatement(token, *id.api("pipelines", "$runId", "steps", step, "log")).execute { answer ->
            if (!answer.status.isSuccess()) return@execute answer.failure()
            answer.bodyAsChannel().writeTo(into)
            ForgeResult.Success(Unit)
        }
    }

    /**
     * The pipelines [id] can be started with: its branches' own ([BRANCH_PIPELINE], run for the branch asked), and
     * each custom one its `bitbucket-pipelines.yml` defines on its main branch.
     */
    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = forgeCall {
        val branch = http.bitbucket(token, *id.api(), query = listOf("fields" to "mainbranch.name")).let { if (it.status.isSuccess()) it.body<Repository>().mainbranch?.name else null }
            ?: return@forgeCall ForgeResult.Success(emptyList())
        val file = http.bitbucket(token, *id.api("src", branch, CONFIG), accept = "*/*")
        // A repository without the file has no pipelines.
        if (!file.status.isSuccess()) return@forgeCall ForgeResult.Success(emptyList())
        val custom = customPipelines(file.bodyAsText()).keys
        ForgeResult.Success(listOf(Workflow(BRANCH_PIPELINE, "Pipelines", CONFIG)) + custom.mapIndexed { index, name -> Workflow(index + 1L, "custom: $name", CONFIG) })
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> = forgeCall {
        val name = customName(workflow) ?: return@forgeCall ForgeResult.Success(emptyList())
        val file = http.bitbucket(token, *id.api("src", ref, CONFIG), accept = "*/*")
        if (!file.status.isSuccess()) return@forgeCall file.failure()
        val steps = customPipelines(file.bodyAsText())[name] ?: return@forgeCall ForgeResult.Success(null)
        ForgeResult.Success(variablesOf(steps))
    }

    /** Starts [workflow] on the branch [ref]: a custom pipeline by its name, with [inputs] as its variables. */
    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> = forgeCall {
        val body = buildJsonObject {
            putJsonObject("target") {
                put("type", "pipeline_ref_target")
                put("ref_type", "branch")
                put("ref_name", ref)
                customName(workflow)?.let { name -> putJsonObject("selector") { put("type", "custom"); put("pattern", name) } }
            }
            if (inputs.isNotEmpty()) {
                put("variables", buildJsonArray { inputs.forEach { (key, value) -> add(buildJsonObject { put("key", key); put("value", value) }) } })
            }
        }
        http.bitbucket(token, *id.api("pipelines"), method = HttpMethod.Post, body = body).toResult { }
    }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> = forgeCall {
        http.bitbucket(token, *id.api("pipelines", "$runId", "stopPipeline"), method = HttpMethod.Post).toResult { }
    }

    private fun customName(workflow: Workflow): String? = workflow.name.removePrefix("custom: ").takeIf { workflow.id != BRANCH_PIPELINE && workflow.name.startsWith("custom: ") }

    companion object {
        const val CONFIG = "bitbucket-pipelines.yml"

        /** The pipeline a branch runs of itself, by the rules its branch matches; started for a branch as a push would. */
        const val BRANCH_PIPELINE = 0L

        /** The custom pipelines of a `bitbucket-pipelines.yml`, by name, each with its steps as written. */
        internal fun customPipelines(yaml: String): Map<String, Any?> {
            val document = runCatching { Load(LoadSettings.builder().build()).loadFromString(yaml) }.getOrNull() as? Map<*, *> ?: return emptyMap()
            val custom = (document["pipelines"] as? Map<*, *>)?.get("custom") as? Map<*, *> ?: return emptyMap()
            return custom.entries.associate { (name, steps) -> name.toString() to steps }
        }

        /**
         * The variables a custom pipeline asks for: its first entry is `- variables:` with each one's `name`, and
         * maybe its `default`, `allowed-values` and `description`.
         */
        internal fun variablesOf(steps: Any?): List<DispatchInput> {
            val first = (steps as? List<*>)?.firstOrNull() as? Map<*, *> ?: return emptyList()
            val variables = first["variables"] as? List<*> ?: return emptyList()
            return variables.mapNotNull { entry ->
                val variable = entry as? Map<*, *> ?: return@mapNotNull null
                val name = variable["name"]?.toString() ?: return@mapNotNull null
                val allowed = (variable["allowed-values"] as? List<*>)?.map { it.toString() }.orEmpty()
                DispatchInput(
                    name = name,
                    description = variable["description"]?.toString(),
                    type = if (allowed.isNotEmpty()) DispatchInputType.CHOICE else DispatchInputType.STRING,
                    // A variable without a default must be given.
                    required = variable["default"] == null,
                    default = variable["default"]?.toString(),
                    options = allowed,
                )
            }
        }
    }
}
