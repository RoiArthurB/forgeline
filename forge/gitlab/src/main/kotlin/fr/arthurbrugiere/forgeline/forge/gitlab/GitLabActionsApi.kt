package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ActionsLogParser
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.DispatchInput
import fr.arthurbrugiere.forgeline.core.model.JobLog
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.Workflow
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GitLabActionsApi(private val httpClient: HttpClient) : ActionsApi {

    override val supportsRerun: Boolean = true

    private fun projectPath(id: RepoId): String = encodePath(id.fullName)

    override suspend fun run(token: String?, id: RepoId, runId: Long): ForgeResult<WorkflowRun> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "pipelines", runId.toString())
            .toResult { body<GitLabPipelineJson>().toRun() }
    }

    override suspend fun jobs(token: String?, id: RepoId, runId: Long): ForgeResult<List<RunJob>> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "pipelines", runId.toString(), "jobs", query = mapOf("per_page" to "100"))
            .toResult { body<List<GitLabJobJson>>().map { it.toRunJob() } }
    }

    override suspend fun job(token: String?, id: RepoId, runId: Long, jobId: Long): ForgeResult<RunJob> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "jobs", jobId.toString())
            .toResult { body<GitLabJobJson>().toRunJob() }
    }

    override suspend fun jobLog(token: String?, id: RepoId, jobId: Long): ForgeResult<JobLog> = gitlabCall {
        val response = httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "jobs", jobId.toString(), "trace")
        if (!response.status.isSuccess()) return@gitlabCall response.failure()
        val text = response.bodyAsText()
        ForgeResult.Success(withContext(Dispatchers.Default) { ActionsLogParser.parse(text) })
    }

    override suspend fun workflows(token: String?, id: RepoId): ForgeResult<List<Workflow>> = gitlabCall {
        val response = httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "repository", "files", encodePath(".gitlab-ci.yml"), query = mapOf("ref" to "HEAD"))
        if (response.status == HttpStatusCode.NotFound) return@gitlabCall ForgeResult.Success(emptyList())
        if (!response.status.isSuccess()) return@gitlabCall response.failure()
        ForgeResult.Success(listOf(Workflow(id = 1L, name = "GitLab CI", path = ".gitlab-ci.yml")))
    }

    override suspend fun dispatchInputs(token: String?, id: RepoId, workflow: Workflow, ref: String): ForgeResult<List<DispatchInput>?> =
        ForgeResult.Success(emptyList())

    override suspend fun dispatch(token: String, id: RepoId, workflow: Workflow, ref: String, inputs: Map<String, String>): ForgeResult<Unit> = gitlabCall {
        val payload = buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) {
                put("variables", buildJsonArray {
                    inputs.forEach { (key, value) ->
                        add(buildJsonObject {
                            put("key", key)
                            put("value", value)
                        })
                    }
                })
            }
        }
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "pipeline", method = HttpMethod.Post, body = payload)
            .toResult { }
    }

    override suspend fun rerun(token: String, id: RepoId, runId: Long, failedJobsOnly: Boolean): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "pipelines", runId.toString(), "retry", method = HttpMethod.Post)
            .toResult { }
    }

    override suspend fun cancel(token: String, id: RepoId, runId: Long): ForgeResult<Unit> = gitlabCall {
        httpClient.gitlabApi(id.forge, token, "projects", projectPath(id), "pipelines", runId.toString(), "cancel", method = HttpMethod.Post)
            .toResult { }
    }
}
