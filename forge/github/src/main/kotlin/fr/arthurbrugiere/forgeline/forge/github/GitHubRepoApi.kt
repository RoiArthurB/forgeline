package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Base64

class GitHubRepoApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : RepoApi {

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = gitHubCall {
        get(token, "repos", id.owner, id.name).toResult { body<RepoResponse>().toModel() }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = gitHubCall {
        val response = get(token, "repos", id.owner, id.name, "readme", query = ref?.let { mapOf("ref" to it) } ?: emptyMap())
        if (response.status == HttpStatusCode.NotFound) return@gitHubCall ForgeResult.Success(null)
        response.toResult {
            val file = body<ContentResponse>()
            Readme(path = file.path, markdown = file.decodedText() ?: "")
        }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = gitHubCall {
        // matching-refs answers every ref at once, where /branches and /tags stop at 100 a page.
        val names = listOf("heads", "tags").map { kind ->
            val response = get(token, "repos", id.owner, id.name, "git", "matching-refs", kind)
            when (response.status) {
                HttpStatusCode.OK -> response.body<List<GitRefResponse>>().map { it.ref.removePrefix("refs/$kind/") }
                // An empty repository has no git database yet.
                HttpStatusCode.Conflict -> emptyList()
                else -> return@gitHubCall response.failure()
            }
        }
        ForgeResult.Success(
            GitRefs(
                branches = names[0].sortedWith(String.CASE_INSENSITIVE_ORDER),
                tags = names[1].sortedWith(VersionOrder.reversed()),
            ),
        )
    }

    override suspend fun contents(token: String?, id: RepoId, path: String, ref: String): ForgeResult<List<RepoFile>> =
        gitHubCall {
            get(token, "repos", id.owner, id.name, "contents", *path.segments(), query = mapOf("ref" to ref)).toResult {
                body<List<ContentResponse>>()
                    .map { it.toFile() }
                    .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = gitHubCall {
        val response = get(token, "repos", id.owner, id.name, "contents", *path.segments(), query = mapOf("ref" to ref))
        if (response.status != HttpStatusCode.OK) return@gitHubCall response.failure()
        // Files over 1 MB come back without content: the contents API can't serve them.
        response.body<ContentResponse>().decodedText()?.let { ForgeResult.Success(it) }
            ?: ForgeResult.Failure(ForgeError.Http(413, "File too large to preview"))
    }

    override suspend fun openIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> = gitHubCall {
        // Search, not /issues: that endpoint mixes in pull requests, which crowd issues out on busy repos.
        get(
            token, "search", "issues",
            query = mapOf("q" to "repo:${id.fullName} is:issue is:open", "sort" to "created", "order" to "desc", "per_page" to "30"),
        ).toResult { body<SearchResponse>().items.map { it.toModel(isPullRequest = false) } }
    }

    override suspend fun openPullRequests(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> = gitHubCall {
        get(token, "repos", id.owner, id.name, "pulls", query = mapOf("state" to "open", "per_page" to "30"))
            .toResult { body<List<IssueResponse>>().map { it.toModel(isPullRequest = true) } }
    }

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = gitHubCall {
        get(token, "repos", id.owner, id.name, "releases", query = mapOf("per_page" to "30"))
            .toResult { body<List<ReleaseResponse>>().filterNot { it.draft }.map { it.toModel() } }
    }

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = gitHubCall {
        get(token, "repos", id.owner, id.name, "actions", "runs", query = mapOf("per_page" to "30"))
            .toResult { body<RunsResponse>().workflowRuns.map { it.toModel() } }
    }

    override fun rawBaseUrl(id: RepoId, ref: String): String = "https://raw.githubusercontent.com/${id.fullName}/$ref/"

    override fun blobBaseUrl(id: RepoId, ref: String): String = "https://github.com/${id.fullName}/blob/$ref/"

    private suspend fun get(token: String?, vararg segments: String, query: Map<String, String> = emptyMap()): HttpResponse =
        httpClient.gitHubApi(apiBaseUrl, token, *segments, query = query)

    private fun String.segments(): Array<String> = split('/').filter { it.isNotEmpty() }.toTypedArray()
}

/** Compares runs of digits as numbers, so v1.10 comes after v1.9. */
private object VersionOrder : Comparator<String> {
    private val chunks = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(a).map { it.value }.toList()
        val right = chunks.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val order = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }
}

@Serializable
private data class GitRefResponse(val ref: String)

@Serializable
private data class Owner(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null) {
    fun toModel() = ForgeUser(login = login, name = null, avatarUrl = avatarUrl)
}

@Serializable
private data class LicenseResponse(val name: String? = null, @SerialName("spdx_id") val spdxId: String? = null)

@Serializable
private data class RepoResponse(
    val name: String,
    val owner: Owner,
    val description: String? = null,
    val homepage: String? = null,
    val topics: List<String> = emptyList(),
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    @SerialName("subscribers_count") val watchers: Int = 0,
    val language: String? = null,
    val license: LicenseResponse? = null,
    @SerialName("default_branch") val defaultBranch: String,
    val fork: Boolean = false,
    val archived: Boolean = false,
    @SerialName("pushed_at") val pushedAt: String? = null,
) {
    fun toModel() = RepoDetails(
        id = RepoId(owner.login, name),
        description = description,
        homepage = homepage?.ifBlank { null },
        topics = topics,
        stars = stars,
        forks = forks,
        watchers = watchers,
        language = language,
        // GitHub reports unrecognized licenses as NOASSERTION.
        license = license?.spdxId?.takeUnless { it == "NOASSERTION" } ?: license?.name,
        defaultBranch = defaultBranch,
        ownerAvatarUrl = owner.avatarUrl,
        isFork = fork,
        isArchived = archived,
        pushedAt = pushedAt?.let(Instant::parse),
    )
}

@Serializable
private data class ContentResponse(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    val content: String? = null,
    val encoding: String? = null,
) {
    fun decodedText(): String? =
        if (encoding == "base64" && content != null) Base64.getMimeDecoder().decode(content).decodeToString() else null

    fun toFile() = RepoFile(
        path = path,
        name = name,
        type = when (type) {
            "dir" -> RepoFileType.DIR
            "symlink" -> RepoFileType.SYMLINK
            "submodule" -> RepoFileType.SUBMODULE
            else -> RepoFileType.FILE
        },
        size = size,
    )
}

@Serializable
private data class LabelResponse(val name: String, val color: String? = null)

@Serializable
private data class IssueResponse(
    val number: Int,
    val title: String,
    val state: String,
    val user: Owner? = null,
    val comments: Int? = null,
    @SerialName("created_at") val createdAt: String,
    val labels: List<LabelResponse> = emptyList(),
    val draft: Boolean = false,
    @SerialName("merged_at") val mergedAt: String? = null,
) {
    fun toModel(isPullRequest: Boolean) = IssueSummary(
        number = number,
        title = title,
        state = when {
            mergedAt != null -> IssueState.MERGED
            state == "closed" -> IssueState.CLOSED
            else -> IssueState.OPEN
        },
        author = user?.toModel(),
        comments = if (isPullRequest) null else comments,
        createdAt = Instant.parse(createdAt),
        labels = labels.map { Label(it.name, it.color) },
        isPullRequest = isPullRequest,
        isDraft = draft,
    )
}

@Serializable
private data class SearchResponse(val items: List<IssueResponse>)

@Serializable
private data class ReleaseResponse(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val author: Owner? = null,
) {
    fun toModel() = Release(
        tag = tag,
        name = name?.ifBlank { null },
        body = body?.ifBlank { null },
        publishedAt = publishedAt?.let(Instant::parse),
        isPrerelease = prerelease,
        author = author?.toModel(),
    )
}

@Serializable
private data class RunsResponse(@SerialName("workflow_runs") val workflowRuns: List<RunResponse>)

@Serializable
private data class RunResponse(
    val id: Long,
    val name: String? = null,
    @SerialName("display_title") val displayTitle: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    val event: String,
    @SerialName("run_number") val runNumber: Int,
    @SerialName("created_at") val createdAt: String,
    val actor: Owner? = null,
) {
    fun toModel() = WorkflowRun(
        id = id,
        workflowName = name.orEmpty(),
        title = displayTitle ?: name.orEmpty(),
        status = when (status) {
            "queued", "waiting", "requested", "pending" -> RunStatus.QUEUED
            "in_progress" -> RunStatus.IN_PROGRESS
            "completed" -> RunStatus.COMPLETED
            else -> RunStatus.OTHER
        },
        conclusion = when (conclusion) {
            null -> null
            "success" -> RunConclusion.SUCCESS
            "failure" -> RunConclusion.FAILURE
            "cancelled" -> RunConclusion.CANCELLED
            "skipped" -> RunConclusion.SKIPPED
            "neutral" -> RunConclusion.NEUTRAL
            "timed_out" -> RunConclusion.TIMED_OUT
            "action_required" -> RunConclusion.ACTION_REQUIRED
            else -> RunConclusion.OTHER
        },
        branch = headBranch,
        event = event,
        runNumber = runNumber,
        createdAt = Instant.parse(createdAt),
        actor = actor?.toModel(),
    )
}
