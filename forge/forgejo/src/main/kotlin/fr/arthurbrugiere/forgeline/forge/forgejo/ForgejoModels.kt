package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The shapes of Forgejo's API answers Forgeline reads (checked against codeberg.org on 2026-09-29).

@Serializable
internal data class UserJson(
    val login: String,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    fun toModel() = ForgeUser(login, fullName?.ifBlank { null }, avatarUrl)

    fun toSummary(forge: ForgeInstance) = UserSummary(login, avatarUrl, isOrganization = false, forge = forge)
}

@Serializable
internal data class RepoJson(
    val name: String,
    val owner: UserJson,
    val description: String? = null,
    val website: String? = null,
    val topics: List<String>? = null,
    @SerialName("stars_count") val stars: Int = 0,
    @SerialName("forks_count") val forks: Int = 0,
    @SerialName("watchers_count") val watchers: Int = 0,
    val language: String? = null,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val fork: Boolean = false,
    val archived: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("has_actions") val hasActions: Boolean = false,
    @SerialName("has_issues") val hasIssues: Boolean = true,
    /** How many releases the repository has; null on servers too old to say. */
    @SerialName("release_counter") val releaseCounter: Int? = null,
) {
    fun id(forge: ForgeInstance) = RepoId(owner.login, name, forge)

    fun toDetails(forge: ForgeInstance) = RepoDetails(
        id = id(forge),
        description = description?.ifBlank { null },
        homepage = website?.ifBlank { null },
        topics = topics.orEmpty(),
        stars = stars,
        forks = forks,
        watchers = watchers,
        language = language?.ifBlank { null },
        // Forgejo's API doesn't name a repository's license.
        license = null,
        defaultBranch = defaultBranch,
        ownerAvatarUrl = owner.avatarUrl,
        isFork = fork,
        isArchived = archived,
        pushedAt = instant(updatedAt),
        hasActions = hasActions,
        hasIssues = hasIssues,
    )

    fun toSummary(forge: ForgeInstance) = RepoSummary(
        id = id(forge),
        description = description?.ifBlank { null },
        language = language?.ifBlank { null },
        stars = stars,
        forks = forks,
        isFork = fork,
        updatedAt = instant(updatedAt),
        ownerAvatarUrl = owner.avatarUrl,
    )
}

@Serializable
internal data class LabelJson(val name: String, val color: String? = null) {
    fun toModel() = Label(name, color?.removePrefix("#"))
}

@Serializable
internal data class IssuePullJson(val merged: Boolean = false, val draft: Boolean = false)

@Serializable
internal data class IssueRepoJson(val owner: String, val name: String)

@Serializable
internal data class IssueJson(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val user: UserJson? = null,
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("closed_at") val closedAt: String? = null,
    val labels: List<LabelJson> = emptyList(),
    @SerialName("pull_request") val pullRequest: IssuePullJson? = null,
    // Only in cross-repository search results.
    val repository: IssueRepoJson? = null,
    // Pull request lists carry these at the top level.
    val merged: Boolean? = null,
    val draft: Boolean? = null,
) {
    val isPullRequest: Boolean get() = pullRequest != null || merged != null

    fun state(): IssueState = when {
        merged == true || pullRequest?.merged == true -> IssueState.MERGED
        state == "closed" -> IssueState.CLOSED
        else -> IssueState.OPEN
    }

    fun toSummary() = IssueSummary(
        number = number,
        title = title,
        state = state(),
        author = user?.toModel(),
        comments = comments,
        createdAt = instant(createdAt) ?: java.time.Instant.EPOCH,
        labels = labels.map { it.toModel() },
        isPullRequest = isPullRequest,
        isDraft = draft == true || pullRequest?.draft == true,
    )
}

@Serializable
internal data class ReleaseJson(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val author: UserJson? = null,
) {
    fun toModel() = Release(tag, name?.ifBlank { null }, body?.ifBlank { null }, instant(publishedAt), prerelease, author?.toModel())
}

@Serializable
internal data class ContentJson(val name: String, val path: String, val type: String, val size: Long = 0)

@Serializable
internal data class GitRefJson(val ref: String)

@Serializable
internal data class SearchJson<T>(val data: List<T> = emptyList())
