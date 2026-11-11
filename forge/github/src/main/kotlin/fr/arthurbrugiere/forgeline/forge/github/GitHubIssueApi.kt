package fr.arthurbrugiere.forgeline.forge.github

import com.apollographql.apollo.api.Operation
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.forge.parseIssueChooser
import fr.arthurbrugiere.forgeline.core.forge.parseIssueForm
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.HideReason
import fr.arthurbrugiere.forgeline.core.model.IssueChooser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.IssueType
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.ProjectItem
import fr.arthurbrugiere.forgeline.core.model.ProjectStatus
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.SavedReply
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.SubIssues
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.github.graphql.ClearProjectStatusMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.ClosingQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.ConvertToDraftMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.DeleteIssueMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.HideCommentMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.IssueNodeQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.IssueTemplatesQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.MarkReadyMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.PinIssueMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.ProjectEventPlacesQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.ProjectItemsQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.SavedRepliesQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.SetProjectStatusMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.SubscribableQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.TimelineNodesQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.TransferIdsQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.TransferIssueMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.TypesQuery
import fr.arthurbrugiere.forgeline.forge.github.graphql.UnhideCommentMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.UnpinIssueMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.UpdateSubscriptionMutation
import fr.arthurbrugiere.forgeline.forge.github.graphql.type.PullRequestState
import fr.arthurbrugiere.forgeline.forge.github.graphql.type.ReportedContentClassifiers
import fr.arthurbrugiere.forgeline.forge.github.graphql.type.SubscriptionState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.util.Base64

class GitHubIssueApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
) : IssueApi {

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString())
        if (response.status.value != 200) return@forgeCall response.failure()
        val issue = response.body<IssueJson>()
        // The issue endpoint lacks merge and branch data; pull requests need their own call.
        val pull = if (issue.pullRequest != null) {
            val pullResponse = httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString())
            if (pullResponse.status.value != 200) return@forgeCall pullResponse.failure()
            pullResponse.body<PullJson>()
        } else {
            null
        }
        ForgeResult.Success(issue.toModel(ref, pull))
    }

    /** The issue endpoint names pull requests too: no second call for the title. */
    override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString())
            .toResult { body<IssueJson>().title }
    }

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = forgeCall {
        val response = httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "timeline",
            query = mapOf("per_page" to "100", "page" to page.toString()),
        )
        response.toResult {
            val events = body<List<EventJson>>()
            val (nodes, places) = if (token == null) {
                emptyMap<String, TimelineNodesQuery.Node>() to emptyMap()
            } else {
                coroutineScope {
                    val places = async { projectPlaces(token, events) }
                    timelineNodes(token, events) to places.await()
                }
            }
            val items = events.mapNotNull { event ->
                val item = event.toModel(ref.repo, ref.number, nodes[event.nodeId])
                val place = event.nodeId?.let(places::get)
                if (item is TimelineItem.Event && place != null) item.copy(place = place) else item
            }.withoutCloseOfMerge().withoutRepeatedReferences()
            TimelinePage(withCommitMessages(token, items), nextPage(), lastPage())
        }
    }

    /**
     * A commit is referenced again by every fork it is pushed to, and the one that closed the conversation by the
     * close too (gama-platform/gama#1248, 2026-10-09): it is told once, where it first is.
     */
    private fun List<TimelineItem>.withoutRepeatedReferences(): List<TimelineItem> {
        val seen = mutableSetOf<String>()
        return filter { item ->
            when (item) {
                is TimelineItem.StateChanged -> item.commit?.let { seen += it.sha }.let { true }
                is TimelineItem.CommitReferenced -> seen.add(item.commit.sha)
                else -> true
            }
        }
    }

    /**
     * GitHub writes a merge, then a close a second later: the merge says it all, as GitHub's own page tells it. A merged
     * pull request can't be reopened, so any close after a merge is that one.
     */
    private fun List<TimelineItem>.withoutCloseOfMerge(): List<TimelineItem> {
        val merge = indexOfFirst { it is TimelineItem.StateChanged && it.change == StateChange.MERGED }
        if (merge < 0) return this
        return filterIndexed { i, item -> i < merge || !(item is TimelineItem.StateChanged && item.change == StateChange.CLOSED) }
    }

    /**
     * What GraphQL tells of the entries the REST timeline leaves short (which comments are hidden and may be, a
     * project's new status, the issue a duplicate is of...), by their nodes, a hundred at a time. Entries it can't be
     * asked of read as REST gives them.
     */
    private suspend fun timelineNodes(token: String, events: List<EventJson>): Map<String, TimelineNodesQuery.Node> = coroutineScope {
        events.filter { it.event in DETAILED_IN_GRAPHQL }.mapNotNull { it.nodeId }.distinct().chunked(100).map { ids ->
            async {
                val answer = runCatching { httpClient.gitHubOperation(apiBaseUrl, token, TimelineNodesQuery(ids), partial = true) }.getOrNull()
                (answer as? ForgeResult.Success)?.value?.nodes.orEmpty().filterNotNull().map { node -> node.id to node }
            }
        }.awaitAll().flatten().toMap()
    }

    /**
     * The boards project events happened on, by their nodes: a sign-in without the read:project scope (made before
     * Forgeline asked it) is refused, and the events read without them.
     */
    private suspend fun projectPlaces(token: String, events: List<EventJson>): Map<String, String> {
        val ids = events.filter { it.event in PROJECT_EVENTS }.mapNotNull { it.nodeId }.distinct().take(100)
        if (ids.isEmpty()) return emptyMap()
        val answer = runCatching { httpClient.gitHubOperation(apiBaseUrl, token, ProjectEventPlacesQuery(ids), partial = true) }.getOrNull()
        return (answer as? ForgeResult.Success)?.value?.nodes.orEmpty().filterNotNull().mapNotNull { node ->
            val title = node.onAddedToProjectV2Event?.project?.title ?: node.onRemovedFromProjectV2Event?.project?.title ?: node.onProjectV2ItemStatusChangedEvent?.project?.title
            title?.let { node.id to it }
        }.toMap()
    }

    override val keepsSavedReplies: Boolean = true

    /** The account's own, read with its read:user scope, which Forgeline's sign-in asks for. */
    override suspend fun savedReplies(token: String): ForgeResult<List<SavedReply>> = operation(token, SavedRepliesQuery()).map { data ->
        data.viewer.savedReplies?.nodes.orEmpty().filterNotNull().map { SavedReply(it.title, it.body) }
    }

    override val typesIssues: Boolean = true

    /** Through GraphQL: its REST list is the organisation's, and asks for its administration. */
    override suspend fun issueTypes(token: String, repo: RepoId): ForgeResult<List<IssueType>> = operation(token, TypesQuery(repo.owner, repo.name)).map { data ->
        data.repository?.issueTypes?.nodes.orEmpty().filterNotNull().map { IssueType(it.name, it.color.rawValue, it.description?.ifBlank { null }) }
    }

    override suspend fun setIssueType(token: String, ref: IssueRef, name: String?): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(),
            method = HttpMethod.Patch, body = buildJsonObject { put("type", name) },
        ).toResult { }
    }

    override val tracksSubIssues: Boolean = true

    /** Its sub-issues and its parent, asked together; no parent answers 404. */
    override suspend fun subIssues(token: String?, ref: IssueRef): ForgeResult<SubIssues> = forgeCall {
        coroutineScope {
            val parent = async { httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "parent") }
            val children = httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "sub_issues", query = mapOf("per_page" to "50"))
            if (!children.status.isSuccess()) {
                parent.cancel()
                return@coroutineScope children.failure()
            }
            val above = parent.await().takeIf { it.status.isSuccess() }?.let { runCatching { it.body<SubIssueJson>().toModel(ref.repo.forge) }.getOrNull() }
            ForgeResult.Success(SubIssues(above, children.body<List<SubIssueJson>>().map { it.toModel(ref.repo.forge) }))
        }
    }

    override suspend fun addSubIssue(token: String, ref: IssueRef, sub: IssueRef): ForgeResult<Unit> = subIssueChange(token, ref, sub, "sub_issues", HttpMethod.Post)

    override suspend fun removeSubIssue(token: String, ref: IssueRef, sub: IssueRef): ForgeResult<Unit> = subIssueChange(token, ref, sub, "sub_issue", HttpMethod.Delete)

    /** A sub-issue is named by its id, not its number: it is asked for first. */
    private suspend fun subIssueChange(token: String, ref: IssueRef, sub: IssueRef, segment: String, method: HttpMethod): ForgeResult<Unit> = forgeCall {
        val read = httpClient.gitHubApi(apiBaseUrl, token, "repos", sub.repo.owner, sub.repo.name, "issues", sub.number.toString())
        if (!read.status.isSuccess()) return@forgeCall read.failure()
        val id = read.body<SubIssueJson>().id
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), segment,
            method = method, body = buildJsonObject { put("sub_issue_id", id) },
        ).toResult { }
    }

    /** GraphQL only, which takes an account: signed out, none are told. */
    override suspend fun closingPulls(token: String?, ref: IssueRef): ForgeResult<List<LinkedIssue>> {
        if (token == null) return ForgeResult.Success(emptyList())
        return operation(token, ClosingQuery(ref.repo.owner, ref.repo.name, ref.number)).map { data ->
            data.repository?.issue?.closedByPullRequestsReferences?.nodes.orEmpty().filterNotNull().map { it.toModel(ref.repo.forge) }
        }
    }

    override val tracksProjects: Boolean = true

    /** Through GraphQL, its archived items left out: GitHub's page doesn't show them either. */
    override suspend fun projects(token: String, ref: IssueRef): ForgeResult<List<ProjectItem>> =
        operation(token, ProjectItemsQuery(ref.repo.owner, ref.repo.name, ref.number)).map { data ->
            val conversation = data.repository?.issueOrPullRequest
            val items = conversation?.onIssue?.projectItems?.nodes?.mapNotNull { it?.projectItemFields }
                ?: conversation?.onPullRequest?.projectItems?.nodes?.mapNotNull { it?.projectItemFields }
            items.orEmpty().filterNot { it.isArchived }.map { item ->
                val field = item.project.field?.onProjectV2SingleSelectField
                ProjectItem(
                    id = item.id,
                    projectId = item.project.id,
                    title = item.project.title,
                    status = item.fieldValueByName?.onProjectV2ItemFieldSingleSelectValue?.name,
                    statusFieldId = field?.id,
                    statuses = field?.options.orEmpty().map { ProjectStatus(it.id, it.name) },
                    // A closed board, or one without a Status column, can't be moved on.
                    canChange = item.project.viewerCanUpdate && !item.project.closed && field != null,
                    url = item.project.url.toString(),
                )
            }
        }

    override suspend fun setProjectStatus(token: String, item: ProjectItem, status: ProjectStatus?): ForgeResult<Unit> {
        val field = item.statusFieldId ?: return ForgeResult.Failure(ForgeError.Unsupported)
        return if (status == null) {
            operation(token, ClearProjectStatusMutation(item.projectId, item.id, field)).map { }
        } else {
            operation(token, SetProjectStatusMutation(item.projectId, item.id, field, status.id)).map { }
        }
    }

    override val hidesComments: Boolean = true

    override suspend fun setCommentHidden(token: String, ref: IssueRef, comment: TimelineItem.Comment, reason: HideReason?): ForgeResult<Unit> {
        val id = comment.nodeId ?: return ForgeResult.Failure(ForgeError.Unsupported)
        return if (reason == null) {
            operation(token, UnhideCommentMutation(id)).map { }
        } else {
            operation(token, HideCommentMutation(id, ReportedContentClassifiers.safeValueOf(reason.name))).map { }
        }
    }

    /** A closing event names its commit only: each one's message is asked of it, all at once. */
    private suspend fun withCommitMessages(token: String?, items: List<TimelineItem>): List<TimelineItem> = coroutineScope {
        items.map { item ->
            when (item) {
                is TimelineItem.StateChanged -> {
                    val commit = item.commit ?: return@map async { item }
                    async { item.copy(commit = commit.copy(message = commitMessage(token, commit) ?: return@async item)) }
                }
                is TimelineItem.CommitReferenced -> async { item.copy(commit = item.commit.copy(message = commitMessage(token, item.commit) ?: return@async item)) }
                else -> async { item }
            }
        }.awaitAll()
    }

    /** The commit's message, or null when it can't be read (gone since, or in a repository the reader can't see). */
    private suspend fun commitMessage(token: String?, commit: ClosingCommit): String? {
        val (owner, name) = commit.webUrl.removePrefix("https://github.com/").split('/').takeIf { it.size >= 2 } ?: return null
        val response = runCatching { httpClient.gitHubApi(apiBaseUrl, token, "repos", owner, name, "commits", commit.sha) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { response.body<CommitDetailJson>().commit?.message }.getOrNull()
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "comments",
            method = HttpMethod.Post, body = buildJsonObject { put("body", body) },
        ).toResult { body<CommentJson>().toModel() }
    }

    override val editsComments: Boolean = true

    override val reacts: Boolean = true

    override suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", "comments", commentId.toString(),
            method = HttpMethod.Patch, body = buildJsonObject { put("body", body) },
        ).toResult { }
    }

    override suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", "comments", commentId.toString(),
            method = HttpMethod.Delete,
        ).toResult { }
    }

    /** A pull request is an issue to this endpoint: one call renames either. */
    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = edit(token, ref) {
        put("title", title)
        put("body", body)
    }

    /**
     * GitHub says whether the reaction was already there: 201 when it made it, 200 with the one it had. That one is
     * then taken back by its id. The counts come from the comment or the issue read again: a list of reactions stops
     * at a hundred, and a busy issue has more.
     */
    override suspend fun toggleReaction(token: String, login: String, ref: IssueRef, commentId: Long?, reaction: Reaction): ForgeResult<Map<Reaction, Int>> = forgeCall {
        val target = if (commentId == null) arrayOf("issues", ref.number.toString()) else arrayOf("issues", "comments", commentId.toString())
        httpClient.toggleGitHubReaction(apiBaseUrl, token, ref.repo, target, reaction)
    }

    /** GitHub drops [labels] without a word for a reader who can't push to [repo]. */
    override suspend fun create(token: String, repo: RepoId, title: String, body: String, labels: List<String>): ForgeResult<IssueDetails> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", repo.owner, repo.name, "issues",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("title", title)
                put("body", body)
                if (labels.isNotEmpty()) putJsonArray("labels") { labels.forEach { add(it) } }
            },
        ).toResult { body<IssueJson>().let { it.toModel(IssueRef(repo, it.number), pull = null) } }
    }

    /** GitHub's GraphQL API lists a repository's Markdown templates, bodies and all; its forms are left out. */

    /**
     * GitHub's GraphQL API lists a repository's Markdown templates only: its issue forms, written in YAML, are read from
     * `.github/ISSUE_TEMPLATE` beside them, in the same order of file names GitHub shows them in.
     */
    override suspend fun issueTemplates(token: String, repo: RepoId): ForgeResult<List<IssueTemplate>> = coroutineScope {
        val forms = async { issueForms(token, repo) }
        markdownTemplates(token, repo).map { templates -> (templates + forms.await()).sortedBy { it.key } }
    }

    /** The forms that read; none where the folder can't be listed, which is most repositories (404). */
    private suspend fun issueForms(token: String, repo: RepoId): List<IssueTemplate> = coroutineScope {
        val listed = forgeCall {
            httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "contents", ".github", "ISSUE_TEMPLATE").toResult { body<List<TemplateFileJson>>() }
        }
        // config.yml says what the chooser offers besides the templates: it is no form.
        val files = (listed as? ForgeResult.Success)?.value.orEmpty()
            .filter { it.type == "file" && it.name.substringAfterLast('.') in FORM_EXTENSIONS && it.name.substringBeforeLast('.') != "config" }
        files.map { file ->
            async {
                val read = forgeCall {
                    httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "contents", *file.path.split('/').toTypedArray()).toResult { body<TemplateFileJson>().text() }
                }
                (read as? ForgeResult.Success)?.value?.let { parseIssueForm(file.name, it) }
            }
        }.awaitAll().filterNotNull()
    }

    /** GitHub reads `config.yml` from the templates' folder; most repositories keep none (404), which allows everything. */
    override suspend fun issueChooser(token: String, repo: RepoId): ForgeResult<IssueChooser> = forgeCall {
        val read = httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "contents", ".github", "ISSUE_TEMPLATE", "config.yml")
        if (read.status == HttpStatusCode.NotFound) return@forgeCall ForgeResult.Success(IssueChooser())
        read.toResult { body<TemplateFileJson>().text()?.let(::parseIssueChooser) ?: IssueChooser() }
    }

    private suspend fun markdownTemplates(token: String, repo: RepoId): ForgeResult<List<IssueTemplate>> =
        operation(token, IssueTemplatesQuery(repo.owner, repo.name)).map { data ->
            data.repository?.issueTemplates.orEmpty().map {
                IssueTemplate(
                    it.filename ?: it.name, it.name, it.about?.ifBlank { null }, it.title.orEmpty(), it.body.orEmpty(),
                    it.labels?.nodes.orEmpty().filterNotNull().map { label -> label.name },
                )
            }
        }

    /** A pull request is an issue to this endpoint: one call closes either. */
    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> = edit(token, ref) {
        put("state", if (open) "open" else "closed")
        if (!open && reason != null) put("state_reason", reason.name.lowercase())
    }

    private suspend fun edit(token: String, ref: IssueRef, fields: JsonObjectBuilder.() -> Unit): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(),
            method = HttpMethod.Patch, body = buildJsonObject(fields),
        ).toResult { }
    }

    /** GitHub keeps no due date, time spent or dependencies on an issue. */
    override val actions: Set<ConversationAction> = setOf(
        ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.CLOSE_REASON,
        ConversationAction.LOCK, ConversationAction.PIN, ConversationAction.TRANSFER, ConversationAction.DELETE,
        ConversationAction.REVIEWERS, ConversationAction.DRAFT, ConversationAction.TYPE,
    )

    // The lists below stop at their first hundred: a repository with more is rare, and none needs them all to triage.

    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "labels", query = FIRST_HUNDRED)
            .toResult { body<List<LabelJson>>().map { it.toModel() } }
    }

    /** GitHub writes a colour without its hash. */
    override suspend fun createLabel(token: String, repo: RepoId, name: String, color: String): ForgeResult<Label> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", repo.owner, repo.name, "labels", method = HttpMethod.Post,
            body = buildJsonObject {
                put("name", name)
                put("color", color.removePrefix("#"))
            },
        ).toResult { body<LabelJson>().toModel() }
    }

    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "labels",
            method = HttpMethod.Put, body = buildJsonObject { putJsonArray("labels") { names.forEach { add(it) } } },
        ).toResult { }
    }

    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "assignees", query = FIRST_HUNDRED)
            .toResult { body<List<UserJson>>().map { it.toModel() } }
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> =
        edit(token, ref) { putJsonArray("assignees") { logins.forEach { add(it) } } }

    /** GitHub asks for reviews one way and takes the ask back another: only who changes is sent to each. */
    override suspend fun setReviewers(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> = forgeCall {
        val pull = pull(token, ref)
        if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
        val asked = pull.body<PullJson>().requestedReviewers.map { it.login }
        val added = logins.filter { login -> asked.none { it.equals(login, ignoreCase = true) } }
        val removed = asked.filter { login -> logins.none { it.equals(login, ignoreCase = true) } }
        for ((who, method) in listOf(added to HttpMethod.Post, removed to HttpMethod.Delete)) {
            if (who.isEmpty()) continue
            val response = httpClient.gitHubApi(
                apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString(), "requested_reviewers",
                method = method,
                body = buildJsonObject { putJsonArray("reviewers") { who.forEach { add(it) } } },
            )
            if (!response.status.isSuccess()) return@forgeCall response.failure()
        }
        ForgeResult.Success(Unit)
    }

    /** Only GitHub's GraphQL API turns a pull request into a draft and back, by its node id. */
    override suspend fun setDraft(token: String, ref: IssueRef, draft: Boolean): ForgeResult<Unit> {
        val id = forgeCall {
            val pull = pull(token, ref)
            if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
            pull.body<PullJson>().nodeId?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }
        val node = when (id) {
            is ForgeResult.Failure -> return id
            is ForgeResult.Success -> id.value
        }
        return if (draft) operation(token, ConvertToDraftMutation(node)).map { } else operation(token, MarkReadyMutation(node)).map { }
    }

    /** The branch a pull request goes into is the pull request's own, not the issue's it also is. */
    override suspend fun setBase(token: String, ref: IssueRef, branch: String): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString(),
            method = HttpMethod.Patch,
            body = buildJsonObject { put("base", branch) },
        ).toResult { }
    }

    private suspend fun pull(token: String, ref: IssueRef) =
        httpClient.gitHubApi(apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString())

    override suspend fun milestones(token: String, repo: RepoId): ForgeResult<List<Milestone>> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, "milestones", query = FIRST_HUNDRED + ("state" to "open"))
            .toResult { body<List<MilestoneJson>>().map { it.toModel() } }
    }

    override suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?): ForgeResult<Unit> =
        edit(token, ref) { put("milestone", milestone?.id) }

    override suspend fun setLocked(token: String, ref: IssueRef, locked: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.gitHubApi(
            apiBaseUrl, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "lock",
            method = if (locked) HttpMethod.Put else HttpMethod.Delete,
        ).toResult { }
    }

    // Pinning, transferring and deleting only exist in GitHub's GraphQL API, which names an issue by its node id.

    override suspend fun isPinned(token: String, ref: IssueRef): ForgeResult<Boolean> =
        node(token, ref).map { it.isPinned == true }

    override suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean): ForgeResult<Unit> = onNode(token, ref) { id ->
        if (pinned) operation(token, PinIssueMutation(id)).map { } else operation(token, UnpinIssueMutation(id)).map { }
    }

    // A subscription is only in GitHub's GraphQL API too, on an issue or a pull request alike.

    override suspend fun isSubscribed(token: String, login: String, ref: IssueRef): ForgeResult<Boolean> =
        subscribable(token, ref).map { (_, state) -> state == SubscriptionState.SUBSCRIBED }

    override suspend fun setSubscribed(token: String, login: String, ref: IssueRef, subscribed: Boolean): ForgeResult<Unit> =
        when (val found = subscribable(token, ref)) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success ->
                operation(token, UpdateSubscriptionMutation(found.value.first, if (subscribed) SubscriptionState.SUBSCRIBED else SubscriptionState.UNSUBSCRIBED)).map { }
        }

    /** The issue's or pull request's node id, and whether the reader is subscribed to it. */
    private suspend fun subscribable(token: String, ref: IssueRef): ForgeResult<Pair<String, SubscriptionState?>> {
        val found = operation(token, SubscribableQuery(ref.repo.owner, ref.repo.name, ref.number))
        return when (found) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success -> {
                val node = found.value.repository?.issueOrPullRequest
                val subscribable = node?.onIssue?.let { it.id to it.viewerSubscription } ?: node?.onPullRequest?.let { it.id to it.viewerSubscription }
                subscribable?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
            }
        }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = onNode(token, ref) { id ->
        operation(token, DeleteIssueMutation(id)).map { }
    }

    override suspend fun transfer(token: String, ref: IssueRef, to: RepoId): ForgeResult<IssueRef> {
        val ids = when (val found = operation(token, TransferIdsQuery(ref.repo.owner, ref.repo.name, ref.number, to.owner, to.name))) {
            is ForgeResult.Failure -> return found
            is ForgeResult.Success -> found.value
        }
        val issueId = ids.from?.issue?.id
        val repositoryId = ids.to?.id
        if (issueId == null || repositoryId == null) return ForgeResult.Failure(ForgeError.Http(404, null))
        return operation(token, TransferIssueMutation(issueId, repositoryId)).map { data ->
            val moved = requireNotNull(data.transferIssue?.issue) { "GitHub answered the transfer without the issue" }
            IssueRef(RepoId(moved.repository.owner.login, moved.repository.name, ref.repo.forge), moved.number)
        }
    }

    /** The issue's node: its id and whether it is pinned. 404 when GitHub doesn't know it, or it is a pull request. */
    private suspend fun node(token: String, ref: IssueRef): ForgeResult<IssueNodeQuery.Issue> =
        when (val found = operation(token, IssueNodeQuery(ref.repo.owner, ref.repo.name, ref.number))) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success -> found.value.repository?.issue?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }

    private suspend fun <T> onNode(token: String, ref: IssueRef, act: suspend (id: String) -> ForgeResult<T>): ForgeResult<T> =
        when (val node = node(token, ref)) {
            is ForgeResult.Failure -> node
            is ForgeResult.Success -> act(node.value.id)
        }

    private suspend fun <D : Operation.Data> operation(token: String, operation: Operation<D>): ForgeResult<D> =
        forgeCall { httpClient.gitHubOperation(apiBaseUrl, token, operation) }

    private companion object {
        val FIRST_HUNDRED = mapOf("per_page" to "100")
    }

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = forgeCall {
        httpClient.gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name).toResult { gitHubRights(bodyAsText()) }
    }
}

@Serializable
private data class PermissionsJson(val admin: Boolean = false, val maintain: Boolean = false, val push: Boolean = false, val triage: Boolean = false)

@Serializable
private data class PermittedRepoJson(val permissions: PermissionsJson = PermissionsJson())

/** What a repository's answer says the token's account may do there (`permissions`, sent to whoever is signed in). */
internal fun gitHubRights(repoAnswer: String): RepoRights {
    val permissions = ForgeJson.decodeFromString<PermittedRepoJson>(repoAnswer).permissions
    return RepoRights(
        when {
            permissions.admin -> RepoAccess.ADMIN
            permissions.push || permissions.maintain -> RepoAccess.WRITE
            permissions.triage -> RepoAccess.TRIAGE
            else -> RepoAccess.NONE
        },
    )
}

@Serializable
private data class CommentJson(
    val id: Long,
    val user: UserJson? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String,
    val reactions: ReactionsJson? = null,
) {
    fun toModel() = TimelineItem.Comment(id, user?.toModel(), body.orEmpty(), Instant.parse(createdAt), reactions?.toModel().orEmpty())
}

@Serializable
internal data class UserJson(val login: String, @SerialName("avatar_url") val avatarUrl: String? = null) {
    fun toModel() = ForgeUser(login = login, name = null, avatarUrl = avatarUrl)
}

@Serializable
private data class MilestoneJson(val number: Long = 0, val title: String) {
    fun toModel() = Milestone(number, title)
}

@Serializable
private data class LabelJson(val name: String, val color: String? = null) {
    fun toModel() = Label(name, color)
}

/** One reaction, as GitHub answers the one made or already there. */

/**
 * Gives [reaction] to what [target] names in [repo] (an issue, an issue comment or a review comment), or takes it back
 * when the reader had given it: GitHub answers 200 instead of 201 then, with its id to delete it by. Answers what it
 * then counts, everyone's.
 */
internal suspend fun HttpClient.toggleGitHubReaction(apiBaseUrl: String, token: String, repo: RepoId, target: Array<String>, reaction: Reaction): ForgeResult<Map<Reaction, Int>> {
    val given = gitHubApi(
        apiBaseUrl, token, "repos", repo.owner, repo.name, *target, "reactions",
        method = HttpMethod.Post, body = buildJsonObject { put("content", reaction.gitHubContent) },
    )
    if (!given.status.isSuccess()) return given.failure()
    if (given.status.value == 200) {
        val taken = gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, *target, "reactions", given.body<ReactionJson>().id.toString(), method = HttpMethod.Delete)
        if (!taken.status.isSuccess()) return taken.failure()
    }
    return gitHubApi(apiBaseUrl, token, "repos", repo.owner, repo.name, *target).toResult { body<ReactedJson>().reactions?.toModel().orEmpty() }
}

private val Reaction.gitHubContent: String
    get() = when (this) {
        Reaction.THUMBS_UP -> "+1"
        Reaction.THUMBS_DOWN -> "-1"
        Reaction.LAUGH -> "laugh"
        Reaction.HOORAY -> "hooray"
        Reaction.CONFUSED -> "confused"
        Reaction.HEART -> "heart"
        Reaction.ROCKET -> "rocket"
        Reaction.EYES -> "eyes"
    }

@Serializable
private data class ReactionJson(val id: Long)

/** The part of an issue or a comment that counts its reactions. */
@Serializable
private data class ReactedJson(val reactions: ReactionsJson? = null)

@Serializable
private data class ReactionsJson(
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
private data class PullRefJson(val ref: String)

@Serializable
private data class PullJson(
    val draft: Boolean = false,
    val merged: Boolean = false,
    val base: PullRefJson,
    val head: PullRefJson,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
    val commits: Int = 0,
    @SerialName("requested_reviewers") val requestedReviewers: List<UserJson> = emptyList(),
    @SerialName("node_id") val nodeId: String? = null,
)

@Serializable
private data class IssueJson(
    // Only read from a created issue: one that was asked for is already numbered.
    val number: Int = 0,
    val title: String,
    val body: String? = null,
    val state: String,
    @SerialName("state_reason") val stateReason: String? = null,
    val user: UserJson? = null,
    val labels: List<LabelJson> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    val comments: Int = 0,
    val reactions: ReactionsJson? = null,
    @SerialName("pull_request") val pullRequest: kotlinx.serialization.json.JsonElement? = null,
    val locked: Boolean = false,
    val assignees: List<UserJson> = emptyList(),
    val milestone: MilestoneJson? = null,
    val type: IssueTypeJson? = null,
) {
    fun toModel(ref: IssueRef, pull: PullJson?) = IssueDetails(
        ref = ref,
        title = title,
        body = body?.ifBlank { null },
        state = when {
            pull?.merged == true -> IssueState.MERGED
            state == "closed" -> IssueState.CLOSED
            else -> IssueState.OPEN
        },
        stateReason = stateReason,
        author = user?.toModel(),
        labels = labels.map { it.toModel() },
        createdAt = Instant.parse(createdAt),
        closedAt = closedAt?.let(Instant::parse),
        comments = comments,
        reactions = reactions?.toModel().orEmpty(),
        pullRequest = pull?.let {
            PullRequestInfo(it.draft, it.merged, it.base.ref, it.head.ref, it.additions, it.deletions, it.changedFiles, it.commits, it.requestedReviewers.map { user -> user.toModel() })
        },
        isLocked = locked,
        assignees = assignees.map { it.toModel() },
        milestone = milestone?.toModel(),
        type = type?.toModel(),
    )
}

@Serializable
private data class IssueTypeJson(val name: String, val color: String? = null, val description: String? = null) {
    fun toModel() = IssueType(name, color, description?.ifBlank { null })
}

@Serializable
private data class RepositoryJson(@SerialName("full_name") val fullName: String)

@Serializable
private data class SourceIssueJson(
    val number: Int,
    val title: String,
    val repository: RepositoryJson? = null,
    @SerialName("pull_request") val pullRequest: kotlinx.serialization.json.JsonElement? = null,
) {
    /** Where it is: in [home] unless its repository says another. */
    fun ref(home: RepoId): IssueRef {
        val path = repository?.fullName?.split('/')?.takeIf { it.size == 2 }
        val repo = if (path == null) home else RepoId(path[0], path[1], home.forge)
        return IssueRef(repo, number, isPullRequest = pullRequest != null)
    }

    /** "#12 Its title", or "owner/name#12 Its title" when it is in another repository than [home]. */
    fun named(home: RepoId): String {
        val elsewhere = repository?.fullName?.takeUnless { it.equals(home.fullName, ignoreCase = true) }.orEmpty()
        return "$elsewhere#$number $title"
    }
}

@Serializable
private data class SourceJson(val issue: SourceIssueJson? = null)

@Serializable
private data class RenameJson(val from: String, val to: String)

/** A comment's node, as GraphQL tells whether it is hidden and why ("off-topic", "spam"...). */
private val TimelineNodesQuery.OnIssueComment.reason: HideReason?
    get() = HideReason.entries.firstOrNull { it.name.replace('_', '-').equals(minimizedReason, ignoreCase = true) }

/** An issue as the sub-issue endpoints answer it: its id names it to them. */
@Serializable
private data class SubIssueJson(val id: Long, val number: Int, val title: String = "", val state: String = "open", @SerialName("repository_url") val repositoryUrl: String? = null) {
    fun toModel(forge: ForgeInstance): LinkedIssue {
        val path = repositoryUrl?.substringAfter("/repos/", "")?.split('/')?.takeIf { it.size == 2 }
        val repo = RepoId(path?.get(0).orEmpty(), path?.get(1).orEmpty(), forge)
        return LinkedIssue(IssueRef(repo, number, isPullRequest = false), title, if (state == "closed") IssueState.CLOSED else IssueState.OPEN)
    }
}

/** A pull request that closes an issue once merged, wherever it is. */
private fun ClosingQuery.Node.toModel(forge: ForgeInstance) = LinkedIssue(
    IssueRef(RepoId(repository.owner.login, repository.name, forge), number, isPullRequest = true),
    title,
    when (state) {
        PullRequestState.MERGED -> IssueState.MERGED
        PullRequestState.CLOSED -> IssueState.CLOSED
        else -> IssueState.OPEN
    },
)

@Serializable
private data class CommitDetailJson(val commit: CommitMessageJson? = null)

@Serializable
private data class CommitMessageJson(val message: String? = null)

@Serializable
private data class CommitAuthorJson(val name: String? = null, val date: String? = null)

@Serializable
private data class TeamJson(val name: String)

@Serializable
private data class DismissedReviewJson(@SerialName("dismissal_message") val message: String? = null)

@Serializable
private data class ProjectCardJson(@SerialName("column_name") val columnName: String? = null)

/** The events that happen on a project board, which GraphQL names to a sign-in with the read:project scope. */
private val PROJECT_EVENTS = setOf("added_to_project_v2", "removed_from_project_v2", "project_v2_item_status_changed")

/** The events GraphQL tells more of than the REST timeline does, by their nodes. */
private val DETAILED_IN_GRAPHQL = setOf(
    "commented", "project_v2_item_status_changed", "marked_as_duplicate", "unmarked_as_duplicate", "deployed", "base_ref_changed",
)

@Serializable
private data class EventJson(
    val event: String,
    val id: Long? = null,
    val actor: UserJson? = null,
    val user: UserJson? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("state_reason") val stateReason: String? = null,
    val state: String? = null,
    val reactions: ReactionsJson? = null,
    val label: LabelJson? = null,
    val rename: RenameJson? = null,
    val source: SourceJson? = null,
    val sha: String? = null,
    val message: String? = null,
    val author: CommitAuthorJson? = null,
    val assignee: UserJson? = null,
    val milestone: MilestoneJson? = null,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("commit_id") val commitId: String? = null,
    @SerialName("commit_url") val commitUrl: String? = null,
    @SerialName("lock_reason") val lockReason: String? = null,
    @SerialName("requested_reviewer") val requestedReviewer: UserJson? = null,
    @SerialName("requested_team") val requestedTeam: TeamJson? = null,
    @SerialName("dismissed_review") val dismissedReview: DismissedReviewJson? = null,
    @SerialName("issue_type") val issueType: IssueTypeJson? = null,
    @SerialName("prev_issue_type") val previousIssueType: IssueTypeJson? = null,
    @SerialName("project_card") val projectCard: ProjectCardJson? = null,
    /** The conversation an event names: a linked pull request, a sub-issue, its parent, one it is blocked by or blocks. */
    val subject: SourceIssueJson? = null,
    @SerialName("sub_issue") val subIssue: SourceIssueJson? = null,
    @SerialName("parent_issue") val parentIssue: SourceIssueJson? = null,
    @SerialName("blocked_by") val blockedBy: SourceIssueJson? = null,
    val blocking: SourceIssueJson? = null,
) {
    /** The commit an event names, its page made from the API's address for it. */
    private fun namedCommit(): ClosingCommit? {
        val sha = commitId ?: return null
        val repo = commitUrl?.substringAfter("/repos/", "")?.substringBefore("/commits/")?.takeIf { it.count { c -> c == '/' } == 1 } ?: return null
        return ClosingCommit(sha, message = null, webUrl = "https://github.com/$repo/commit/$sha")
    }

    private fun event(event: ConversationEvent, at: Instant?, subject: String? = null, target: IssueRef? = null): TimelineItem? =
        at?.let { TimelineItem.Event(event, actor?.toModel(), subject, it, target) }

    /** An event that names another conversation: it says which ("#12 Its title", or with its repository when elsewhere), and opens it. */
    private fun linked(event: ConversationEvent, at: Instant?, issue: SourceIssueJson?, home: RepoId): TimelineItem? {
        issue ?: return null
        val ref = issue.ref(home)
        return event(event, at, issue.named(home), ref)
    }

    /** Null for events that are noise in a conversation (subscribed, mentioned...). [node] is what GraphQL told of it. */
    fun toModel(home: RepoId, number: Int, node: TimelineNodesQuery.Node? = null): TimelineItem? {
        val at = createdAt?.let(Instant::parse)
        return when (event) {
            "commented" -> TimelineItem.Comment(
                id = id ?: 0,
                author = (user ?: actor)?.toModel(),
                body = body.orEmpty(),
                createdAt = at ?: return null,
                reactions = reactions?.toModel().orEmpty(),
                nodeId = nodeId,
                hidden = node?.onIssueComment?.takeIf { it.isMinimized }?.reason,
                canHide = node?.onIssueComment?.viewerCanMinimize == true,
            )
            "reviewed" -> TimelineItem.Review(
                id = id ?: 0,
                author = user?.toModel(),
                state = when (state?.lowercase()) {
                    "approved" -> ReviewState.APPROVED
                    "changes_requested" -> ReviewState.CHANGES_REQUESTED
                    "dismissed" -> ReviewState.DISMISSED
                    else -> ReviewState.COMMENTED
                },
                body = body?.ifBlank { null },
                createdAt = submittedAt?.let(Instant::parse),
            )
            "closed" -> TimelineItem.StateChanged(StateChange.CLOSED, actor?.toModel(), stateReason, at ?: return null, namedCommit())
            "reopened" -> TimelineItem.StateChanged(StateChange.REOPENED, actor?.toModel(), stateReason, at ?: return null)
            "merged" -> TimelineItem.StateChanged(StateChange.MERGED, actor?.toModel(), null, at ?: return null)
            "labeled", "unlabeled" -> TimelineItem.Labeled(event == "labeled", label?.toModel() ?: return null, actor?.toModel(), at ?: return null)
            "renamed" -> TimelineItem.Renamed(rename?.from ?: return null, rename.to, actor?.toModel(), at ?: return null)
            "cross-referenced" -> {
                val issue = source?.issue ?: return null
                val repo = issue.repository?.fullName?.split('/')?.takeIf { it.size == 2 } ?: return null
                TimelineItem.CrossReferenced(
                    IssueRef(RepoId(repo[0], repo[1], ForgeInstance.GitHub), issue.number), issue.title, issue.pullRequest != null, actor?.toModel(), at ?: return null,
                )
            }
            // A commit that mentions it, pushed anywhere: a fork's push of it too.
            "referenced" -> TimelineItem.CommitReferenced(namedCommit() ?: return null, actor?.toModel(), at ?: return null)
            // Why, where it was said: "off-topic", "too heated", "resolved", "spam".
            "locked" -> event(ConversationEvent.LOCKED, at, lockReason?.ifBlank { null })
            "unlocked" -> event(ConversationEvent.UNLOCKED, at)
            "pinned" -> event(ConversationEvent.PINNED, at)
            "unpinned" -> event(ConversationEvent.UNPINNED, at)
            "assigned" -> event(ConversationEvent.ASSIGNED, at, assignee?.login ?: return null)
            "unassigned" -> event(ConversationEvent.UNASSIGNED, at, assignee?.login ?: return null)
            "milestoned" -> event(ConversationEvent.MILESTONED, at, milestone?.title ?: return null)
            "demilestoned" -> event(ConversationEvent.DEMILESTONED, at, milestone?.title ?: return null)
            "transferred" -> event(ConversationEvent.TRANSFERRED, at)
            "converted_to_discussion" -> event(ConversationEvent.CONVERTED_TO_DISCUSSION, at)
            // GitHub doesn't name the branch here: it is the pull request's own.
            "head_ref_deleted" -> event(ConversationEvent.BRANCH_DELETED, at)
            "head_ref_restored" -> event(ConversationEvent.BRANCH_RESTORED, at)
            "head_ref_force_pushed" -> event(ConversationEvent.FORCE_PUSHED, at, commitId?.take(7))
            "committed" -> TimelineItem.Committed(sha ?: return null, message.orEmpty(), author?.name, author?.date?.let(Instant::parse))
            // A person or a team.
            "review_requested" -> event(ConversationEvent.REVIEW_REQUESTED, at, requestedReviewer?.login ?: requestedTeam?.name ?: return null)
            "review_request_removed" -> event(ConversationEvent.REVIEW_REQUEST_REMOVED, at, requestedReviewer?.login ?: requestedTeam?.name ?: return null)
            "review_dismissed" -> event(ConversationEvent.REVIEW_DISMISSED, at, dismissedReview?.message?.ifBlank { null })
            "ready_for_review" -> event(ConversationEvent.READY_FOR_REVIEW, at)
            "convert_to_draft" -> event(ConversationEvent.CONVERTED_TO_DRAFT, at)
            // REST names neither side: GraphQL tells both. The original's timeline holds it (zed-industries/zed#66369, 2026-11-02).
            "marked_as_duplicate", "unmarked_as_duplicate" -> {
                val marked = event == "marked_as_duplicate"
                val sides = node?.onMarkedAsDuplicateEvent?.let { it.canonical?.canonical to it.duplicate?.canonical }
                    ?: node?.onUnmarkedAsDuplicateEvent?.let { it.canonical?.canonical to it.duplicate?.canonical }
                val canonical = sides?.first?.toSource()
                val duplicate = sides?.second?.toSource()
                if (canonical != null && duplicate != null && canonical.ref(home).number == number && canonical.ref(home).repo == home) {
                    event(if (marked) ConversationEvent.DUPLICATE_ADDED else ConversationEvent.DUPLICATE_REMOVED, at, duplicate.named(home), duplicate.ref(home))
                } else {
                    event(if (marked) ConversationEvent.MARKED_DUPLICATE else ConversationEvent.UNMARKED_DUPLICATE, at, canonical?.named(home), canonical?.ref(home))
                }
            }
            // Its board isn't named without the read:project scope; its new column is (none when it was cleared).
            "added_to_project_v2", "added_to_project" -> event(ConversationEvent.ADDED_TO_PROJECT, at)
            "removed_from_project_v2", "removed_from_project" -> event(ConversationEvent.REMOVED_FROM_PROJECT, at)
            "project_v2_item_status_changed" -> event(ConversationEvent.PROJECT_STATUS_CHANGED, at, node?.onProjectV2ItemStatusChangedEvent?.status?.ifBlank { null })
            "moved_columns_in_project" -> event(ConversationEvent.PROJECT_STATUS_CHANGED, at, projectCard?.columnName?.ifBlank { null })
            "comment_deleted" -> event(ConversationEvent.COMMENT_DELETED, at)
            "issue_comment_pinned" -> event(ConversationEvent.COMMENT_PINNED, at)
            "issue_comment_unpinned" -> event(ConversationEvent.COMMENT_UNPINNED, at)
            "deployed" -> event(ConversationEvent.DEPLOYED, at, node?.onDeployedEvent?.deployment?.environment?.ifBlank { null })
            "auto_merge_enabled", "auto_squash_enabled", "auto_rebase_enabled" -> event(ConversationEvent.AUTO_MERGE_ENABLED, at)
            "auto_merge_disabled" -> event(ConversationEvent.AUTO_MERGE_DISABLED, at)
            "added_to_merge_queue" -> event(ConversationEvent.ADDED_TO_MERGE_QUEUE, at)
            "removed_from_merge_queue" -> event(ConversationEvent.REMOVED_FROM_MERGE_QUEUE, at)
            "base_ref_changed" -> event(ConversationEvent.BASE_CHANGED, at, node?.onBaseRefChangedEvent?.currentRefName?.ifBlank { null })
            "connected" -> linked(ConversationEvent.LINKED, at, subject, home)
            "disconnected" -> linked(ConversationEvent.UNLINKED, at, subject, home)
            "user_blocked" -> event(ConversationEvent.USER_BLOCKED, at)
            "issue_type_added", "issue_type_changed" -> event(ConversationEvent.TYPE_SET, at, issueType?.name ?: return null)
            "issue_type_removed" -> event(ConversationEvent.TYPE_REMOVED, at, (issueType ?: previousIssueType)?.name)
            "sub_issue_added" -> linked(ConversationEvent.SUB_ISSUE_ADDED, at, subIssue, home)
            "sub_issue_removed" -> linked(ConversationEvent.SUB_ISSUE_REMOVED, at, subIssue, home)
            "parent_issue_added" -> linked(ConversationEvent.PARENT_ADDED, at, parentIssue, home)
            "parent_issue_removed" -> linked(ConversationEvent.PARENT_REMOVED, at, parentIssue, home)
            // Blocked by another is depending on it, as Forgejo says it.
            "blocked_by_added" -> linked(ConversationEvent.DEPENDENCY_ADDED, at, blockedBy, home)
            "blocked_by_removed" -> linked(ConversationEvent.DEPENDENCY_REMOVED, at, blockedBy, home)
            "blocking_added" -> linked(ConversationEvent.BLOCKING_ADDED, at, blocking, home)
            "blocking_removed" -> linked(ConversationEvent.BLOCKING_REMOVED, at, blocking, home)
            else -> null
        }
    }
}

/** The conversation as GraphQL names a duplicate's original. */
private fun fr.arthurbrugiere.forgeline.forge.github.graphql.fragment.Canonical.toSource(): SourceIssueJson? {
    onIssue?.let { return SourceIssueJson(it.number, it.title, RepositoryJson(it.repository.nameWithOwner)) }
    onPullRequest?.let { return SourceIssueJson(it.number, it.title, RepositoryJson(it.repository.nameWithOwner), pullRequest = kotlinx.serialization.json.JsonNull) }
    return null
}

/** A file of `.github/ISSUE_TEMPLATE`, as the contents API lists it, and sends it alone ([content], in base64). */
@Serializable
private data class TemplateFileJson(val name: String, val path: String, val type: String = "file", val content: String? = null, val encoding: String? = null) {
    fun text(): String? = content?.takeIf { encoding == "base64" }?.let { Base64.getMimeDecoder().decode(it).decodeToString() }
}

/** The file names GitHub reads an issue form from. */
private val FORM_EXTENSIONS = setOf("yml", "yaml")
