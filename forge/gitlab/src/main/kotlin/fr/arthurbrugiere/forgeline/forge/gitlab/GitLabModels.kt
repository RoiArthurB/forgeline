package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.GitRefs
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoFileType
import fr.arthurbrugiere.forgeline.core.model.RepoFile
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStatus
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.UserProfile
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.core.model.WorkflowRun
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
internal data class GitLabProjectJson(
    val id: Long,
    val name: String,
    val path: String,
    @SerialName("path_with_namespace") val pathWithNamespace: String,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("web_url") val webUrl: String,
    @SerialName("readme_url") val readmeUrl: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("star_count") val starCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val archived: Boolean = false,
    val topics: List<String> = emptyList(),
    @SerialName("tag_list") val tagList: List<String> = emptyList(),
    @SerialName("jobs_enabled") val jobsEnabled: Boolean? = true,
    @SerialName("issues_enabled") val issuesEnabled: Boolean? = true,
    @SerialName("merge_requests_enabled") val mergeRequestsEnabled: Boolean? = true,
    val license: GitLabLicenseJson? = null,
    val permissions: GitLabPermissionsJson? = null,
) {
    fun repoId(forge: ForgeInstance): RepoId {
        val owner = if (pathWithNamespace.contains('/')) pathWithNamespace.substringBeforeLast('/') else pathWithNamespace
        return RepoId(owner, path, forge)
    }

    fun toDetails(forge: ForgeInstance): RepoDetails {
        val allTopics = (topics + tagList).distinct()
        return RepoDetails(
            id = repoId(forge),
            description = description?.ifBlank { null },
            homepage = null,
            topics = allTopics,
            stars = starCount,
            forks = forksCount,
            watchers = starCount,
            language = null,
            license = license?.name ?: license?.key,
            defaultBranch = defaultBranch ?: "main",
            ownerAvatarUrl = avatarUrl,
            isFork = false,
            isArchived = archived,
            pushedAt = gitlabInstant(lastActivityAt),
            hasActions = jobsEnabled ?: true,
            hasIssues = issuesEnabled ?: true,
        )
    }

    fun toSummary(forge: ForgeInstance): RepoSummary = RepoSummary(
        id = repoId(forge),
        description = description?.ifBlank { null },
        language = null,
        stars = starCount,
        forks = forksCount,
        isFork = false,
        updatedAt = gitlabInstant(lastActivityAt) ?: gitlabInstant(createdAt) ?: Instant.EPOCH,
        ownerAvatarUrl = avatarUrl,
    )
}

@Serializable
internal data class GitLabLicenseJson(
    val key: String? = null,
    val name: String? = null,
)

@Serializable
internal data class GitLabPermissionsJson(
    @SerialName("project_access") val projectAccess: GitLabAccessJson? = null,
    @SerialName("group_access") val groupAccess: GitLabAccessJson? = null,
)

@Serializable
internal data class GitLabAccessJson(
    @SerialName("access_level") val accessLevel: Int? = null,
)

@Serializable
internal data class GitLabBranchJson(
    val name: String,
    val default: Boolean = false,
)

@Serializable
internal data class GitLabTagJson(
    val name: String,
    val message: String? = null,
)

@Serializable
internal data class GitLabTreeItemJson(
    val id: String,
    val name: String,
    val type: String,
    val path: String,
    val mode: String? = null,
) {
    fun toRepoFile(): RepoFile {
        val fileType = when (type) {
            "tree" -> RepoFileType.DIR
            "submodule" -> RepoFileType.SUBMODULE
            else -> RepoFileType.FILE
        }
        return RepoFile(path = path, name = name, type = fileType, size = 0L)
    }
}

@Serializable
internal data class GitLabIssueJson(
    val id: Long,
    val iid: Int,
    @SerialName("project_id") val projectId: Long,
    val title: String,
    val description: String? = null,
    val state: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    val author: GitLabUserJson? = null,
    val assignees: List<GitLabUserJson> = emptyList(),
    val labels: List<String> = emptyList(),
    val milestone: GitLabMilestoneJson? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("discussion_locked") val discussionLocked: Boolean? = null,
    @SerialName("user_notes_count") val userNotesCount: Int = 0,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    @SerialName("state_reason") val stateReason: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
) {
    fun toSummary(repo: RepoId): IssueSummary = IssueSummary(
        number = iid,
        title = title,
        state = if (state == "opened") IssueState.OPEN else IssueState.CLOSED,
        author = author?.toForgeUser(),
        comments = userNotesCount,
        createdAt = gitlabInstant(createdAt) ?: Instant.EPOCH,
        labels = labels.map { Label(it, null) },
        isPullRequest = false,
        isDraft = false,
    )

    fun toDetails(repo: RepoId, reactions: Map<Reaction, Int> = emptyMap()): IssueDetails = IssueDetails(
        ref = IssueRef(repo, iid, isPullRequest = false),
        title = title,
        body = description?.ifBlank { null },
        state = if (state == "opened") IssueState.OPEN else IssueState.CLOSED,
        stateReason = stateReason,
        author = author?.toForgeUser(),
        labels = labels.map { Label(it, null) },
        createdAt = gitlabInstant(createdAt) ?: Instant.EPOCH,
        closedAt = gitlabInstant(closedAt),
        comments = userNotesCount,
        reactions = reactions,
        pullRequest = null,
        isLocked = discussionLocked ?: false,
        assignees = assignees.map { it.toForgeUser() },
        milestone = milestone?.let { Milestone(it.id, it.title) },
        dueDate = dueDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
    )
}

@Serializable
internal data class GitLabMergeRequestJson(
    val id: Long,
    val iid: Int,
    @SerialName("project_id") val projectId: Long,
    val title: String,
    val description: String? = null,
    val state: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("merged_at") val mergedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("target_branch") val targetBranch: String? = null,
    val draft: Boolean = false,
    @SerialName("work_in_progress") val workInProgress: Boolean = false,
    val author: GitLabUserJson? = null,
    val assignees: List<GitLabUserJson> = emptyList(),
    val labels: List<String> = emptyList(),
    val milestone: GitLabMilestoneJson? = null,
    @SerialName("user_notes_count") val userNotesCount: Int = 0,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    @SerialName("discussion_locked") val discussionLocked: Boolean? = null,
    @SerialName("changes_count") val changesCount: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
) {
    val isDraft: Boolean get() = draft || workInProgress || title.startsWith("Draft:", ignoreCase = true) || title.startsWith("WIP:", ignoreCase = true)

    val issueState: IssueState
        get() = when (state) {
            "opened" -> IssueState.OPEN
            "merged" -> IssueState.MERGED
            else -> IssueState.CLOSED
        }

    fun toSummary(repo: RepoId): IssueSummary = IssueSummary(
        number = iid,
        title = title,
        state = issueState,
        author = author?.toForgeUser(),
        comments = userNotesCount,
        createdAt = gitlabInstant(createdAt) ?: Instant.EPOCH,
        labels = labels.map { Label(it, null) },
        isPullRequest = true,
        isDraft = isDraft,
    )

    fun toDetails(repo: RepoId, reactions: Map<Reaction, Int> = emptyMap()): IssueDetails = IssueDetails(
        ref = IssueRef(repo, iid, isPullRequest = true),
        title = title,
        body = description?.ifBlank { null },
        state = issueState,
        stateReason = null,
        author = author?.toForgeUser(),
        labels = labels.map { Label(it, null) },
        createdAt = gitlabInstant(createdAt) ?: Instant.EPOCH,
        closedAt = gitlabInstant(mergedAt ?: closedAt),
        comments = userNotesCount,
        reactions = reactions,
        pullRequest = PullRequestInfo(
            isDraft = isDraft,
            isMerged = state == "merged",
            baseRef = targetBranch ?: "",
            headRef = sourceBranch ?: "",
            additions = 0,
            deletions = 0,
            changedFiles = changesCount?.toIntOrNull() ?: 0,
            commits = 0,
        ),
        isLocked = discussionLocked ?: false,
        assignees = assignees.map { it.toForgeUser() },
        milestone = milestone?.let { Milestone(it.id, it.title) },
        dueDate = null,
    )
}

@Serializable
internal data class GitLabNoteJson(
    val id: Long,
    val body: String,
    val author: GitLabUserJson? = null,
    @SerialName("created_at") val createdAt: String,
    val system: Boolean = false,
) {
    fun toComment(reactions: Map<Reaction, Int> = emptyMap()): TimelineItem.Comment = TimelineItem.Comment(
        id = id,
        author = author?.toForgeUser(),
        body = body,
        createdAt = gitlabInstant(createdAt) ?: Instant.EPOCH,
        reactions = reactions,
    )
}

/** A conversation closed, reopened or merged: `resource_state_events`, kept apart from its notes. */
@Serializable
internal data class GitLabStateEventJson(
    val user: GitLabUserJson? = null,
    val state: String,
    @SerialName("created_at") val createdAt: String,
) {
    fun toTimelineItem(): TimelineItem.StateChanged? {
        val change = when (state) {
            "closed" -> StateChange.CLOSED
            "reopened" -> StateChange.REOPENED
            "merged" -> StateChange.MERGED
            else -> return null
        }
        return TimelineItem.StateChanged(change, user?.toForgeUser(), null, gitlabInstant(createdAt) ?: return null)
    }
}

/** A file uploaded to a project, as GitLab answered on 2026-10-07: [fullPath] is "/-/project/<id>/uploads/<hash>/<name>". */
@Serializable
internal data class GitLabUploadJson(val alt: String = "", @SerialName("full_path") val fullPath: String)

@Serializable
internal data class GitLabAwardEmojiJson(
    val id: Long,
    val name: String,
    val user: GitLabUserJson? = null,
) {
    fun toReaction(): Reaction? = when (name) {
        "thumbsup", "+1" -> Reaction.THUMBS_UP
        "thumbsdown", "-1" -> Reaction.THUMBS_DOWN
        "laugh", "laughing", "smile", "joy" -> Reaction.LAUGH
        "tada", "hooray" -> Reaction.HOORAY
        "confused" -> Reaction.CONFUSED
        "heart" -> Reaction.HEART
        "rocket" -> Reaction.ROCKET
        "eyes" -> Reaction.EYES
        else -> null
    }
}

@Serializable
internal data class GitLabLabelJson(
    val id: Long? = null,
    val name: String,
    val color: String? = null,
) {
    fun toModel(): Label = Label(name, color?.removePrefix("#"))
}

@Serializable
internal data class GitLabMilestoneJson(
    val id: Long,
    val iid: Int? = null,
    val title: String,
    val state: String? = null,
)

@Serializable
internal data class GitLabUserJson(
    val id: Long,
    val username: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
    val bio: String? = null,
    val location: String? = null,
    @SerialName("website_url") val websiteUrl: String? = null,
    val followers: Int = 0,
    val following: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toForgeUser(): ForgeUser = ForgeUser(
        login = username,
        name = name?.ifBlank { null },
        avatarUrl = avatarUrl,
    )

    fun toProfile(publicRepos: Int = 0): UserProfile = UserProfile(
        login = username,
        name = name?.ifBlank { null },
        avatarUrl = avatarUrl,
        bio = bio?.ifBlank { null },
        company = null,
        location = location?.ifBlank { null },
        website = websiteUrl?.ifBlank { null },
        followers = followers,
        following = following,
        publicRepos = publicRepos,
        isOrganization = false,
        createdAt = gitlabInstant(createdAt),
    )

    fun toSummary(forge: ForgeInstance = ForgeInstance.GitLab): UserSummary = UserSummary(
        login = username,
        avatarUrl = avatarUrl,
        isOrganization = false,
        forge = forge,
    )
}

@Serializable
internal data class GitLabGroupJson(
    val id: Long,
    val name: String,
    val path: String,
    @SerialName("full_path") val fullPath: String? = null,
    val description: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
) {
    fun toProfile(publicRepos: Int = 0): UserProfile = UserProfile(
        login = fullPath ?: path,
        name = name.ifBlank { null },
        avatarUrl = avatarUrl,
        bio = description?.ifBlank { null },
        company = null,
        location = null,
        website = webUrl,
        followers = 0,
        following = 0,
        publicRepos = publicRepos,
        isOrganization = true,
        createdAt = null,
    )

    fun toSummary(forge: ForgeInstance = ForgeInstance.GitLab): UserSummary = UserSummary(
        login = fullPath ?: path,
        avatarUrl = avatarUrl,
        isOrganization = true,
        forge = forge,
    )
}

@Serializable
internal data class GitLabReleaseJson(
    val name: String? = null,
    @SerialName("tag_name") val tagName: String,
    val description: String? = null,
    @SerialName("released_at") val releasedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val author: GitLabUserJson? = null,
    val assets: GitLabReleaseAssetsJson? = null,
) {
    fun toModel(): Release {
        val links = assets?.links.orEmpty().mapNotNull { link ->
            val url = link.directAssetUrl ?: link.url ?: return@mapNotNull null
            ReleaseAsset(name = link.name ?: "asset", sizeBytes = 0L, downloads = 0, url = url)
        }
        val sources = assets?.sources.orEmpty().mapNotNull { src ->
            val url = src.url ?: return@mapNotNull null
            ReleaseAsset(name = "Source code (${src.format ?: "archive"})", sizeBytes = 0L, downloads = 0, url = url)
        }
        return Release(
            tag = tagName,
            name = name?.ifBlank { null } ?: tagName,
            body = description?.ifBlank { null },
            publishedAt = gitlabInstant(releasedAt) ?: gitlabInstant(createdAt) ?: Instant.EPOCH,
            isPrerelease = false,
            author = author?.toForgeUser(),
            assets = links + sources,
        )
    }
}

@Serializable
internal data class GitLabReleaseAssetsJson(
    val count: Int = 0,
    val sources: List<GitLabReleaseSourceJson> = emptyList(),
    val links: List<GitLabReleaseLinkJson> = emptyList(),
)

@Serializable
internal data class GitLabReleaseSourceJson(
    val format: String? = null,
    val url: String? = null,
)

@Serializable
internal data class GitLabReleaseLinkJson(
    val id: Long? = null,
    val name: String? = null,
    val url: String? = null,
    @SerialName("direct_asset_url") val directAssetUrl: String? = null,
)

@Serializable
internal data class GitLabPipelineJson(
    val id: Long,
    val iid: Int? = null,
    @SerialName("project_id") val projectId: Long? = null,
    val sha: String? = null,
    val ref: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("web_url") val webUrl: String? = null,
    val user: GitLabUserJson? = null,
) {
    fun toRun(): WorkflowRun {
        val (runStatus, runConclusion) = mapStatus(status)
        return WorkflowRun(
            id = id,
            workflowName = "Pipeline #$id",
            title = "Pipeline for ${ref ?: sha ?: id.toString()}",
            status = runStatus,
            conclusion = runConclusion,
            branch = ref,
            event = "push",
            runNumber = (iid ?: id).toInt(),
            createdAt = gitlabInstant(createdAt) ?: Instant.EPOCH,
            actor = user?.toForgeUser(),
            workflowId = 1L,
            startedAt = gitlabInstant(createdAt),
            updatedAt = gitlabInstant(updatedAt) ?: gitlabInstant(createdAt) ?: Instant.EPOCH,
            webUrl = webUrl,
        )
    }

    companion object {
        fun mapStatus(status: String): Pair<RunStatus, RunConclusion?> = when (status) {
            "success" -> RunStatus.COMPLETED to RunConclusion.SUCCESS
            "failed" -> RunStatus.COMPLETED to RunConclusion.FAILURE
            "canceled" -> RunStatus.COMPLETED to RunConclusion.CANCELLED
            "skipped" -> RunStatus.COMPLETED to RunConclusion.SKIPPED
            "running" -> RunStatus.IN_PROGRESS to null
            else -> RunStatus.QUEUED to null
        }
    }
}

@Serializable
internal data class GitLabJobJson(
    val id: Long,
    val name: String,
    val stage: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val duration: Double? = null,
) {
    fun toRunJob(): RunJob {
        val (jobStatus, jobConclusion) = GitLabPipelineJson.mapStatus(status)
        return RunJob(
            id = id,
            name = name,
            status = jobStatus,
            conclusion = jobConclusion,
            startedAt = gitlabInstant(startedAt),
            completedAt = gitlabInstant(finishedAt),
            steps = emptyList(),
        )
    }
}

@Serializable
internal data class GitLabTodoJson(
    val id: Long,
    val project: GitLabProjectSnippetJson? = null,
    val author: GitLabUserJson? = null,
    @SerialName("action_name") val actionName: String,
    @SerialName("target_type") val targetType: String,
    val target: GitLabTodoTargetJson? = null,
    @SerialName("target_url") val targetUrl: String? = null,
    val body: String? = null,
    val state: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal data class GitLabProjectSnippetJson(
    val id: Long,
    val name: String,
    @SerialName("path_with_namespace") val pathWithNamespace: String,
)

@Serializable
internal data class GitLabTodoTargetJson(
    val id: Long? = null,
    val iid: Int? = null,
    val title: String? = null,
    val state: String? = null,
    val draft: Boolean? = null,
)

@Serializable
internal data class GitLabEventJson(
    val id: Long? = null,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("action_name") val actionName: String,
    @SerialName("target_id") val targetId: Long? = null,
    // A comment's is the note's own id, far past an Int; its conversation's number is in [note].
    @SerialName("target_iid") val targetIid: Long? = null,
    @SerialName("target_type") val targetType: String? = null,
    val note: GitLabEventNoteJson? = null,
    @SerialName("target_title") val targetTitle: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("author_username") val authorUsername: String? = null,
    val author: GitLabUserJson? = null,
    @SerialName("push_data") val pushData: GitLabPushDataJson? = null,
)

/** The note of a "commented on" event: what it is on, and its number there. */
@Serializable
internal data class GitLabEventNoteJson(
    @SerialName("noteable_type") val noteableType: String? = null,
    @SerialName("noteable_iid") val noteableIid: Int? = null,
)

@Serializable
internal data class GitLabPushDataJson(
    @SerialName("commit_count") val commitCount: Int? = null,
    val action: String? = null,
    @SerialName("ref_type") val refType: String? = null,
    @SerialName("commit_to") val commitTo: String? = null,
    @SerialName("commit_title") val commitTitle: String? = null,
    val ref: String? = null,
)

@Serializable
internal data class GitLabTimeStatsJson(
    @SerialName("time_estimate") val timeEstimate: Long = 0,
    @SerialName("total_time_spent") val totalTimeSpent: Long = 0,
)
