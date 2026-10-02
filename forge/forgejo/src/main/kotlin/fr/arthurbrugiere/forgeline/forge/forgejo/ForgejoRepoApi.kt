package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

class ForgejoRepoApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : RepoApi {

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = forgejoCall {
        get(token, id).toResult { body<RepoJson>().toDetails(forge) }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgejoCall {
        // Forgejo has no README endpoint (404 on Codeberg, 2026-09-29): find it among the root files.
        val listing = get(token, id, "contents", query = ref?.let { mapOf("ref" to it) } ?: emptyMap())
        if (listing.status == HttpStatusCode.NotFound) return@forgejoCall ForgeResult.Success(null)
        if (listing.status != HttpStatusCode.OK) return@forgejoCall listing.failure()
        val file = readmeOf(listing.body<List<ContentJson>>()) ?: return@forgejoCall ForgeResult.Success(null)
        val text = raw(token, id, file.path, ref)
        if (text.status != HttpStatusCode.OK) return@forgejoCall text.failure()
        ForgeResult.Success(Readme(file.path, text.bodyAsText()))
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgejoCall {
        // git/refs answers every ref at once, where /branches and /tags page by 50 (checked on Codeberg).
        val names = listOf("heads", "tags").map { kind ->
            val response = get(token, id, "git", "refs", kind)
            when (response.status) {
                HttpStatusCode.OK -> response.body<List<GitRefJson>>().map { it.ref.removePrefix("refs/$kind/") }
                // An empty repository has no refs yet.
                HttpStatusCode.NotFound, HttpStatusCode.Conflict -> emptyList()
                else -> return@forgejoCall response.failure()
            }
        }
        ForgeResult.Success(GitRefs(names[0].sortedWith(String.CASE_INSENSITIVE_ORDER), names[1].sortedWith(VersionOrder.reversed())))
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = forgejoCall {
        get(token, id, "contents", *path.segments(), query = mapOf("ref" to ref)).toResult {
            body<List<ContentJson>>()
                .map { RepoFile(it.path, it.name, it.type.toFileType(), it.size) }
                .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgejoCall {
        raw(token, id, path, ref).toResult { bodyAsText() }
    }

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgejoCall { list(token, id, "issues", query) }

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgejoCall {
        // The pulls endpoint can't look for words: those go through the issue list, which lists pull requests too.
        if (query.text.isNotBlank()) return@forgejoCall list(token, id, "pulls", query)
        get(token, id, "pulls", query = mapOf("state" to query.state, "sort" to "newest", "limit" to "30"))
            .toResult { body<List<IssueJson>>().map { it.toSummary() } }
    }

    private suspend fun list(token: String?, id: RepoId, type: String, query: IssueQuery): ForgeResult<List<IssueSummary>> {
        val words = query.text.trim()
        val parameters = mapOf("state" to query.state, "type" to type, "limit" to "30") + if (words.isEmpty()) emptyMap() else mapOf("q" to words)
        return get(token, id, "issues", query = parameters).toResult { body<List<IssueJson>>().map { it.toSummary() } }
    }

    private val IssueQuery.state get() = if (open) "open" else "closed"

    /** Forgejo pins pull requests apart: only the issues are asked for. */
    override suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> = forgejoCall {
        get(token, id, "issues", "pinned").toResult { body<List<IssueJson>>().filterNot { it.isPullRequest }.map { it.toSummary() } }
    }

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgejoCall {
        get(token, id, "releases", query = mapOf("limit" to "30"))
            .toResult { body<List<ReleaseJson>>().filterNot { it.draft }.map { it.toModel() } }
    }

    // Forgejo Actions come with Codeberg sign-in: its run endpoints answer 404 without a token.
    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = ForgejoActionsApi(httpClient, forge).runs(token, id)

    override fun rawBaseUrl(id: RepoId, ref: String): String = id.rawBaseUrl(ref)

    override fun blobBaseUrl(id: RepoId, ref: String): String = id.blobBaseUrl(ref)

    private suspend fun get(token: String?, id: RepoId, vararg segments: String, query: Map<String, String> = emptyMap()): HttpResponse =
        httpClient.forgejoApi(forge, token, "repos", id.owner, id.name, *segments, method = HttpMethod.Get, query = query)

    private suspend fun raw(token: String?, id: RepoId, path: String, ref: String?): HttpResponse =
        get(token, id, "raw", *path.segments(), query = ref?.let { mapOf("ref" to it) } ?: emptyMap())

    private fun String.segments(): Array<String> = split('/').filter { it.isNotEmpty() }.toTypedArray()

    private fun String.toFileType() = when (this) {
        "dir" -> RepoFileType.DIR
        "symlink" -> RepoFileType.SYMLINK
        "submodule" -> RepoFileType.SUBMODULE
        else -> RepoFileType.FILE
    }

    private companion object {
        /** The README a forge shows: README.md first, then any README.* or README. */
        fun readmeOf(files: List<ContentJson>): ContentJson? {
            val candidates = files.filter { it.type == "file" && it.name.substringBefore('.').equals("readme", ignoreCase = true) }
            return candidates.firstOrNull { it.name.equals("README.md", ignoreCase = true) } ?: candidates.minByOrNull { it.name.length }
        }
    }
}
