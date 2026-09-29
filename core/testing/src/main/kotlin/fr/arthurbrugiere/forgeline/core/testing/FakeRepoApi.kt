package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun

/** Records every call as "method:owner/name[:extra]" and the token used. */
class FakeRepoApi : RepoApi {
    val details = mutableMapOf<RepoId, RepoDetails>()
    val readmes = mutableMapOf<RepoId, Readme?>()
    val directories = mutableMapOf<Pair<RepoId, String>, List<RepoFile>>()
    val files = mutableMapOf<Pair<RepoId, String>, String>()
    var issues: List<IssueSummary> = emptyList()
    var pulls: List<IssueSummary> = emptyList()
    var releases: List<Release> = emptyList()
    var runs: List<WorkflowRun> = emptyList()
    var refs = GitRefs(listOf("main"), emptyList())
    var failure: ForgeError? = null
    val calls = mutableListOf<String>()
    val tokens = mutableListOf<String?>()

    private fun <T> answer(call: String, token: String?, value: () -> T?): ForgeResult<T> {
        calls += call
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        @Suppress("UNCHECKED_CAST")
        return value()?.let { ForgeResult.Success(it) } ?: ForgeResult.Success(null as T)
    }

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> {
        calls += "repo:${id.fullName}"
        tokens += token
        failure?.let { return ForgeResult.Failure(it) }
        return details[id]?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?) =
        answer<Readme?>("readme:${id.fullName}" + (ref?.let { "@$it" } ?: ""), token) { readmes[id] }

    override suspend fun refs(token: String?, id: RepoId) = answer("refs:${id.fullName}", token) { refs }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> =
        answer("contents:${id.fullName}:$path@$ref", token) { directories[id to path] ?: emptyList() }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> =
        answer("file:${id.fullName}:$path@$ref", token) { files[id to path] ?: "" }

    override suspend fun openIssues(token: String?, id: RepoId) = answer("issues:${id.fullName}", token) { issues }

    override suspend fun openPullRequests(token: String?, id: RepoId) = answer("pulls:${id.fullName}", token) { pulls }

    override suspend fun releases(token: String?, id: RepoId) = answer("releases:${id.fullName}", token) { releases }

    override suspend fun workflowRuns(token: String?, id: RepoId) = answer("runs:${id.fullName}", token) { runs }

    override fun rawBaseUrl(id: RepoId, ref: String) = "https://raw.example/${id.fullName}/$ref/"

    override fun blobBaseUrl(id: RepoId, ref: String) = "https://blob.example/${id.fullName}/$ref/"
}

fun repoDetails(fullName: String, defaultBranch: String = "main", stars: Int = 100): RepoDetails {
    val (owner, name) = fullName.split('/')
    return RepoDetails(
        id = RepoId(owner, name), description = "About $fullName", homepage = null, topics = listOf("kotlin"),
        stars = stars, forks = 3, watchers = 2, language = "Kotlin", license = "MIT", defaultBranch = defaultBranch,
        ownerAvatarUrl = null, isFork = false, isArchived = false, pushedAt = null,
    )
}

fun issueSummary(number: Int, title: String, isPullRequest: Boolean = false) = fr.arthurbrugiere.forgeline.core.model.IssueSummary(
    number = number,
    title = title,
    state = fr.arthurbrugiere.forgeline.core.model.IssueState.OPEN,
    author = fr.arthurbrugiere.forgeline.core.model.ForgeUser("octocat", null, null),
    comments = if (isPullRequest) null else 2,
    createdAt = java.time.Instant.parse("2026-09-26T08:00:00Z"),
    labels = listOf(fr.arthurbrugiere.forgeline.core.model.Label("bug", "d73a4a")),
    isPullRequest = isPullRequest,
    isDraft = false,
)
