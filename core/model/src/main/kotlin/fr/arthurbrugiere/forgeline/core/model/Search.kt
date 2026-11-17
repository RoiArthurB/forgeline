package fr.arthurbrugiere.forgeline.core.model

enum class SearchScope {
    REPOSITORIES,
    ISSUES,
    USERS,
    ;

    /** The orders a search of this scope can ask for, the forge's own first. People have none: forges agree on no other. */
    val sorts: List<SearchSort>
        get() = when (this) {
            REPOSITORIES -> listOf(SearchSort.BEST_MATCH, SearchSort.STARS, SearchSort.UPDATED)
            ISSUES -> listOf(SearchSort.BEST_MATCH, SearchSort.NEWEST, SearchSort.UPDATED)
            USERS -> listOf(SearchSort.BEST_MATCH)
        }
}

/**
 * The order a search's results come in, each forge sorting its own: [BEST_MATCH] as the forge ranks them, [STARS] the
 * most starred first, [UPDATED] the most recently updated first, [NEWEST] the most recently opened first.
 */
enum class SearchSort { BEST_MATCH, STARS, UPDATED, NEWEST }

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
