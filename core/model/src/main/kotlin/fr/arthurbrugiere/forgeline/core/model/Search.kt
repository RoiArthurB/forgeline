package fr.arthurbrugiere.forgeline.core.model

enum class SearchScope { REPOSITORIES, ISSUES, USERS }

/** An issue or pull request found anywhere on the forge, so it carries its repo. */
data class IssueSearchResult(val repo: RepoId, val issue: IssueSummary)

data class UserSummary(val login: String, val avatarUrl: String?, val isOrganization: Boolean)

data class SearchPage<T>(val items: List<T>, val totalCount: Int, val nextPage: Int?)
