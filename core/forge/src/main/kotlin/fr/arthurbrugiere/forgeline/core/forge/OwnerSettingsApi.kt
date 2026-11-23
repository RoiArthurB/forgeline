package fr.arthurbrugiere.forgeline.core.forge

import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange

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
}

/** A forge where none of it can be set through the API. */
object NoOwnerSettingsApi : OwnerSettingsApi
