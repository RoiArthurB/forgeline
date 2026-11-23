package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.OrgMembers
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.UserSummary

/**
 * What a person sets on their own profile, and an organisation's owners on it. Every call needs a token. Whatever a
 * forge's API doesn't reach is left out, and its website is where to set it: unsupported here, never faked.
 */
interface OwnerSettingsApi {
    /** Whether the signed-in person's own profile can be read and changed here. */
    val profileSupported: Boolean get() = false

    /** Whether an organisation's (a GitLab group's) can be. */
    val organizationsSupported: Boolean get() = false

    suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Makes [change], and answers the settings as the forge then has them. */
    suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether the signed-in person may change [organization]'s settings: one of its owners (a group's Owner). */
    suspend fun administers(token: String, organization: String): ForgeResult<Boolean> = ForgeResult.Success(false)

    /** Whether [owner] blocks people here: GitLab lets only its administrators, Tangled and SourceHut have no blocks. */
    fun blocksSupported(owner: Owner): Boolean = false

    /** The people [owner] blocks: they can't comment, open issues or pull requests, or follow. */
    suspend fun blocked(token: String, owner: Owner): ForgeResult<List<UserSummary>> = ForgeResult.Failure(ForgeError.Unsupported)

    suspend fun setBlocked(token: String, owner: Owner, login: String, blocked: Boolean): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Whether an organisation's members are listed and managed here. */
    val membersSupported: Boolean get() = false

    suspend fun members(token: String, organization: String): ForgeResult<OrgMembers> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Makes [member] (by [OrgMember.id]) one of [OrgMembers.roles]. */
    suspend fun setRole(token: String, organization: String, member: String, role: String): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    suspend fun removeMember(token: String, organization: String, member: String): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    /** Asks [invitee] (a login, or an email where [OrgMembers.invitesByEmail]) to join as [role]. */
    suspend fun invite(token: String, organization: String, invitee: String, role: String): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)

    suspend fun cancelInvitation(token: String, organization: String, invitation: String): ForgeResult<Unit> = ForgeResult.Failure(ForgeError.Unsupported)
}

/** A forge where none of it can be set through the API. */
object NoOwnerSettingsApi : OwnerSettingsApi
