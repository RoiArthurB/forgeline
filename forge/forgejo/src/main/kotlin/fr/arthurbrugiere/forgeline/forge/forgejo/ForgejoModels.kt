package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.Release
import fr.arthurbrugiere.forgeline.core.model.ReleaseAsset
import fr.arthurbrugiere.forgeline.core.model.RepoDetails
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Attachment
import fr.arthurbrugiere.forgeline.forge.forgejo.api.GitEntry
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Issue
import fr.arthurbrugiere.forgeline.forge.forgejo.api.PullRequest
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Repository
import fr.arthurbrugiere.forgeline.forge.forgejo.api.User
import kotlinx.serialization.Serializable
import java.time.Instant
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Label as LabelJson
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Milestone as MilestoneJson
import fr.arthurbrugiere.forgeline.forge.forgejo.api.Release as ReleaseJson

// The shapes of Forgejo's answers are generated from its OpenAPI description (forge/forgejo/spec, refreshed by
// tools/specs/fetch.py) by OpenAPI Generator, in the `api` package. Every field there may be missing, as the description
// promises nothing: what follows is how the app reads them, with the defaults it always had, and a field the app can't
// do without missing makes the answer unreadable rather than a blank screen.

/** [value], which an answer the app can read always has: its absence makes the answer [ForgeError.Unreadable]. */
internal fun <T : Any> required(value: T?, field: String): T = requireNotNull(value) { "Forgejo answered without $field" }

internal val User.name: String get() = required(login, "a user's login")

internal fun User.toModel() = ForgeUser(name, fullName?.ifBlank { null }, avatarUrl)

internal fun User.toSummary(forge: ForgeInstance) = UserSummary(name, avatarUrl, isOrganization = false, forge = forge)

internal fun Repository.id(forge: ForgeInstance) = RepoId(required(owner, "a repository's owner").name, required(name, "a repository's name"), forge)

/** The repository's own avatar, else its owner's, as Forgejo itself shows it; its own is empty when it has none. */
internal val Repository.logoUrl: String? get() = avatarUrl?.ifBlank { null } ?: owner?.avatarUrl

internal fun Repository.toDetails(forge: ForgeInstance) = RepoDetails(
    id = id(forge),
    description = description?.ifBlank { null },
    homepage = website?.ifBlank { null },
    topics = topics.orEmpty(),
    stars = starsCount.count(),
    forks = forksCount.count(),
    watchers = watchersCount.count(),
    language = language?.ifBlank { null },
    // Forgejo's API doesn't name a repository's license.
    license = null,
    defaultBranch = defaultBranch ?: "main",
    ownerAvatarUrl = logoUrl,
    isFork = fork == true,
    isArchived = archived == true,
    pushedAt = instant(updatedAt),
    hasActions = hasActions == true,
    hasIssues = hasIssues != false,
    hasWiki = hasWiki == true,
    // Open issues, pull requests apart: Forgejo counts the two separately.
    openIssues = openIssuesCount?.toInt(),
    openPulls = openPrCounter?.toInt(),
    isEmpty = empty == true,
)

internal fun Repository.toSummary(forge: ForgeInstance) = RepoSummary(
    id = id(forge),
    description = description?.ifBlank { null },
    language = language?.ifBlank { null },
    stars = starsCount.count(),
    forks = forksCount.count(),
    isFork = fork == true,
    updatedAt = instant(updatedAt),
    ownerAvatarUrl = logoUrl,
    isPrivate = private == true,
)

/** A count Forgejo leaves out is none. */
internal fun Long?.count(): Int = this?.toInt() ?: 0

internal fun LabelJson.toModel() = Label(required(name, "a label's name"), color?.removePrefix("#"))

internal fun MilestoneJson.toModel() = Milestone(id ?: 0, title.orEmpty())

/** An issue, or a pull request as the issue list shows it (`pull_request` set). */
internal val Issue.isPullRequest: Boolean get() = pullRequest != null

internal fun Issue.state(): IssueState = when {
    pullRequest?.merged == true -> IssueState.MERGED
    state == "closed" -> IssueState.CLOSED
    else -> IssueState.OPEN
}

internal fun Issue.toSummary() = IssueSummary(
    number = required(number, "an issue's number").toInt(),
    title = required(title, "an issue's title"),
    state = state(),
    author = user?.toModel(),
    comments = comments.count(),
    createdAt = instant(required(createdAt, "an issue's creation time")) ?: Instant.EPOCH,
    labels = labels.orEmpty().map { it.toModel() },
    isPullRequest = isPullRequest,
    isDraft = pullRequest?.draft == true,
)

/** A pull request as the pull request list shows it, with whether it is merged or a draft at the top level. */
internal fun PullRequest.toSummary() = IssueSummary(
    number = required(number, "a pull request's number").toInt(),
    title = required(title, "a pull request's title"),
    state = when {
        merged == true -> IssueState.MERGED
        state == "closed" -> IssueState.CLOSED
        else -> IssueState.OPEN
    },
    author = user?.toModel(),
    comments = comments.count(),
    createdAt = instant(required(createdAt, "a pull request's creation time")) ?: Instant.EPOCH,
    labels = labels.orEmpty().map { it.toModel() },
    isPullRequest = true,
    isDraft = draft == true,
)

internal val ReleaseJson.isDraft: Boolean get() = draft == true

internal fun ReleaseJson.toModel(): Release {
    // A release can leave out the source archives Forgejo makes for its tag.
    val archives = hideArchiveLinks != true
    return Release(
        tag = required(tagName, "a release's tag"),
        name = name?.ifBlank { null },
        body = body?.ifBlank { null },
        publishedAt = instant(publishedAt),
        isPrerelease = prerelease == true,
        author = author?.toModel(),
        assets = assets.orEmpty().map { it.toAsset() },
        zipUrl = zipballUrl?.takeIf { archives },
        tarUrl = tarballUrl?.takeIf { archives },
        webUrl = htmlUrl,
    )
}

private fun Attachment.toAsset() =
    ReleaseAsset(required(name, "an asset's name"), propertySize ?: 0, downloadCount?.toInt(), required(browserDownloadUrl, "an asset's address"))

/** One entry of a git tree level: its path is its name; `type` is blob, tree or commit (a submodule). */
internal val GitEntry.entryPath: String get() = required(path, "a tree entry's path")

internal val GitEntry.entryType: String get() = required(type, "a tree entry's type")

/** Forgejo's search answers: `{"ok": true, "data": [...]}`, of repositories or users. */
@Serializable
internal data class SearchJson<T>(val data: List<T> = emptyList())
