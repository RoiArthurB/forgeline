package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.FormField
import fr.arthurbrugiere.forgeline.core.forge.IssueApi
import fr.arthurbrugiere.forgeline.core.forge.issueFormText
import fr.arthurbrugiere.forgeline.core.model.AttachmentRule
import fr.arthurbrugiere.forgeline.core.model.CloseReason
import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ContactLink
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueChooser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueTemplate
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.LinkedIssue
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoRights
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimeTracking
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.forge.common.ForgeJson
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Attachment
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Comment
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Issue
import fr.arthurbrugiere.forgeline.forge.forgejo.api.IssueConfig
import fr.arthurbrugiere.forgeline.forge.forgejo.api.IssueFormField
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PullRequest
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PullReview
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Repository
import fr.arthurbrugiere.forgeline.forge.forgejo.api.StopWatch
import fr.arthurbrugiere.forgeline.forge.forgejo.api.TimelineComment
import fr.arthurbrugiere.forgeline.forge.forgejo.api.TrackedTime
import fr.arthurbrugiere.forgeline.forge.forgejo.api.User
import fr.arthurbrugiere.forgeline.forge.forgejo.api.WatchInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import fr.arthurbrugiere.forgeline.forge.forgejo.api.IssueTemplate as ApiIssueTemplate
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Label as ApiLabel
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Milestone as ApiMilestone
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Reaction as ApiReaction

class ForgejoIssueApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : IssueApi {

    override suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails> = forgeCall {
        coroutineScope {
            val reactions = async { get(token, ref, "issues", ref.number.toString(), "reactions") }
            // The pull request's branches and size live on its own endpoint. Asked alongside the issue, before knowing
            // it's a pull request: a plain issue wastes one 404, a pull request saves a round trip to a far forge.
            val pullAnswer = async { get(token, ref, "pulls", ref.number.toString()) }
            val response = get(token, ref, "issues", ref.number.toString())
            if (response.status != HttpStatusCode.OK) return@coroutineScope response.failure()
            val issue = response.body<Issue>()
            val pull = if (issue.isPullRequest) {
                val details = pullAnswer.await()
                if (details.status != HttpStatusCode.OK) return@coroutineScope details.failure()
                details.body<PullRequest>()
            } else {
                // Not a pull request: don't wait for the 404.
                pullAnswer.cancel()
                null
            }
            // No reactions answers null, not an empty list (Codeberg, 2026-09-29).
            val counts = reactions.await().takeIf { it.status == HttpStatusCode.OK }
                ?.let { runCatching { it.body<List<ApiReaction>?>() }.getOrNull() }.orEmpty()
                .mapNotNull { reaction(it.content) }.groupingBy { it }.eachCount()
            ForgeResult.Success(issue.toDetails(ref, pull, counts))
        }
    }

    /** The issue endpoint names pull requests too: no second call for the title. */
    override suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = forgeCall {
        get(token, ref, "issues", ref.number.toString()).toResult { required(body<Issue>().title, "an issue's title") }
    }

    override suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage> = forgeCall {
        coroutineScope {
            // A review entry doesn't say whether it approved; the pull request's reviews do. Asked alongside the
            // timeline, before knowing there are reviews: one round trip instead of two.
            val reviews = async { reviewStates(token, ref) }
            timelinePage(token, ref, page) { entries -> if (entries.any { it.kind == "review" }) reviews.await() else emptyMap() }
                .also { reviews.cancel() }
        }
    }

    override suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "comments",
            method = HttpMethod.Post, body = buildJsonObject { put("body", body) },
        ).toResult {
            val created = body<Comment>()
            TimelineItem.Comment(required(created.id, "a comment's id"), created.user?.toModel(), created.body.orEmpty(), instant(created.createdAt) ?: Instant.now(), emptyMap())
        }
    }

    override suspend fun editComment(token: String, ref: IssueRef, commentId: Long, body: String): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", "comments", commentId.toString(),
            method = HttpMethod.Patch, body = buildJsonObject { put("body", body) },
        ).toResult { }
    }

    override suspend fun deleteComment(token: String, ref: IssueRef, commentId: Long): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", "comments", commentId.toString(),
            method = HttpMethod.Delete,
        ).toResult { }
    }

    /** A pull request is an issue to this endpoint: one call renames either. */
    override suspend fun edit(token: String, ref: IssueRef, title: String, body: String): ForgeResult<Unit> = edit(token, ref) {
        put("title", title)
        put("body", body)
    }

    /**
     * Forgejo lists who gave what: the reader's own is looked for there, then given or taken back by its name. The
     * counts are that list's, with the change just made.
     */
    override suspend fun toggleReaction(token: String, login: String, ref: IssueRef, commentId: Long?, reaction: Reaction): ForgeResult<Map<Reaction, Int>> = forgeCall {
        val target = if (commentId == null) arrayOf("issues", ref.number.toString(), "reactions") else arrayOf("issues", "comments", commentId.toString(), "reactions")
        val listed = get(token, ref, *target)
        if (listed.status != HttpStatusCode.OK) return@forgeCall listed.failure()
        // No reactions answers null, not an empty list.
        val given = listed.body<List<ApiReaction>?>().orEmpty()
        val mine = given.any { reaction(it.content) == reaction && it.user?.login.equals(login, ignoreCase = true) }
        val name = given.firstOrNull { reaction(it.content) == reaction }?.content ?: reaction.content
        val changed = httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, *target,
            method = if (mine) HttpMethod.Delete else HttpMethod.Post, body = buildJsonObject { put("content", name) },
        )
        if (!changed.status.isSuccess()) return@forgeCall changed.failure()
        val counts = given.mapNotNull { reaction(it.content) }.groupingBy { it }.eachCount()
        val now = (counts[reaction] ?: 0) + if (mine) -1 else 1
        ForgeResult.Success(if (now > 0) counts + (reaction to now) else counts - reaction)
    }

    private val Reaction.content: String
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

    /** Forgejo keeps a comment's files with the conversation, which its author and the repository's writers may add to. */
    override val attachments: AttachmentRule = AttachmentRule.AUTHOR_OR_WRITER

    override suspend fun attach(token: String, ref: IssueRef, name: String, mimeType: String, bytes: ByteArray): ForgeResult<String> = forgeCall {
        httpClient.post {
            url {
                takeFrom("${forge.webUrl}/api/v1")
                appendPathSegments("repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "assets")
            }
            parameter("name", name)
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.Authorization, "token $token")
            setBody(MultiPartFormDataContent(formData { append("attachment", bytes, fileHeaders(name, mimeType)) }))
        }.toResult {
            val kept = body<Attachment>()
            "![${altText(kept.name ?: name)}](${required(kept.browserDownloadUrl, "a file's address")})"
        }
    }

    /**
     * Forgejo opens an issue with labels by their ids only: [labels] are put on it once it is open, by name, which its
     * labels endpoint takes. A reader who may not label is refused that, and the issue stays open without them.
     */
    override suspend fun create(token: String, repo: RepoId, title: String, body: String, labels: List<String>): ForgeResult<IssueDetails> = forgeCall {
        val created = httpClient.forgejoApi(
            forge, token, "repos", repo.owner, repo.name, "issues",
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("title", title)
                put("body", body)
            },
        ).toResult { body<Issue>().let { it.toDetails(IssueRef(repo, required(it.number, "an issue's number").toInt()), pull = null, reactions = emptyMap()) } }
        if (created !is ForgeResult.Success || labels.isEmpty()) return@forgeCall created
        when (setLabels(token, created.value.ref, labels)) {
            is ForgeResult.Success -> ForgeResult.Success(created.value.copy(labels = labels.map { Label(it, null) }))
            is ForgeResult.Failure -> created
        }
    }

    /** Forgejo reads its templates' `config.yml` itself, and answers what it says. */
    override suspend fun issueChooser(token: String, repo: RepoId): ForgeResult<IssueChooser> = forgeCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "issue_config").toResult { body<IssueConfig>().toModel() }
    }

    /**
     * A repository's templates, Markdown ones as they are and forms as the Markdown the issue would have once filled:
     * each field a heading over its default answer, its help kept as a comment that doesn't show once sent.
     */
    override suspend fun issueTemplates(token: String, repo: RepoId): ForgeResult<List<IssueTemplate>> = forgeCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "issue_templates").toResult {
            body<List<ApiIssueTemplate>>().map { template ->
                val name = required(template.name, "a template's name")
                IssueTemplate(
                    key = template.fileName ?: name,
                    name = name,
                    about = template.about?.ifBlank { null },
                    title = template.title.orEmpty(),
                    body = template.content?.ifBlank { null } ?: formToMarkdown(template.body.orEmpty()),
                    labels = template.labels.orEmpty(),
                )
            }
        }
    }

    /** A pull request is an issue to this endpoint: one call closes either. */

    /** Forgejo keeps no reason for closing: [reason] is left out. */
    override suspend fun setOpen(token: String, ref: IssueRef, open: Boolean, reason: CloseReason?): ForgeResult<Unit> =
        edit(token, ref) { put("state", if (open) "open" else "closed") }

    private suspend fun edit(token: String, ref: IssueRef, fields: JsonObjectBuilder.() -> Unit): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(),
            method = HttpMethod.Patch, body = buildJsonObject(fields),
        ).toResult { }
    }

    /** Forgejo's API neither locks a conversation nor moves an issue to another repository (16.0). */
    override val actions: Set<ConversationAction> = setOf(
        ConversationAction.LABELS, ConversationAction.ASSIGNEES, ConversationAction.MILESTONE, ConversationAction.PIN, ConversationAction.DELETE,
        ConversationAction.DUE_DATE, ConversationAction.TIME_TRACKING, ConversationAction.DEPENDENCIES,
        ConversationAction.REVIEWERS, ConversationAction.DRAFT,
    )

    /**
     * The day is sent at midnight UTC: Forgejo keeps the end of that day. Taking the date away has its own field,
     * since a missing date means "leave it".
     */
    override suspend fun setDueDate(token: String, ref: IssueRef, date: LocalDate?): ForgeResult<Unit> = edit(token, ref) {
        if (date == null) put("unset_due_date", true) else put("due_date", "${date}T00:00:00Z")
    }

    /** Everyone's time on the conversation, from its first page of entries, and the signed-in user's timer if it runs here. */
    override suspend fun timeTracking(token: String, ref: IssueRef): ForgeResult<TimeTracking> = forgeCall {
        coroutineScope {
            val timers = async { httpClient.forgejoApi(forge, token, "user", "stopwatches") }
            val times = get(token, ref, "issues", ref.number.toString(), "times", query = FIRST_PAGE)
            if (times.status != HttpStatusCode.OK) {
                timers.cancel()
                return@coroutineScope times.failure()
            }
            val running = timers.await().takeIf { it.status == HttpStatusCode.OK }?.body<List<StopWatch>>().orEmpty()
                .firstOrNull { it.issueIndex == ref.number.toLong() && it.repoName.equals(ref.repo.name, ignoreCase = true) && it.repoOwnerName.equals(ref.repo.owner, ignoreCase = true) }
            ForgeResult.Success(TimeTracking(times.body<List<TrackedTime>>().sumOf { it.time ?: 0 }, running?.let { instant(it.created) }))
        }
    }

    override suspend fun setTimerRunning(token: String, ref: IssueRef, running: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "stopwatch", if (running) "start" else "stop",
            method = HttpMethod.Post,
        ).toResult { }
    }

    override suspend fun addTime(token: String, ref: IssueRef, seconds: Long): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "times",
            method = HttpMethod.Post, body = buildJsonObject { put("time", seconds) },
        ).toResult { }
    }

    override suspend fun dependencies(token: String, ref: IssueRef): ForgeResult<List<LinkedIssue>> = forgeCall {
        get(token, ref, "issues", ref.number.toString(), "dependencies").toResult {
            body<List<Issue>>().map { issue ->
                // A dependency can live in another repository, which it then names.
                val repo = issue.repository?.let { RepoId(required(it.owner, "a repository's owner"), required(it.name, "a repository's name"), forge) } ?: ref.repo
                LinkedIssue(IssueRef(repo, required(issue.number, "an issue's number").toInt()), required(issue.title, "an issue's title"), issue.state())
            }
        }
    }

    override suspend fun addDependency(token: String, ref: IssueRef, on: IssueRef): ForgeResult<Unit> = dependency(token, ref, on, HttpMethod.Post)

    override suspend fun removeDependency(token: String, ref: IssueRef, on: IssueRef): ForgeResult<Unit> = dependency(token, ref, on, HttpMethod.Delete)

    private suspend fun dependency(token: String, ref: IssueRef, on: IssueRef, method: HttpMethod): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "dependencies",
            method = method,
            body = buildJsonObject {
                put("owner", on.repo.owner)
                put("repo", on.repo.name)
                put("index", on.number)
            },
        ).toResult { }
    }

    // The lists below stop at their first page: a repository with more is rare, and none needs them all to triage.

    /** The repository's own labels, then its organisation's: both can be put on its conversations. */
    override suspend fun labels(token: String, repo: RepoId): ForgeResult<List<Label>> = forgeCall {
        coroutineScope {
            val shared = async { httpClient.forgejoApi(forge, token, "orgs", repo.owner, "labels", query = FIRST_PAGE) }
            val own = httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "labels", query = FIRST_PAGE)
            if (own.status != HttpStatusCode.OK) {
                shared.cancel()
                return@coroutineScope own.failure()
            }
            // An owner that is a person has no organisation labels: that answers 404.
            val fromOrganisation = shared.await().takeIf { it.status == HttpStatusCode.OK }?.body<List<ApiLabel>>().orEmpty()
            ForgeResult.Success((own.body<List<ApiLabel>>() + fromOrganisation).map { it.toModel() }.distinctBy { it.name })
        }
    }

    /** Forgejo takes names as well as ids here. */

    /** In the repository itself, never its organisation's. */
    override suspend fun createLabel(token: String, repo: RepoId, name: String, color: String): ForgeResult<Label> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", repo.owner, repo.name, "labels", method = HttpMethod.Post,
            body = buildJsonObject {
                put("name", name)
                put("color", color)
            },
        ).toResult { body<ApiLabel>().toModel() }
    }

    override suspend fun setLabels(token: String, ref: IssueRef, names: List<String>): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "labels",
            method = HttpMethod.Put, body = buildJsonObject { putJsonArray("labels") { names.forEach { add(it) } } },
        ).toResult { }
    }

    override suspend fun assignable(token: String, repo: RepoId): ForgeResult<List<ForgeUser>> = forgeCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "assignees").toResult { body<List<User>>().map { it.toModel() } }
    }

    override suspend fun setAssignees(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> =
        edit(token, ref) { putJsonArray("assignees") { logins.forEach { add(it) } } }

    /** Forgejo asks for reviews one way and takes the ask back another: only who changes is sent to each. */
    override suspend fun setReviewers(token: String, ref: IssueRef, logins: List<String>): ForgeResult<Unit> = forgeCall {
        val pull = get(token, ref, "pulls", ref.number.toString())
        if (pull.status != HttpStatusCode.OK) return@forgeCall pull.failure()
        val asked = pull.body<PullRequest>().requestedReviewers.orEmpty().map { it.name }
        val added = logins.filter { login -> asked.none { it.equals(login, ignoreCase = true) } }
        val removed = asked.filter { login -> logins.none { it.equals(login, ignoreCase = true) } }
        for ((who, method) in listOf(added to HttpMethod.Post, removed to HttpMethod.Delete)) {
            if (who.isEmpty()) continue
            val response = httpClient.forgejoApi(
                forge, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString(), "requested_reviewers",
                method = method,
                body = buildJsonObject { putJsonArray("reviewers") { who.forEach { add(it) } } },
            )
            if (!response.status.isSuccess()) return@forgeCall response.failure()
        }
        ForgeResult.Success(Unit)
    }

    /**
     * A Forgejo pull request is a draft while its title starts with "WIP:" or "[WIP]" (the instance's default marks),
     * so the title is rewritten. Nothing is sent when it already is what was asked.
     */
    override suspend fun setDraft(token: String, ref: IssueRef, draft: Boolean): ForgeResult<Unit> {
        val pull = forgeCall {
            val answer = get(token, ref, "pulls", ref.number.toString())
            if (answer.status != HttpStatusCode.OK) return@forgeCall answer.failure()
            ForgeResult.Success(answer.body<PullRequest>())
        }
        val current = when (pull) {
            is ForgeResult.Failure -> return pull
            is ForgeResult.Success -> pull.value
        }
        if ((current.draft == true) == draft) return ForgeResult.Success(Unit)
        val currentTitle = current.title.orEmpty()
        val title = if (draft) "WIP: $currentTitle" else currentTitle.replace(forgejoDraftMark, "")
        return edit(token, ref) { put("title", title) }
    }

    override suspend fun milestones(token: String, repo: RepoId): ForgeResult<List<Milestone>> = forgeCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name, "milestones", query = FIRST_PAGE + ("state" to "open"))
            .toResult { body<List<ApiMilestone>>().map { it.toModel() } }
    }

    /** No milestone is said with 0. */
    override suspend fun setMilestone(token: String, ref: IssueRef, milestone: Milestone?): ForgeResult<Unit> =
        edit(token, ref) { put("milestone", milestone?.id ?: 0) }

    /** The branch a pull request goes into is changed on the pull request, not on the issue it also is. */
    override suspend fun setBase(token: String, ref: IssueRef, branch: String): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "pulls", ref.number.toString(),
            method = HttpMethod.Patch,
            body = buildJsonObject { put("base", branch) },
        ).toResult { }
    }

    override suspend fun isPinned(token: String, ref: IssueRef): ForgeResult<Boolean> = forgeCall {
        get(token, ref, "issues", ref.number.toString()).toResult { (body<Issue>().pinOrder ?: 0) > 0 }
    }

    override suspend fun setPinned(token: String, ref: IssueRef, pinned: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "pin",
            method = if (pinned) HttpMethod.Post else HttpMethod.Delete,
        ).toResult { }
    }

    override suspend fun isSubscribed(token: String, login: String, ref: IssueRef): ForgeResult<Boolean> = forgeCall {
        get(token, ref, "issues", ref.number.toString(), "subscriptions", "check").toResult { body<WatchInfo>().subscribed == true }
    }

    override suspend fun setSubscribed(token: String, login: String, ref: IssueRef, subscribed: Boolean): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(
            forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), "subscriptions", login,
            method = if (subscribed) HttpMethod.Put else HttpMethod.Delete,
        ).toResult { }
    }

    override suspend fun delete(token: String, ref: IssueRef): ForgeResult<Unit> = forgeCall {
        httpClient.forgejoApi(forge, token, "repos", ref.repo.owner, ref.repo.name, "issues", ref.number.toString(), method = HttpMethod.Delete).toResult { }
    }

    override suspend fun access(token: String, repo: RepoId): ForgeResult<RepoRights> = forgeCall {
        httpClient.forgejoApi(forge, token, "repos", repo.owner, repo.name).toResult { forgejoRights(body<Repository>()) }
    }

    private fun Issue.toDetails(ref: IssueRef, pull: PullRequest?, reactions: Map<Reaction, Int>) = IssueDetails(
        ref = ref,
        title = required(title, "an issue's title"),
        body = body?.ifBlank { null },
        state = state(),
        stateReason = null,
        author = user?.toModel(),
        labels = labels.orEmpty().map { it.toModel() },
        createdAt = instant(required(createdAt, "an issue's creation time")) ?: Instant.EPOCH,
        closedAt = instant(closedAt),
        comments = comments.count(),
        reactions = reactions,
        isLocked = isLocked == true,
        assignees = assignees.orEmpty().map { it.toModel() },
        milestone = milestone?.takeIf { (it.id ?: 0) > 0 }?.toModel(),
        // Kept as the end of a day; read in UTC, where it was sent as that day.
        dueDate = instant(dueDate)?.atOffset(ZoneOffset.UTC)?.toLocalDate(),
        pullRequest = pull?.let {
            val head = required(it.head, "a pull request's head")
            val headRef = required(head.ref, "a pull request's head branch")
            PullRequestInfo(
                isDraft = it.draft == true,
                isMerged = it.merged == true,
                baseRef = required(required(it.base, "a pull request's base").ref, "a pull request's base branch"),
                // A pull request from another repository only names a pull ref; its label says owner:branch.
                headRef = head.label?.takeIf { headRef.startsWith("refs/pull/") } ?: headRef,
                additions = it.additions.count(),
                deletions = it.deletions.count(),
                changedFiles = it.changedFiles.count(),
                // Forgejo's pull request doesn't count its commits (not in its API description, nor in Codeberg's answers).
                commits = 0,
                reviewers = it.requestedReviewers.orEmpty().map { user -> user.toModel() },
            )
        },
    )

    private suspend fun timelinePage(
        token: String?,
        ref: IssueRef,
        page: Int,
        reviews: suspend (List<TimelineComment>) -> Map<Long, ReviewState>,
    ): ForgeResult<TimelinePage> {
        val response = get(token, ref, "issues", ref.number.toString(), "timeline", query = mapOf("page" to page.toString(), "limit" to "$PAGE_SIZE"))
        if (response.status != HttpStatusCode.OK) return response.failure()
        val entries = response.body<List<TimelineComment>>()
        val reviewStates = reviews(entries)
        val items = entries.mapIndexedNotNull { index, entry ->
            val before = entries.getOrNull(index - 1)
            when {
                // A commit that closed the conversation is told with the close, not apart from it.
                entry.closesWith(entries.getOrNull(index + 1)) -> null
                before != null && before.closesWith(entry) -> entry.toItem(reviewStates, ref.repo)?.let { item ->
                    (item as? TimelineItem.StateChanged)?.copy(commit = before.commit()) ?: item
                }
                else -> entry.toItem(reviewStates, ref.repo)
            }
        }
        val next = response.nextPage() ?: (page + 1).takeIf { entries.size == PAGE_SIZE }
        // The count covers every entry, shown or not: it gives the number of pages, not of items.
        val last = response.totalCount()?.let { (it + PAGE_SIZE - 1) / PAGE_SIZE }
        return ForgeResult.Success(TimelinePage(items, next, last))
    }

    /**
     * Whether each review approved: a detail of the timeline, so failing never takes the timeline with it.
     * Regression: asked alongside, an answer that couldn't be read (or a dropped connection) failed the whole
     * conversation whenever it came before the timeline did; a test failed now and then on a busy machine.
     */
    private suspend fun reviewStates(token: String?, ref: IssueRef): Map<Long, ReviewState> = try {
        readReviewStates(token, ref)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    private suspend fun readReviewStates(token: String?, ref: IssueRef): Map<Long, ReviewState> {
        val response = get(token, ref, "pulls", ref.number.toString(), "reviews")
        if (response.status != HttpStatusCode.OK) return emptyMap()
        return response.body<List<PullReview>>().mapNotNull { review ->
            when (review.state) {
                "APPROVED" -> ReviewState.APPROVED
                "REQUEST_CHANGES" -> ReviewState.CHANGES_REQUESTED
                "COMMENT" -> ReviewState.COMMENTED
                else -> null
            }?.let { required(review.id, "a review's id") to it }
        }.toMap()
    }

    private suspend fun get(token: String?, ref: IssueRef, vararg segments: String, query: Map<String, String> = emptyMap()): HttpResponse =
        httpClient.forgejoApi(forge, token, "repos", ref.repo.owner, ref.repo.name, *segments, query = query)

    /**
     * Whether this is the reference of a commit that closed the conversation: Forgejo writes it, then the close, by the
     * same person in the same second (dnkl/foot#2467 on Codeberg, 2026-09-18).
     */
    private fun TimelineComment.closesWith(next: TimelineComment?) =
        kind == "commit_ref" && next?.kind == "close" && next.createdAt == createdAt && next.user?.login == user?.login && commit() != null

    /** The commit a reference names: its body is a link to the commit, its words the commit's first line. */
    private fun TimelineComment.commit(): ClosingCommit? {
        val sha = refCommitSha?.ifEmpty { null } ?: return null
        val link = COMMIT_LINK.find(body.orEmpty())
        val path = link?.groupValues?.get(1)?.takeIf { it.startsWith("/") }
        val message = link?.groupValues?.get(2)?.let(::withoutEntities)?.ifBlank { null }
        // The link's path is from the server's root, a server installed under a path (example.org/git) included.
        return ClosingCommit(sha, message, path?.let { "https://${forge.server}$it" } ?: "${forge.webUrl}/commit/$sha")
    }

    private fun TimelineComment.toItem(reviewStates: Map<Long, ReviewState>, home: RepoId): TimelineItem? {
        val at = instant(createdAt) ?: return null
        val actor = user?.toModel()
        val oldTitle = oldTitle
        val newTitle = newTitle
        return when (kind) {
            "comment" -> TimelineItem.Comment(required(id, "a comment's id"), actor, body.orEmpty(), at, emptyMap())
            "review" -> reviewId?.let { reviewStates[it] }?.let { TimelineItem.Review(reviewId, actor, it, body?.ifBlank { null }, at) }
            "close" -> TimelineItem.StateChanged(StateChange.CLOSED, actor, null, at)
            "reopen" -> TimelineItem.StateChanged(StateChange.REOPENED, actor, null, at)
            "merge_pull" -> TimelineItem.StateChanged(StateChange.MERGED, actor, null, at)
            // A label entry's body is "1" when added, empty when removed.
            "label" -> label?.let { TimelineItem.Labeled(body == "1", it.toModel(), actor, at) }
            "change_title" -> if (oldTitle != null && newTitle != null) TimelineItem.Renamed(oldTitle, newTitle, actor, at) else null
            "issue_ref", "pull_ref", "comment_ref" -> refIssue?.let { source ->
                val repo = source.repository?.fullName?.split('/')?.takeIf { it.size == 2 } ?: return null
                TimelineItem.CrossReferenced(
                    IssueRef(RepoId(repo[0], repo[1], forge), required(source.number, "an issue's number").toInt()),
                    required(source.title, "an issue's title"),
                    source.pullRequest != null,
                    actor,
                    at,
                )
            }
            "lock" -> TimelineItem.Event(ConversationEvent.LOCKED, actor, null, at)
            "unlock" -> TimelineItem.Event(ConversationEvent.UNLOCKED, actor, null, at)
            "pin" -> TimelineItem.Event(ConversationEvent.PINNED, actor, null, at)
            "unpin" -> TimelineItem.Event(ConversationEvent.UNPINNED, actor, null, at)
            "assignees" -> assignee?.let {
                TimelineItem.Event(if (removedAssignee == true) ConversationEvent.UNASSIGNED else ConversationEvent.ASSIGNED, actor, it.name, at)
            }
            // Setting one names it; taking it away names the one that was there.
            "milestone" -> milestone?.takeIf { (it.id ?: 0) > 0 }?.let { TimelineItem.Event(ConversationEvent.MILESTONED, actor, it.title, at) }
                ?: oldMilestone?.takeIf { (it.id ?: 0) > 0 }?.let { TimelineItem.Event(ConversationEvent.DEMILESTONED, actor, it.title, at) }
            // A new date is written alone, a changed one as "new|old", a removed one as the date it was.
            "added_deadline", "modified_deadline" -> TimelineItem.Event(ConversationEvent.DEADLINE_SET, actor, body?.substringBefore('|')?.ifBlank { null }, at)
            "removed_deadline" -> TimelineItem.Event(ConversationEvent.DEADLINE_REMOVED, actor, null, at)
            "start_tracking" -> TimelineItem.Event(ConversationEvent.TRACKING_STARTED, actor, null, at)
            "stop_tracking" -> TimelineItem.Event(ConversationEvent.TRACKING_STOPPED, actor, null, at)
            // Its body is "|<seconds>" (older ones wrote the time out: shown as it came).
            "add_time_manual" -> TimelineItem.Event(ConversationEvent.TIME_ADDED, actor, spentTime(body), at)
            "delete_time_manual" -> TimelineItem.Event(ConversationEvent.TIME_REMOVED, actor, null, at)
            "cancel_tracking" -> TimelineItem.Event(ConversationEvent.TRACKING_CANCELLED, actor, null, at)
            // Gitea only, its body the estimate in seconds: none is "0".
            "change_time_estimate" -> when (val estimate = spentTime(body)) {
                null -> TimelineItem.Event(ConversationEvent.ESTIMATE_REMOVED, actor, null, at)
                else -> TimelineItem.Event(ConversationEvent.ESTIMATE_SET, actor, estimate, at)
            }
            "add_dependency", "remove_dependency" -> dependentIssue?.let {
                TimelineItem.Event(
                    if (kind == "add_dependency") ConversationEvent.DEPENDENCY_ADDED else ConversationEvent.DEPENDENCY_REMOVED,
                    actor,
                    // One in another repository names it.
                    it.repository?.fullName?.takeUnless { name -> name.equals(home.fullName, ignoreCase = true) }.orEmpty() + "#${it.number} ${it.title}",
                    at,
                )
            }
            // The comment of a deleted branch names it.
            "delete_branch" -> TimelineItem.Event(ConversationEvent.BRANCH_DELETED, actor, oldRef?.ifBlank { null }, at)
            // A person or a team asked for a review, or no longer.
            "review_request" -> (assignee?.login ?: assigneeTeam?.name)?.let {
                TimelineItem.Event(if (removedAssignee == true) ConversationEvent.REVIEW_REQUEST_REMOVED else ConversationEvent.REVIEW_REQUESTED, actor, it, at)
            }
            "dismiss_review" -> TimelineItem.Event(ConversationEvent.REVIEW_DISMISSED, actor, body?.ifBlank { null }, at)
            // Its body: {"is_force_push":false,"commit_ids":[...]}, a force push naming the old head then the new.
            "pull_push" -> push(body).let { (force, commits) ->
                if (force) {
                    TimelineItem.Event(ConversationEvent.FORCE_PUSHED, actor, commits.lastOrNull()?.take(7), at)
                } else {
                    TimelineItem.Event(ConversationEvent.PUSHED, actor, commits.size.takeIf { it > 0 }?.toString(), at)
                }
            }
            "change_target_branch" -> TimelineItem.Event(ConversationEvent.BASE_CHANGED, actor, newRef?.ifBlank { null }, at)
            "pull_scheduled_merge" -> TimelineItem.Event(ConversationEvent.AUTO_MERGE_ENABLED, actor, null, at)
            "pull_cancel_scheduled_merge" -> TimelineItem.Event(ConversationEvent.AUTO_MERGE_DISABLED, actor, null, at)
            // A commit that mentions it without closing it (one that closes it is told with the close).
            "commit_ref" -> commit()?.let { TimelineItem.CommitReferenced(it, actor, at) }
            // The API names a project by its id alone, and a column not at all.
            "project" -> when {
                (projectId ?: 0) > 0 -> TimelineItem.Event(ConversationEvent.ADDED_TO_PROJECT, actor, null, at)
                (oldProjectId ?: 0) > 0 -> TimelineItem.Event(ConversationEvent.REMOVED_FROM_PROJECT, actor, null, at)
                else -> null
            }
            "project_board" -> TimelineItem.Event(ConversationEvent.PROJECT_STATUS_CHANGED, actor, null, at)
            // Line comments are read as threads; changing the branch an issue refers to says nothing of its work.
            else -> null
        }
    }

    private companion object {
        const val PAGE_SIZE = 50

        /** "|5400" read as "1h 30m"; a time written out is kept as it is, none (or "0") is null. */
        fun spentTime(body: String?): String? {
            val text = body?.trim()?.ifEmpty { null } ?: return null
            val seconds = text.removePrefix("|").toLongOrNull() ?: return text.removePrefix("|").ifBlank { null }
            if (seconds <= 0) return null
            val hours = seconds / 3600
            val minutes = seconds % 3600 / 60
            val rest = seconds % 60
            return listOfNotNull(hours.takeIf { it > 0 }?.let { "${it}h" }, minutes.takeIf { it > 0 }?.let { "${it}m" }, rest.takeIf { it > 0 && hours == 0L }?.let { "${it}s" })
                .joinToString(" ")
        }

        /** Whether a push rewrote the branch, and the commits it names. */
        fun push(body: String?): Pair<Boolean, List<String>> {
            val json = runCatching { ForgeJson.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull() ?: return false to emptyList()
            val force = json["is_force_push"]?.jsonPrimitive?.booleanOrNull == true
            val commits = (json["commit_ids"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            return force to commits
        }

        val FIRST_PAGE = mapOf("limit" to "$PAGE_SIZE")

        fun reaction(content: String?): Reaction? = when (content) {
            "+1" -> Reaction.THUMBS_UP
            "-1" -> Reaction.THUMBS_DOWN
            "laugh" -> Reaction.LAUGH
            "hooray" -> Reaction.HOORAY
            "confused" -> Reaction.CONFUSED
            "heart" -> Reaction.HEART
            "rocket" -> Reaction.ROCKET
            "eyes" -> Reaction.EYES
            else -> null
        }
    }
}

private fun IssueConfig.toModel() = IssueChooser(
    blankIssuesEnabled != false,
    contactLinks.orEmpty()
        .mapNotNull { link -> link.name?.takeIf { it.isNotBlank() }?.let { name -> link.url?.takeIf { it.isNotBlank() }?.let { ContactLink(name, it, link.about?.ifBlank { null }) } } },
)

/**
 * The Markdown an issue form writes, with its answers still to give: what Forgejo itself puts in the issue (its markdown
 * meant for the issue, then each field's label as a heading over its answer), each field's help as a comment above
 * where it is answered, a dropdown's choices as a list to keep one of, checkboxes as boxes to tick. A field's
 * `visible` says whether it shows in the form, in the issue it writes, or both.
 */
internal fun formToMarkdown(fields: List<IssueFormField>): String = issueFormText(
    fields.mapNotNull { field ->
        val type = field.type ?: return@mapNotNull null
        val attributes = field.attributes.orEmpty()
        fun text(name: String) = (attributes[name] as? JsonPrimitive)?.contentOrNull
        // Plain words for a dropdown, a label each for checkboxes.
        val options = (attributes["options"] as? JsonArray).orEmpty().mapNotNull { option ->
            (option as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull ?: (option as? JsonPrimitive)?.contentOrNull
        }
        FormField(type, text("label"), text("description"), text("value"), options, inIssue = field.visible?.contains("content") ?: (type != "markdown"))
    },
)

/**
 * What a repository's answer says the token's account may do there. Forgejo has no triage role: whoever can push
 * manages the conversations too. A repository's own settings switch time tracking and dependencies on, and may keep
 * tracking to those who can push.
 */
internal fun forgejoRights(repo: Repository): RepoRights {
    val admin = repo.permissions?.admin == true
    val push = repo.permissions?.push == true
    // Without its own tracker (issues kept elsewhere), neither applies.
    val tracker = repo.internalTracker
    val tracksTime = tracker != null && tracker.enableTimeTracker == true && (push || tracker.allowOnlyContributorsToTrackTime != true)
    return RepoRights(
        access = when {
            admin -> RepoAccess.ADMIN
            push -> RepoAccess.WRITE
            else -> RepoAccess.NONE
        },
        switchedOff = setOfNotNull(
            ConversationAction.TIME_TRACKING.takeUnless { tracksTime },
            ConversationAction.DEPENDENCIES.takeUnless { tracker?.enableIssueDependencies == true },
        ),
    )
}

/** The reactions on comment [commentId] of [repo], counted; none where they can't be read (null answers none too). */
internal suspend fun HttpClient.forgejoCommentReactions(forge: ForgeInstance, token: String?, repo: RepoId, commentId: Long): Map<Reaction, Int> {
    val response = runCatching { forgejoApi(forge, token, "repos", repo.owner, repo.name, "issues", "comments", commentId.toString(), "reactions") }.getOrNull()
    if (response?.status != HttpStatusCode.OK) return emptyMap()
    val given = runCatching { response.body<List<ApiReaction>?>() }.getOrNull().orEmpty()
    return given.mapNotNull { forgejoReaction(it.content) }.groupingBy { it }.eachCount()
}

internal fun forgejoReaction(content: String?): Reaction? = when (content) {
    "+1" -> Reaction.THUMBS_UP
    "-1" -> Reaction.THUMBS_DOWN
    "laugh" -> Reaction.LAUGH
    "hooray" -> Reaction.HOORAY
    "confused" -> Reaction.CONFUSED
    "heart" -> Reaction.HEART
    "rocket" -> Reaction.ROCKET
    "eyes" -> Reaction.EYES
    else -> null
}

private fun fileHeaders(name: String, mimeType: String) = Headers.build {
    append(HttpHeaders.ContentType, mimeType)
    append(HttpHeaders.ContentDisposition, "filename=\"${name.replace("\"", "")}\"")
}

/** A file's name as the text that stands for its picture: without what would end the Markdown early. */
private fun altText(name: String): String = name.substringBeforeLast('.').replace(Regex("""[\[\]\\]"""), "").ifBlank { "image" }

/** What a timeline entry is; a created comment comes back without a type. */
private val TimelineComment.kind: String get() = type ?: "comment"

/** A commit reference's body: `<a href="/owner/name/commit/sha">its first line</a>`. */
private val COMMIT_LINK = Regex("""<a href="([^"]*)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

/** HTML's escapes back to what they stand for, `&amp;` last so it never makes another. */
private fun withoutEntities(html: String) =
    html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

/** The marks a pull request's title starts with while it is a draft, by Forgejo's default, and the space after. */
private val forgejoDraftMark = Regex("""^\s*(wip:|\[wip])\s*""", RegexOption.IGNORE_CASE)
