package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.model.UserProfile

/** Issues and pull requests share numbering and conversation on every supported forge. */
interface IssueApi {
    suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails>

    /** Oldest first. [page] starts at 1. */
    suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage>
}

interface UserApi {
    suspend fun user(token: String?, login: String): ForgeResult<UserProfile>

    /** Most recently updated first. */
    suspend fun repos(token: String?, login: String): ForgeResult<List<RepoSummary>>

    suspend fun starred(token: String?, login: String): ForgeResult<List<RepoSummary>>

    /** Whether the signed-in user follows [login]. */
    suspend fun isFollowing(token: String, login: String): ForgeResult<Boolean>

    suspend fun setFollowing(token: String, login: String, follow: Boolean): ForgeResult<Unit>
}
