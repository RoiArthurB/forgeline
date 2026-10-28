package fr.arthurbrugiere.forgeline.forge.github

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
            val items = withCommitMessages(token, body<List<EventJson>>().mapNotNull { it.toModel() }.withoutCloseOfMerge())
            TimelinePage(if (token == null) items else withHiddenState(token, items), nextPage(), lastPage())
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
     * Which comments are hidden, and whether the reader may hide them: GraphQL tells, by their nodes, all at once. A
     * timeline that can't be told so reads as it was.
     */
    private suspend fun withHiddenState(token: String, items: List<TimelineItem>): List<TimelineItem> {
        val ids = items.mapNotNull { (it as? TimelineItem.Comment)?.nodeId }.take(100)
        if (ids.isEmpty()) return items
        val answer = runCatching {
            httpClient.gitHubGraphQl(apiBaseUrl, token, HIDDEN_QUERY, buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }, partial = true)
        }.getOrNull() as? ForgeResult.Success ?: return items
        val nodes = (answer.value["nodes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { node -> runCatching { ForgeJson.decodeFromJsonElement<HiddenNodeJson>(node) }.getOrNull() }
            .associateBy { it.id }
        return items.map { item ->
            val node = (item as? TimelineItem.Comment)?.nodeId?.let(nodes::get) ?: return@map item
            item.copy(hidden = node.reason.takeIf { node.isMinimized }, canHide = node.viewerCanMinimize)
        }
    }

    override val keepsSavedReplies: Boolean = true

    /** The account's own, read with its read:user scope, which Forgeline's sign-in asks for. */
    override suspend fun savedReplies(token: String): ForgeResult<List<SavedReply>> = forgeCall {
        httpClient.gitHubGraphQl(apiBaseUrl, token, "query { viewer { savedReplies(first: 50) { nodes { title body } } } }")
    }.map { data ->
        val nodes = ((data["viewer"] as? JsonObject)?.get("savedReplies") as? JsonObject)?.get("nodes")
        nodes?.let { runCatching { ForgeJson.decodeFromJsonElement<List<SavedReplyJson>>(it) }.getOrNull() }.orEmpty().map { SavedReply(it.title, it.body) }
    }

    override val typesIssues: Boolean = true

    /** Through GraphQL: its REST list is the organisation's, and asks for its administration. */
    override suspend fun issueTypes(token: String, repo: RepoId): ForgeResult<List<IssueType>> = forgeCall {
        httpClient.gitHubGraphQl(
            apiBaseUrl, token, TYPES_QUERY,
            buildJsonObject {
                put("owner", repo.owner)
                put("name", repo.name)
            },
        )
    }.map { data ->
        val nodes = ((data["repository"] as? JsonObject)?.get("issueTypes") as? JsonObject)?.get("nodes")
        nodes?.let { runCatching { ForgeJson.decodeFromJsonElement<List<IssueTypeJson>>(it) }.getOrNull() }.orEmpty().map { it.toModel() }
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
        val answer = forgeCall {
            httpClient.gitHubGraphQl(
                apiBaseUrl, token, CLOSING_QUERY,
                buildJsonObject {
                    put("owner", ref.repo.owner)
                    put("name", ref.repo.name)
                    put("number", ref.number)
                },
            )
        }
        return answer.map { data ->
            val nodes = ((data["repository"] as? JsonObject)?.get("issue") as? JsonObject)?.get("closedByPullRequestsReferences")?.let { it as? JsonObject }?.get("nodes")
            nodes?.let { runCatching { ForgeJson.decodeFromJsonElement<List<ClosingPullJson>>(it) }.getOrNull() }.orEmpty().map { it.toModel(ref.repo.forge) }
        }
    }

    override val hidesComments: Boolean = true

    override suspend fun setCommentHidden(token: String, ref: IssueRef, comment: TimelineItem.Comment, reason: HideReason?): ForgeResult<Unit> {
        val id = comment.nodeId ?: return ForgeResult.Failure(ForgeError.Unsupported)
        val mutation = if (reason == null) {
            "mutation(\$id: ID!) { done: unminimizeComment(input: {subjectId: \$id}) { clientMutationId } }"
        } else {
            "mutation(\$id: ID!) { done: minimizeComment(input: {subjectId: \$id, classifier: ${reason.name}}) { clientMutationId } }"
        }
        return forgeCall { httpClient.gitHubGraphQl(apiBaseUrl, token, mutation, buildJsonObject { put("id", id) }) }.map { }
    }

    /** A closing event names its commit only: each one's message is asked of it, all at once. */
    private suspend fun withCommitMessages(token: String?, items: List<TimelineItem>): List<TimelineItem> = coroutineScope {
        items.map { item ->
            val commit = (item as? TimelineItem.StateChanged)?.commit ?: return@map async { item }
            async { item.copy(commit = commit.copy(message = commitMessage(token, commit) ?: return@async item)) }
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

    private suspend fun markdownTemplates(token: String, repo: RepoId): ForgeResult<List<IssueTemplate>> = graphQl(
        token,
        "query(\$owner: String!, \$name: String!) { repository(owner: \$owner, name: \$name) { issueTemplates { filename name about title body labels(first: 20) { nodes { name } } } } }",
        buildJsonObject {
            put("owner", repo.owner)
            put("name", repo.name)
        },
    ).map { data ->
        val templates = data.objectAt("repository")?.get("issueTemplates")?.takeUnless { it is JsonNull } ?: return@map emptyList()
        ForgeJson.decodeFromJsonElement<List<IssueTemplateJson>>(templates).map {
            IssueTemplate(it.filename ?: it.name, it.name, it.about?.ifBlank { null }, it.title.orEmpty(), it.body.orEmpty(), it.labels?.nodes.orEmpty().map { label -> label.name })
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
        val mutation = if (draft) "convertPullRequestToDraft" else "markPullRequestReadyForReview"
        return graphQl(token, "mutation(\$id: ID!) { $mutation(input: {pullRequestId: \$id}) { clientMutationId } }", buildJsonObject { put("id", node) }).map { }
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
        node(token, ref).map { it.getValue("isPinned").jsonPrimitive.booleanOrNull == true }

    override suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean): ForgeResult<Unit> = onNode(token, ref) { id ->
        val mutation = if (pinned) "pinIssue" else "unpinIssue"
        graphQl(token, "mutation(\$id: ID!) { $mutation(input: {issueId: \$id}) { clientMutationId } }", buildJsonObject { put("id", id) }).map { }
    }

    // A subscription is only in GitHub's GraphQL API too, on an issue or a pull request alike.

    override suspend fun isSubscribed(token: String, login: String, ref: IssueRef): ForgeResult<Boolean> =
        subscribable(token, ref).map { it.getValue("viewerSubscription").jsonPrimitive.content == "SUBSCRIBED" }

    override suspend fun setSubscribed(token: String, login: String, ref: IssueRef, subscribed: Boolean): ForgeResult<Unit> =
        when (val found = subscribable(token, ref)) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success -> graphQl(
                token,
                "mutation(\$id: ID!, \$state: SubscriptionState!) { updateSubscription(input: {subscribableId: \$id, state: \$state}) { clientMutationId } }",
                buildJsonObject {
                    put("id", found.value.getValue("id").jsonPrimitive.content)
                    put("state", if (subscribed) "SUBSCRIBED" else "UNSUBSCRIBED")
                },
            ).map { }
        }

    private suspend fun subscribable(token: String, ref: IssueRef): ForgeResult<JsonObject> {
        val found = graphQl(
            token,
            "query(\$owner: String!, \$name: String!, \$number: Int!) { repository(owner: \$owner, name: \$name) { issueOrPullRequest(number: \$number) { " +
                "... on Issue { id viewerSubscription } ... on PullRequest { id viewerSubscription } } } }",
            buildJsonObject {
                put("owner", ref.repo.owner)
                put("name", ref.repo.name)
                put("number", ref.number)
            },
        )
        return when (found) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success -> found.value.objectAt("repository", "issueOrPullRequest")?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = onNode(token, ref) { id ->
        graphQl(token, "mutation(\$id: ID!) { deleteIssue(input: {issueId: \$id}) { clientMutationId } }", buildJsonObject { put("id", id) }).map { }
    }

    override suspend fun transfer(token: String, ref: IssueRef, to: RepoId): ForgeResult<IssueRef> {
        val found = graphQl(
            token,
            "query(\$owner: String!, \$name: String!, \$number: Int!, \$toOwner: String!, \$toName: String!) { " +
                "from: repository(owner: \$owner, name: \$name) { issue(number: \$number) { id } } " +
                "to: repository(owner: \$toOwner, name: \$toName) { id } }",
            buildJsonObject {
                put("owner", ref.repo.owner)
                put("name", ref.repo.name)
                put("number", ref.number)
                put("toOwner", to.owner)
                put("toName", to.name)
            },
        )
        val ids = when (found) {
            is ForgeResult.Failure -> return found
            is ForgeResult.Success -> found.value
        }
        val issueId = ids.objectAt("from", "issue")?.get("id")?.jsonPrimitive?.contentOrNull
        val repositoryId = ids.objectAt("to")?.get("id")?.jsonPrimitive?.contentOrNull
        if (issueId == null || repositoryId == null) return ForgeResult.Failure(ForgeError.Http(404, null))
        return graphQl(
            token,
            "mutation(\$issue: ID!, \$repository: ID!) { transferIssue(input: {issueId: \$issue, repositoryId: \$repository}) { " +
                "issue { number repository { name owner { login } } } } }",
            buildJsonObject {
                put("issue", issueId)
                put("repository", repositoryId)
            },
        ).map { data ->
            val moved = data.objectAt("transferIssue", "issue") ?: throw NoSuchElementException("transferIssue.issue")
            val repository = moved.getValue("repository").jsonObject
            IssueRef(
                RepoId(repository.getValue("owner").jsonObject.getValue("login").jsonPrimitive.content, repository.getValue("name").jsonPrimitive.content, ref.repo.forge),
                moved.getValue("number").jsonPrimitive.int,
            )
        }
    }

    /** The issue's node: its id and whether it is pinned. 404 when GitHub doesn't know it, or it is a pull request. */
    private suspend fun node(token: String, ref: IssueRef): ForgeResult<JsonObject> {
        val found = graphQl(
            token,
            "query(\$owner: String!, \$name: String!, \$number: Int!) { repository(owner: \$owner, name: \$name) { issue(number: \$number) { id isPinned } } }",
            buildJsonObject {
                put("owner", ref.repo.owner)
                put("name", ref.repo.name)
                put("number", ref.number)
            },
        )
        return when (found) {
            is ForgeResult.Failure -> found
            is ForgeResult.Success -> found.value.objectAt("repository", "issue")?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, null))
        }
    }

    private suspend fun <T> onNode(token: String, ref: IssueRef, act: suspend (id: String) -> ForgeResult<T>): ForgeResult<T> =
        when (val node = node(token, ref)) {
            is ForgeResult.Failure -> node
            is ForgeResult.Success -> act(node.value.getValue("id").jsonPrimitive.content)
        }

    private suspend fun graphQl(token: String, query: String, variables: JsonObject): ForgeResult<JsonObject> =
        forgeCall { httpClient.gitHubGraphQl(apiBaseUrl, token, query, variables) }

    private fun JsonObject.objectAt(vararg path: String): JsonObject? =
        path.fold<String, JsonObject?>(this) { at, key -> at?.get(key)?.takeUnless { it is JsonNull }?.jsonObject }

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
)

@Serializable
private data class SourceJson(val issue: SourceIssueJson? = null)

@Serializable
private data class RenameJson(val from: String, val to: String)

/** A comment's node, as GraphQL tells whether it is hidden and why ("off-topic", "spam"...). */
@Serializable
private data class HiddenNodeJson(val id: String, val isMinimized: Boolean = false, val minimizedReason: String? = null, val viewerCanMinimize: Boolean = false) {
    val reason: HideReason? get() = HideReason.entries.firstOrNull { it.name.replace('_', '-').equals(minimizedReason, ignoreCase = true) }
}

/** An issue as the sub-issue endpoints answer it: its id names it to them. */
@Serializable
private data class SubIssueJson(val id: Long, val number: Int, val title: String = "", val state: String = "open", @SerialName("repository_url") val repositoryUrl: String? = null) {
    fun toModel(forge: ForgeInstance): LinkedIssue {
        val path = repositoryUrl?.substringAfter("/repos/", "")?.split('/')?.takeIf { it.size == 2 }
        val repo = RepoId(path?.get(0).orEmpty(), path?.get(1).orEmpty(), forge)
        return LinkedIssue(IssueRef(repo, number, isPullRequest = false), title, if (state == "closed") IssueState.CLOSED else IssueState.OPEN)
    }
}

@Serializable
private data class SavedReplyJson(val title: String, val body: String = "")

private const val TYPES_QUERY =
    "query Types(\$owner: String!, \$name: String!) { repository(owner: \$owner, name: \$name) { issueTypes(first: 25) { nodes { name color description } } } }"

private const val CLOSING_QUERY = "query Closing(\$owner: String!, \$name: String!, \$number: Int!) { repository(owner: \$owner, name: \$name) { issue(number: \$number) { " +
    "closedByPullRequestsReferences(first: 10, includeClosedPrs: true) { nodes { number title state repository { name owner { login } } } } } } }"

/** A pull request that closes an issue once merged, wherever it is. */
@Serializable
private data class ClosingPullJson(val number: Int, val title: String = "", val state: String = "OPEN", val repository: ClosingRepoJson) {
    fun toModel(forge: ForgeInstance) = LinkedIssue(
        IssueRef(RepoId(repository.owner.login, repository.name, forge), number, isPullRequest = true),
        title,
        when (state) {
            "MERGED" -> IssueState.MERGED
            "CLOSED" -> IssueState.CLOSED
            else -> IssueState.OPEN
        },
    )
}

@Serializable
private data class ClosingRepoJson(val name: String, val owner: ClosingOwnerJson)

@Serializable
private data class ClosingOwnerJson(val login: String)

private const val HIDDEN_QUERY = "query Hidden(\$ids: [ID!]!) { nodes(ids: \$ids) { ... on IssueComment { id isMinimized minimizedReason viewerCanMinimize } } }"

@Serializable
private data class CommitDetailJson(val commit: CommitMessageJson? = null)

@Serializable
private data class CommitMessageJson(val message: String? = null)

@Serializable
private data class CommitAuthorJson(val name: String? = null, val date: String? = null)

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
) {
    /** The commit a closing event names, its page made from the API's address for it. */
    private fun closingCommit(): ClosingCommit? {
        val sha = commitId ?: return null
        val repo = commitUrl?.substringAfter("/repos/", "")?.substringBefore("/commits/")?.takeIf { it.count { c -> c == '/' } == 1 } ?: return null
        return ClosingCommit(sha, message = null, webUrl = "https://github.com/$repo/commit/$sha")
    }

    private fun event(event: ConversationEvent, at: Instant?, subject: String? = null): TimelineItem? =
        at?.let { TimelineItem.Event(event, actor?.toModel(), subject, it) }

    /** Null for events that are noise in a conversation (subscribed, mentioned, review_requested...). */
    fun toModel(): TimelineItem? {
        val at = createdAt?.let(Instant::parse)
        return when (event) {
            "commented" -> TimelineItem.Comment(
                id = id ?: 0,
                author = (user ?: actor)?.toModel(),
                body = body.orEmpty(),
                createdAt = at ?: return null,
                reactions = reactions?.toModel().orEmpty(),
                nodeId = nodeId,
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
            "closed" -> TimelineItem.StateChanged(StateChange.CLOSED, actor?.toModel(), stateReason, at ?: return null, closingCommit())
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
            "locked" -> event(ConversationEvent.LOCKED, at)
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
            "committed" -> TimelineItem.Committed(sha ?: return null, message.orEmpty(), author?.name, author?.date?.let(Instant::parse))
            else -> null
        }
    }
}

@Serializable
private data class IssueTemplateJson(
    val filename: String? = null,
    val name: String,
    val about: String? = null,
    val title: String? = null,
    val body: String? = null,
    val labels: TemplateLabelsJson? = null,
)

@Serializable
private data class TemplateLabelsJson(val nodes: List<TemplateLabelJson> = emptyList())

@Serializable
private data class TemplateLabelJson(val name: String)

/** A file of `.github/ISSUE_TEMPLATE`, as the contents API lists it, and sends it alone ([content], in base64). */
@Serializable
private data class TemplateFileJson(val name: String, val path: String, val type: String = "file", val content: String? = null, val encoding: String? = null) {
    fun text(): String? = content?.takeIf { encoding == "base64" }?.let { Base64.getMimeDecoder().decode(it).decodeToString() }
}

/** The file names GitHub reads an issue form from. */
private val FORM_EXTENSIONS = setOf("yml", "yaml")
