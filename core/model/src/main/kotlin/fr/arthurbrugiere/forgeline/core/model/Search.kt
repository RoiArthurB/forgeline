package fr.arthurbrugiere.forgeline.core.model

enum class SearchScope { REPOSITORIES, ISSUES, USERS }

/** What an open conversation is to the person signed in: theirs to review, theirs as its author, or theirs to do. */
enum class WorkKind { REVIEW_REQUESTED, OWN_PULL_REQUESTS, ASSIGNED }

/** An issue or pull request found anywhere on the forge, so it carries its repo. */
data class IssueSearchResult(val repo: RepoId, val issue: IssueSummary)

data class UserSummary(
    val login: String,
    val avatarUrl: String?,
    val isOrganization: Boolean,
    val forge: ForgeInstance = ForgeInstance.GitHub,
)

data class SearchPage<T>(val items: List<T>, val totalCount: Int, val nextPage: Int?)
