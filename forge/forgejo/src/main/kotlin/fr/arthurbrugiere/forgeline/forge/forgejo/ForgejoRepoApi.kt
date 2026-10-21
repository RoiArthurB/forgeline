package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
import fr.arthurbrugiere.forgeline.core.forge.map
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
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.languageShares
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.model.withLatest
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode

class ForgejoRepoApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : RepoApi {

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = repoAndRights(token, id).map { it.first }

    override suspend fun repoAndRights(token: String?, id: RepoId): ForgeResult<Pair<RepoDetails, RepoRights?>> = forgeCall {
        get(token, id).toResult {
            val answer = bodyAsText()
            ForgeJson.decodeFromString<RepoJson>(answer).toDetails(forge) to token?.let { forgejoRights(answer) }
        }
    }

    /** Bytes of code by language, as GitHub's API gives them. */
    override suspend fun languages(token: String?, id: RepoId): ForgeResult<List<RepoLanguage>> = forgeCall {
        get(token, id, "languages").toResult { languageShares(body<Map<String, Double>>()) }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgeCall {
        // Forgejo has no README endpoint (404 on Codeberg, 2026-09-29): find it among the root files.
        val root = when (val tree = httpClient.forgejoTree(forge, token, id, ref ?: "HEAD", "")) {
            is ForgeResult.Failure -> return@forgeCall tree
            is ForgeResult.Success -> tree.value ?: return@forgeCall ForgeResult.Success(null)
        }
        val path = readmeOf(root.filter { it.type == "blob" }.map { it.path }) ?: return@forgeCall ForgeResult.Success(null)
        val text = raw(token, id, path, ref)
        if (text.status != HttpStatusCode.OK) return@forgeCall text.failure()
        ForgeResult.Success(Readme(path, text.bodyAsText()))
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        // git/refs answers every ref at once, where /branches and /tags page by 50 (checked on Codeberg).
        val names = listOf("heads", "tags").map { kind ->
            val response = get(token, id, "git", "refs", kind)
            when (response.status) {
                HttpStatusCode.OK -> response.body<List<GitRefJson>>().map { it.ref.removePrefix("refs/$kind/") }
                // An empty repository has no refs yet.
                HttpStatusCode.NotFound, HttpStatusCode.Conflict -> emptyList()
                else -> return@forgeCall response.failure()
            }
        }
        ForgeResult.Success(GitRefs(names[0].sortedWith(String.CASE_INSENSITIVE_ORDER), names[1].sortedWith(VersionOrder.reversed())))
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> = forgeCall {
        val entries = when (val tree = httpClient.forgejoTree(forge, token, id, ref, path)) {
            is ForgeResult.Failure -> return@forgeCall tree
            is ForgeResult.Success -> tree.value ?: return@forgeCall ForgeResult.Failure(ForgeError.Http(HttpStatusCode.NotFound.value, "Not Found"))
        }
        val folder = path.trim('/')
        ForgeResult.Success(
            entries
                .map { RepoFile(if (folder.isEmpty()) it.path else "$folder/${it.path}", it.path, it.toFileType(), it.size) }
                .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
        )
    }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall {
        raw(token, id, path, ref).toResult { bodyAsText() }
    }

    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall { list(token, id, "issues", query) }

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        // The pulls endpoint can't look for words: those go through the issue list, which lists pull requests too.
        if (query.text.isNotBlank()) return@forgeCall list(token, id, "pulls", query)
        get(token, id, "pulls", query = mapOf("state" to query.state, "sort" to "newest", "limit" to "${IssueQuery.PAGE_SIZE}", "page" to "${query.page}"), patienceMillis = PULLS_PATIENCE_MILLIS)
            .toResult { body<List<IssueJson>>().map { it.toSummary() } }
    }

    private suspend fun list(token: String?, id: RepoId, type: String, query: IssueQuery): ForgeResult<List<IssueSummary>> {
        val words = query.text.trim()
        val parameters = mapOf("state" to query.state, "type" to type, "limit" to "${IssueQuery.PAGE_SIZE}", "page" to "${query.page}") + if (words.isEmpty()) emptyMap() else mapOf("q" to words)
        return get(token, id, "issues", query = parameters).toResult { body<List<IssueJson>>().map { it.toSummary() } }
    }

    private val IssueQuery.state get() = if (open) "open" else "closed"

    /** Forgejo pins pull requests apart: only the issues are asked for. */
    override suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> = forgeCall {
        get(token, id, "issues", "pinned").toResult { body<List<IssueJson>>().filterNot { it.isPullRequest }.map { it.toSummary() } }
    }

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgeCall {
        get(token, id, "releases", query = mapOf("limit" to "30"))
            .toResult { body<List<ReleaseJson>>().filterNot { it.draft }.map { it.toModel() }.withLatest() }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = forgeCall {
        // A tag may hold slashes: each part is its own path segment.
        get(token, id, "releases", "tags", *tag.split('/').filter { it.isNotEmpty() }.toTypedArray()).toResult { body<ReleaseJson>().toModel() }
    }

    // Forgejo Actions come with Codeberg sign-in: its run endpoints answer 404 without a token.
    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = ForgejoActionsApi(httpClient, forge).runs(token, id)

    override fun rawBaseUrl(id: RepoId, ref: String): String = id.rawBaseUrl(ref)

    override fun blobBaseUrl(id: RepoId, ref: String): String = id.blobBaseUrl(ref)

    private suspend fun get(token: String?, id: RepoId, vararg segments: String, query: Map<String, String> = emptyMap(), patienceMillis: Long? = null): HttpResponse =
        httpClient.forgejoApi(forge, token, "repos", id.owner, id.name, *segments, method = HttpMethod.Get, query = query, patienceMillis = patienceMillis)

    private suspend fun raw(token: String?, id: RepoId, path: String, ref: String?): HttpResponse =
        get(token, id, "raw", *path.segments(), query = ref?.let { mapOf("ref" to it) } ?: emptyMap())

    private fun String.segments(): Array<String> = split('/').filter { it.isNotEmpty() }.toTypedArray()

    private fun TreeJson.Entry.toFileType() = when {
        type == "tree" -> RepoFileType.DIR
        type == "commit" -> RepoFileType.SUBMODULE
        mode == "120000" -> RepoFileType.SYMLINK
        else -> RepoFileType.FILE
    }

    private companion object {
        // Codeberg takes 6 to 10 s to list forgejo/forgejo's open pull requests (measured 2026-10-20), and longer from
        // further away: past the 10 s any other call is given. Its issue list answers in 1 to 3 s.
        const val PULLS_PATIENCE_MILLIS = 30_000L

        /** The README a forge shows: README.md first, then any README.* or README. */
        fun readmeOf(files: List<String>): String? {
            val candidates = files.filter { it.substringBefore('.').equals("readme", ignoreCase = true) }
            return candidates.firstOrNull { it.equals("README.md", ignoreCase = true) } ?: candidates.minByOrNull { it.length }
        }
    }
}
