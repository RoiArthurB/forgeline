package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.withLatest
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.Reaction
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

    // Search, not /issues: that endpoint mixes in pull requests, which crowd issues out on busy repos.
    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = gitHubCall {
        search(token, id, query, isPullRequest = false)
    }

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = gitHubCall {
        // The pulls endpoint says which are drafts, but can't look for words: those go through search.
        if (query.text.isNotBlank()) return@gitHubCall search(token, id, query, isPullRequest = true)
        get(token, "repos", id.owner, id.name, "pulls", query = mapOf("state" to if (query.open) "open" else "closed", "per_page" to "30"))
            .toResult { body<List<IssueResponse>>().map { it.toModel(isPullRequest = true) } }
    }

    private suspend fun search(token: String?, id: RepoId, query: IssueQuery, isPullRequest: Boolean): ForgeResult<List<IssueSummary>> {
        val words = query.text.trim()
        val q = listOf("repo:${id.fullName}", if (isPullRequest) "is:pr" else "is:issue", if (query.open) "is:open" else "is:closed", words)
            .filter { it.isNotEmpty() }.joinToString(" ")
        // Newest first; with words, GitHub's best match first.
        val order = if (words.isEmpty()) mapOf("sort" to "created", "order" to "desc") else emptyMap()
        return get(token, "search", "issues", query = mapOf("q" to q) + order + ("per_page" to "30"))
            .toResult { body<SearchResponse>().items.map { it.toModel(isPullRequest) } }
    }

    /** Only GitHub's GraphQL API knows which issues are pinned, and it answers nobody signed out. */
    override suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> {
        if (token == null) return ForgeResult.Success(emptyList())
        return gitHubCall {
            val response = httpClient.post("$apiBaseUrl/graphql") {
                bearerAuth(token)
                header("X-GitHub-Api-Version", GitHubAuthApi.API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("query", PINNED_QUERY)
                        putJsonObject("variables") {
                            put("owner", id.owner)
                            put("name", id.name)
                        }
                    },
                )
            }
            response.toResult { body<PinnedAnswer>().data?.repository?.pinnedIssues?.nodes.orEmpty().map { it.issue.toModel() } }
        }
    }

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = gitHubCall {
        get(token, "repos", id.owner, id.name, "releases", query = mapOf("per_page" to "30"))
            .toResult { body<List<ReleaseResponse>>().filterNot { it.draft }.map { it.toModel(id) }.withLatest() }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = gitHubCall {
        // A tag may hold slashes: each part is its own path segment.
        get(token, "repos", id.owner, id.name, "releases", "tags", *tag.segments()).toResult { body<ReleaseResponse>().toModel(id) }
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

@Serializable
private data class GitRefResponse(val ref: String)

@Serializable
internal data class Owner(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null) {
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
    @SerialName("has_issues") val hasIssues: Boolean = true,
) {
    fun toModel() = RepoDetails(
        id = RepoId(owner.login, name, ForgeInstance.GitHub),
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
        hasIssues = hasIssues,
    )
}

@Serializable
internal data class ContentResponse(
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
    // Where search results say a pull request was merged.
    @SerialName("pull_request") val pullRequest: MergedResponse? = null,
) {
    fun toModel(isPullRequest: Boolean) = IssueSummary(
        number = number,
        title = title,
        state = when {
            mergedAt != null || pullRequest?.mergedAt != null -> IssueState.MERGED
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
private data class MergedResponse(@SerialName("merged_at") val mergedAt: String? = null)

@Serializable
private data class SearchResponse(val items: List<IssueResponse>)

private const val PINNED_QUERY = "query(\$owner: String!, \$name: String!) { repository(owner: \$owner, name: \$name) { pinnedIssues(first: 3) { nodes { issue { " +
    "number title state createdAt author { login avatarUrl } comments { totalCount } labels(first: 10) { nodes { name color } } } } } } }"

@Serializable
private data class PinnedAnswer(val data: PinnedData? = null)

@Serializable
private data class PinnedData(val repository: PinnedRepository? = null)

@Serializable
private data class PinnedRepository(val pinnedIssues: PinnedIssues = PinnedIssues())

@Serializable
private data class PinnedIssues(val nodes: List<PinnedNode> = emptyList())

@Serializable
private data class PinnedNode(val issue: PinnedIssue)

@Serializable
private data class PinnedAuthor(val login: String, val avatarUrl: String? = null)

@Serializable
private data class PinnedCount(val totalCount: Int = 0)

@Serializable
private data class PinnedLabels(val nodes: List<LabelResponse> = emptyList())

@Serializable
private data class PinnedIssue(
    val number: Int,
    val title: String,
    val state: String,
    val createdAt: String,
    val author: PinnedAuthor? = null,
    val comments: PinnedCount = PinnedCount(),
    val labels: PinnedLabels = PinnedLabels(),
) {
    fun toModel() = IssueSummary(
        number = number,
        title = title,
        state = if (state == "CLOSED") IssueState.CLOSED else IssueState.OPEN,
        author = author?.let { ForgeUser(it.login, null, it.avatarUrl) },
        comments = comments.totalCount,
        createdAt = Instant.parse(createdAt),
        labels = labels.nodes.map { Label(it.name, it.color) },
        isPullRequest = false,
        isDraft = false,
    )
}

@Serializable
private data class ReleaseResponse(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val author: Owner? = null,
    val assets: List<AssetResponse> = emptyList(),
    @SerialName("html_url") val htmlUrl: String? = null,
    val reactions: ReleaseReactions? = null,
) {
    fun toModel(id: RepoId) = Release(
        tag = tag,
        name = name?.ifBlank { null },
        body = body?.ifBlank { null },
        publishedAt = publishedAt?.let(Instant::parse),
        isPrerelease = prerelease,
        author = author?.toModel(),
        assets = assets.map { ReleaseAsset(it.name, it.size, it.downloads, it.url) },
        // The archive links the API sends need the API's headers; these are the ones the site offers.
        zipUrl = "https://github.com/${id.fullName}/archive/refs/tags/$tag.zip",
        tarUrl = "https://github.com/${id.fullName}/archive/refs/tags/$tag.tar.gz",
        webUrl = htmlUrl,
        reactions = reactions?.toModel().orEmpty(),
    )
}

@Serializable
private data class AssetResponse(
    val name: String,
    val size: Long = 0,
    @SerialName("download_count") val downloads: Int? = null,
    @SerialName("browser_download_url") val url: String,
)

@Serializable
private data class ReleaseReactions(
    @SerialName("+1") val thumbsUp: Int = 0,
    @SerialName("-1") val thumbsDown: Int = 0,
    val laugh: Int = 0,
    val hooray: Int = 0,
    val confused: Int = 0,
    val heart: Int = 0,
    val rocket: Int = 0,
    val eyes: Int = 0,
) {
    fun toModel(): Map<Reaction, Int> = mapOf(
        Reaction.THUMBS_UP to thumbsUp, Reaction.THUMBS_DOWN to thumbsDown, Reaction.LAUGH to laugh, Reaction.HOORAY to hooray,
        Reaction.CONFUSED to confused, Reaction.HEART to heart, Reaction.ROCKET to rocket, Reaction.EYES to eyes,
    ).filterValues { it > 0 }
}

@Serializable
private data class RunsResponse(@SerialName("workflow_runs") val workflowRuns: List<RunResponse>)

@Serializable
internal data class RunResponse(
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
    @SerialName("workflow_id") val workflowId: Long? = null,
    @SerialName("run_attempt") val runAttempt: Int = 1,
    @SerialName("run_started_at") val runStartedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toModel() = WorkflowRun(
        id = id,
        workflowName = name.orEmpty(),
        title = displayTitle ?: name.orEmpty(),
        status = runStatus(status),
        conclusion = runConclusion(conclusion),
        branch = headBranch,
        event = event,
        runNumber = runNumber,
        createdAt = Instant.parse(createdAt),
        actor = actor?.toModel(),
        workflowId = workflowId,
        attempt = runAttempt,
        startedAt = runStartedAt?.let(Instant::parse),
        updatedAt = updatedAt?.let(Instant::parse),
    )
}

internal fun runStatus(status: String?) = when (status) {
    "queued", "waiting", "requested", "pending" -> RunStatus.QUEUED
    "in_progress" -> RunStatus.IN_PROGRESS
    "completed" -> RunStatus.COMPLETED
    else -> RunStatus.OTHER
}

internal fun runConclusion(conclusion: String?) = when (conclusion) {
    null -> null
    "success" -> RunConclusion.SUCCESS
    "failure" -> RunConclusion.FAILURE
    "cancelled" -> RunConclusion.CANCELLED
    "skipped" -> RunConclusion.SKIPPED
    "neutral" -> RunConclusion.NEUTRAL
    "timed_out" -> RunConclusion.TIMED_OUT
    "action_required" -> RunConclusion.ACTION_REQUIRED
    else -> RunConclusion.OTHER
}
