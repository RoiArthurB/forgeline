package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.TimelinePage
import fr.arthurbrugiere.forgeline.core.model.UserProfile

/** Issues and pull requests share numbering and conversation on every supported forge. */
interface IssueApi {
    suspend fun issue(token: String?, ref: IssueRef): ForgeResult<IssueDetails>

    /** Just the title, for rows that only name a conversation: one request where [issue] may need several. */
    suspend fun title(token: String?, ref: IssueRef): ForgeResult<String> = when (val result = issue(token, ref)) {
        is ForgeResult.Failure -> result
        is ForgeResult.Success -> ForgeResult.Success(result.value.title)
    }

    /** Oldest first. [page] starts at 1. */
    suspend fun timeline(token: String?, ref: IssueRef, page: Int): ForgeResult<TimelinePage>

    /** Adds a comment to the conversation, [body] being Markdown, and answers it as the forge kept it. */
    suspend fun comment(token: String, ref: IssueRef, body: String): ForgeResult<TimelineItem.Comment>
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
