package fr.arthurbrugiere.forgeline.forge.github

import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.RepoApi
import fr.arthurbrugiere.forgeline.core.forge.VersionOrder
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.DiscussionComment
import fr.arthurbrugiere.forgeline.core.model.DiscussionCommentPage
import fr.arthurbrugiere.forgeline.core.model.DiscussionPage
import fr.arthurbrugiere.forgeline.core.model.DiscussionReactions
import fr.arthurbrugiere.forgeline.core.model.DiscussionSummary
import fr.arthurbrugiere.forgeline.core.model.DiscussionUpvotes
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.Readme
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoLanguage
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.WikiPage
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import fr.arthurbrugiere.forgeline.core.model.languageShares
import fr.arthurbrugiere.forgeline.core.model.wikiBaseUrl
import fr.arthurbrugiere.forgeline.core.model.wikiTitleOf
import fr.arthurbrugiere.forgeline.core.model.withLatest
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.github.graphql.AddDiscussionCommentMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.AddReactionMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.AddUpvoteMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.DeleteDiscussionCommentMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.DeleteDiscussionMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.DiscussionCommentsQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.DiscussionQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.DiscussionRepliesQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.DiscussionsQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.MarkDiscussionAnswerMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.PinnedQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.RemoveReactionMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.RemoveUpvoteMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.UnmarkDiscussionAnswerMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.UpdateDiscussionCommentMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.UpdateDiscussionMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.fragment.DiscussionCommentFields
import fr.arthurbrugiere.forgeline.forge.github.graphql.fragment.DiscussionCommentWithReplies
import fr.arthurbrugiere.forgeline.forge.github.graphql.fragment.DiscussionFields
import fr.arthurbrugiere.forgeline.forge.github.graphql.fragment.ReactionTally
import fr.arthurbrugiere.forgeline.forge.github.graphql.type.ReactionContent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.Base64
import fr.arthurbrugiere.forgeline.forge.github.graphql.type.IssueState as GraphQlIssueState

class GitHubRepoApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : RepoApi {

    override suspend fun repo(token: String?, id: RepoId): ForgeResult<RepoDetails> = repoAndRights(token, id).map { it.first }

    override suspend fun repoAndRights(token: String?, id: RepoId): ForgeResult<Pair<RepoDetails, RepoRights?>> = forgeCall {
        coroutineScope {
            // Asked alongside: GitHub counts open issues and pull requests as one number, and only says how many of
            // them are pull requests through their list. Signed out, that request is saved: there are sixty an hour.
            val openPulls = async { if (token == null) null else openPulls(token, id) }
            get(token, "repos", id.owner, id.name).toResult {
                val answer = bodyAsText()
                ForgeJson.decodeFromString<RepoResponse>(answer).toModel(openPulls.await()) to token?.let { gitHubRights(answer) }
            }
        }
    }

    /** How many pull requests are open, from the last page of a list of one per page; null when it can't be told. */
    private suspend fun openPulls(token: String, id: RepoId): Int? = try {
        val response = get(token, "repos", id.owner, id.name, "pulls", query = mapOf("state" to "open", "per_page" to "1"))
        if (response.status != HttpStatusCode.OK) null else response.lastPage() ?: response.body<List<JsonElement>>().size
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A count is a nicety: the repository opens without it.
        null
    }

    /** Bytes of code by language, as GitHub's linguist counts them. */
    override suspend fun languages(token: String?, id: RepoId): ForgeResult<List<RepoLanguage>> = forgeCall {
        get(token, "repos", id.owner, id.name, "languages").toResult { languageShares(body<Map<String, Double>>()) }
    }

    override suspend fun readme(token: String?, id: RepoId, ref: String?): ForgeResult<Readme?> = forgeCall {
        val response = get(token, "repos", id.owner, id.name, "readme", query = ref?.let { mapOf("ref" to it) } ?: emptyMap())
        if (response.status == HttpStatusCode.NotFound) return@forgeCall ForgeResult.Success(null)
        response.toResult {
            val file = body<ContentResponse>()
            Readme(path = file.path, markdown = file.decodedText() ?: "")
        }
    }

    override suspend fun refs(token: String?, id: RepoId): ForgeResult<GitRefs> = forgeCall {
        // matching-refs answers every ref at once, where /branches and /tags stop at 100 a page.
        val names = listOf("heads", "tags").map { kind ->
            val response = get(token, "repos", id.owner, id.name, "git", "matching-refs", kind)
            when (response.status) {
                HttpStatusCode.OK -> response.body<List<GitRefResponse>>().map { it.ref.removePrefix("refs/$kind/") }
                // An empty repository has no git database yet.
                HttpStatusCode.Conflict -> emptyList()
                else -> return@forgeCall response.failure()
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
        forgeCall {
            get(token, "repos", id.owner, id.name, "contents", *path.segments(), query = mapOf("ref" to ref)).toResult {
                body<List<ContentResponse>>()
                    .map { it.toFile() }
                    .sortedWith(compareBy<RepoFile> { it.type != RepoFileType.DIR }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }

    override suspend fun fileText(token: String?, id: RepoId, path: String, ref: String): ForgeResult<String> = forgeCall {
        val response = get(token, "repos", id.owner, id.name, "contents", *path.segments(), query = mapOf("ref" to ref))
        if (response.status != HttpStatusCode.OK) return@forgeCall response.failure()
        // Files over 1 MB come back without content: the contents API can't serve them.
        response.body<ContentResponse>().decodedText()?.let { ForgeResult.Success(it) }
            ?: ForgeResult.Failure(ForgeError.Http(413, "File too large to preview"))
    }

    // Search, not /issues: that endpoint mixes in pull requests, which crowd issues out on busy repos.
    override suspend fun issues(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        search(token, id, query, isPullRequest = false)
    }

    override suspend fun pullRequests(token: String?, id: RepoId, query: IssueQuery): ForgeResult<List<IssueSummary>> = forgeCall {
        // The pulls endpoint says which are drafts, but can't look for words: those go through search.
        if (query.text.isNotBlank()) return@forgeCall search(token, id, query, isPullRequest = true)
        get(token, "repos", id.owner, id.name, "pulls", query = mapOf("state" to if (query.open) "open" else "closed", "per_page" to "${IssueQuery.PAGE_SIZE}", "page" to "${query.page}"))
            .toResult { body<List<IssueResponse>>().map { it.toModel(isPullRequest = true) } }
    }

    private suspend fun search(token: String?, id: RepoId, query: IssueQuery, isPullRequest: Boolean): ForgeResult<List<IssueSummary>> {
        val words = query.text.trim()
        val q = listOf("repo:${id.fullName}", if (isPullRequest) "is:pr" else "is:issue", if (query.open) "is:open" else "is:closed", words)
            .filter { it.isNotEmpty() }.joinToString(" ")
        // Newest first; with words, GitHub's best match first.
        val order = if (words.isEmpty()) mapOf("sort" to "created", "order" to "desc") else emptyMap()
        return get(token, "search", "issues", query = mapOf("q" to q) + order + mapOf("per_page" to "${IssueQuery.PAGE_SIZE}", "page" to "${query.page}"))
            .toResult { body<SearchResponse>().items.map { it.toModel(isPullRequest) } }
    }

    /** Only GitHub's GraphQL API knows which issues are pinned, and it answers nobody signed out. */
    override suspend fun pinnedIssues(token: String?, id: RepoId): ForgeResult<List<IssueSummary>> {
        if (token == null) return ForgeResult.Success(emptyList())
        // A repository the token can't see has nothing pinned for it.
        return forgeCall {
            httpClient.gitHubOperation(apiBaseUrl, token, PinnedQuery(id.owner, id.name), partial = true).map { data ->
                data.repository?.pinnedIssues?.nodes.orEmpty().filterNotNull().map { it.issue.toModel() }
            }
        }
    }

    /** Discussions are in GitHub's GraphQL API only, which answers nobody signed out. */
    private suspend fun <D : Operation.Data, T> discussionsQuery(token: String?, operation: Operation<D>, read: (D) -> T?): ForgeResult<T> {
        if (token == null) return ForgeResult.Failure(ForgeError.Unauthorized)
        return forgeCall {
            when (val answer = httpClient.gitHubOperation(apiBaseUrl, token, operation)) {
                is ForgeResult.Failure -> answer
                is ForgeResult.Success -> read(answer.value)?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
            }
        }
    }

    override suspend fun discussions(token: String?, id: RepoId, after: String?): ForgeResult<DiscussionPage> =
        discussionsQuery(token, DiscussionsQuery(id.owner, id.name, Optional.presentIfNotNull(after))) { data ->
            data.repository?.discussions?.let { list ->
                DiscussionPage(
                    list.nodes.orEmpty().filterNotNull().map { it.discussionFields.toSummary(it.comments.totalCount) },
                    list.pageInfo.endCursor.takeIf { list.pageInfo.hasNextPage },
                )
            }
        }

    override suspend fun discussion(token: String?, id: RepoId, number: Int): ForgeResult<Discussion> =
        discussionsQuery(token, DiscussionQuery(id.owner, id.name, number)) { data ->
            data.repository?.discussion?.let { found ->
                Discussion(
                    found.discussionFields.toSummary(found.comments.totalCount),
                    found.body,
                    found.comments.nodes.orEmpty().filterNotNull().map { it.discussionCommentWithReplies.toModel() },
                    commentsAfter = found.comments.pageInfo.endCursor.takeIf { found.comments.pageInfo.hasNextPage },
                    id = found.id,
                    locked = found.locked,
                    reactions = found.reactionTally.toModel(),
                    upvoted = found.viewerHasUpvoted,
                    canUpvote = found.viewerCanUpvote,
                    canEdit = found.viewerCanUpdate,
                    canDelete = found.viewerCanDelete,
                )
            }
        }

    override suspend fun discussionComments(token: String, discussionId: String, after: String): ForgeResult<DiscussionCommentPage> =
        discussionsQuery(token, DiscussionCommentsQuery(discussionId, Optional.present(after))) { data ->
            data.node?.onDiscussion?.comments?.let { page ->
                DiscussionCommentPage(page.nodes.orEmpty().filterNotNull().map { it.discussionCommentWithReplies.toModel() }, page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage })
            }
        }

    override suspend fun discussionReplies(token: String, commentId: String, after: String): ForgeResult<DiscussionCommentPage> =
        discussionsQuery(token, DiscussionRepliesQuery(commentId, Optional.present(after))) { data ->
            data.node?.onDiscussionComment?.replies?.let { page ->
                DiscussionCommentPage(page.nodes.orEmpty().filterNotNull().map { it.discussionCommentFields.toModel() }, page.pageInfo.endCursor.takeIf { page.pageInfo.hasNextPage })
            }
        }

    override suspend fun commentOnDiscussion(token: String, discussion: Discussion, body: String, replyTo: String?): ForgeResult<Unit> {
        val id = discussion.id ?: return ForgeResult.Failure(ForgeError.Unsupported)
        val mutation = AddDiscussionCommentMutation(id, body, Optional.presentIfNotNull(replyTo))
        return forgeCall { httpClient.gitHubOperation(apiBaseUrl, token, mutation).map { } }
    }

    override suspend fun setDiscussionAnswer(token: String, commentId: String, answer: Boolean): ForgeResult<Unit> = forgeCall {
        if (answer) {
            httpClient.gitHubOperation(apiBaseUrl, token, MarkDiscussionAnswerMutation(commentId)).map { }
        } else {
            httpClient.gitHubOperation(apiBaseUrl, token, UnmarkDiscussionAnswerMutation(commentId)).map { }
        }
    }

    override suspend fun editDiscussionComment(token: String, commentId: String, body: String): ForgeResult<Unit> =
        forgeCall { httpClient.gitHubOperation(apiBaseUrl, token, UpdateDiscussionCommentMutation(commentId, body)).map { } }

    override suspend fun deleteDiscussionComment(token: String, commentId: String): ForgeResult<Unit> =
        forgeCall { httpClient.gitHubOperation(apiBaseUrl, token, DeleteDiscussionCommentMutation(commentId)).map { } }

    override suspend fun editDiscussion(token: String, discussionId: String, title: String, body: String): ForgeResult<Unit> =
        forgeCall { httpClient.gitHubOperation(apiBaseUrl, token, UpdateDiscussionMutation(discussionId, title, body)).map { } }

    override suspend fun deleteDiscussion(token: String, discussionId: String): ForgeResult<Unit> =
        forgeCall { httpClient.gitHubOperation(apiBaseUrl, token, DeleteDiscussionMutation(discussionId)).map { } }

    override suspend fun upvoteInDiscussion(token: String, subjectId: String, give: Boolean): ForgeResult<DiscussionUpvotes> = forgeCall {
        val tally = if (give) {
            httpClient.gitHubOperation(apiBaseUrl, token, AddUpvoteMutation(subjectId)).map { it.addUpvote?.subject?.voteTally }
        } else {
            httpClient.gitHubOperation(apiBaseUrl, token, RemoveUpvoteMutation(subjectId)).map { it.removeUpvote?.subject?.voteTally }
        }
        when (tally) {
            is ForgeResult.Failure -> tally
            is ForgeResult.Success -> tally.value?.let { ForgeResult.Success(DiscussionUpvotes(it.upvoteCount, it.viewerHasUpvoted, it.viewerCanUpvote)) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
        }
    }

    override suspend fun reactInDiscussion(token: String, subjectId: String, reaction: Reaction, give: Boolean): ForgeResult<DiscussionReactions> = forgeCall {
        val content = ReactionContent.safeValueOf(reaction.name)
        if (give) {
            httpClient.gitHubOperation(apiBaseUrl, token, AddReactionMutation(subjectId, content)).map { it.addReaction?.subject?.reactionTally.toModel() }
        } else {
            httpClient.gitHubOperation(apiBaseUrl, token, RemoveReactionMutation(subjectId, content)).map { it.removeReaction?.subject?.reactionTally.toModel() }
        }
    }

    /**
     * GitHub's wikis have no API: a page written in Markdown is read as a file, from the wiki's own repository. One
     * written otherwise (AsciiDoc, MediaWiki) isn't found there: 404, and its forge's site has it.
     */
    override suspend fun wikiPage(token: String?, id: RepoId, key: String): ForgeResult<WikiPage> = forgeCall {
        httpClient.get("https://raw.githubusercontent.com/wiki/${id.owner}/${id.name}/${key.encodeURLPathPart()}.md") { if (token != null) bearerAuth(token) }
            .toResult { WikiPage(key, wikiTitleOf(key), bodyAsText(), "${id.wikiBaseUrl}$key") }
    }

    override suspend fun releases(token: String?, id: RepoId): ForgeResult<List<Release>> = forgeCall {
        get(token, "repos", id.owner, id.name, "releases", query = mapOf("per_page" to "30"))
            .toResult { body<List<ReleaseResponse>>().filterNot { it.draft }.map { it.toModel(id) }.withLatest() }
    }

    override suspend fun release(token: String?, id: RepoId, tag: String): ForgeResult<Release> = forgeCall {
        // A tag may hold slashes: each part is its own path segment.
        get(token, "repos", id.owner, id.name, "releases", "tags", *tag.segments()).toResult { body<ReleaseResponse>().toModel(id) }
    }

    override suspend fun workflowRuns(token: String?, id: RepoId): ForgeResult<List<WorkflowRun>> = forgeCall {
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
    @SerialName("has_discussions") val hasDiscussions: Boolean = false,
    @SerialName("has_wiki") val hasWiki: Boolean = false,
    /** Issues and pull requests together: on GitHub a pull request is an issue. */
    @SerialName("open_issues_count") val openIssuesAndPulls: Int? = null,
) {
    fun toModel(openPulls: Int? = null) = RepoDetails(
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
        hasDiscussions = hasDiscussions,
        hasWiki = hasWiki,
        openIssues = if (openPulls != null && openIssuesAndPulls != null) (openIssuesAndPulls - openPulls).coerceAtLeast(0) else null,
        openPulls = openPulls,
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

private fun DiscussionFields.toSummary(comments: Int) = DiscussionSummary(
    number, title, author?.let { ForgeUser(it.login, null, it.avatarUrl) }, Instant.parse(createdAt), category.name, comments, upvoteCount,
    // Null where the category takes no answer.
    isAnswered,
)

private fun DiscussionCommentFields.toModel(replies: List<DiscussionComment> = emptyList(), replyCount: Int = replies.size) = DiscussionComment(
    id, author?.let { ForgeUser(it.login, null, it.avatarUrl) }, body, Instant.parse(createdAt), upvoteCount, isAnswer, replies,
    replyCount = replyCount,
    canMarkAnswer = viewerCanMarkAsAnswer,
    canUnmarkAnswer = viewerCanUnmarkAsAnswer,
    reactions = reactionTally.toModel(),
    upvoted = viewerHasUpvoted,
    canUpvote = viewerCanUpvote,
    canEdit = viewerCanUpdate,
    canDelete = viewerCanDelete,
)

private fun DiscussionCommentWithReplies.toModel() = discussionCommentFields.toModel(
    replies = replies.nodes.orEmpty().filterNotNull().map { it.discussionCommentFields.toModel() },
    replyCount = replies.totalCount,
).copy(repliesAfter = replies.pageInfo.endCursor.takeIf { replies.pageInfo.hasNextPage })

/** The kinds anyone gave, by how many (GraphQL lists every kind, most at zero), and the reader's own. */
private fun ReactionTally?.toModel(): DiscussionReactions {
    val groups = this?.reactionGroups.orEmpty().mapNotNull { group -> Reaction.entries.firstOrNull { it.name == group.content.rawValue }?.let { it to group } }
    return DiscussionReactions(
        counts = groups.mapNotNull { (kind, group) -> group.reactors.totalCount.takeIf { it > 0 }?.let { kind to it } }.toMap(),
        given = groups.filter { (_, group) -> group.viewerHasReacted }.mapTo(mutableSetOf()) { it.first },
        canReact = this?.viewerCanReact == true,
    )
}

private fun PinnedQuery.Issue.toModel() = IssueSummary(
    number = number,
    title = title,
    state = if (state == GraphQlIssueState.CLOSED) IssueState.CLOSED else IssueState.OPEN,
    author = author?.let { ForgeUser(it.login, null, it.avatarUrl) },
    comments = comments.totalCount,
    createdAt = Instant.parse(createdAt),
    labels = labels?.nodes.orEmpty().filterNotNull().map { Label(it.name, it.color) },
    isPullRequest = false,
    isDraft = false,
)

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
