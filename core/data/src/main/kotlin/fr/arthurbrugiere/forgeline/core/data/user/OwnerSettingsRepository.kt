package fr.arthurbrugiere.forgeline.core.data.user

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.accountOn
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.OrgMembers
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import javax.inject.Inject
import javax.inject.Singleton

/** What a person sets on their own profile, and an organisation's owners on it, as the account signed in on its forge. */
interface OwnerSettingsRepository {
    /**
     * Whether [login]'s profile page offers its settings: the signed-in person's own, where their forge's API lets
     * anything be set, and an organisation they own. A sign-in that can't tell (one made before Forgeline asked to
     * read organisations) is offered them too: the settings then say to sign in again.
     */
    suspend fun offersSettings(forge: ForgeInstance, login: String, organization: Boolean): Boolean

    suspend fun settings(forge: ForgeInstance, owner: Owner): ForgeResult<OwnerSettings>

    /** Makes [change], and answers the settings as the forge then has them. */
    suspend fun change(forge: ForgeInstance, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings>

    /** Whether [owner] blocks people on [forge] through its API. */
    fun blocksSupported(forge: ForgeInstance, owner: Owner): Boolean

    suspend fun blocked(forge: ForgeInstance, owner: Owner): ForgeResult<List<UserSummary>>

    suspend fun setBlocked(forge: ForgeInstance, owner: Owner, login: String, blocked: Boolean): ForgeResult<Unit>

    /** Whether an organisation's members are managed on [forge] through its API. */
    fun membersSupported(forge: ForgeInstance): Boolean

    suspend fun members(forge: ForgeInstance, organization: String): ForgeResult<OrgMembers>

    /** One change to an organisation's members: see [MemberChange]. */
    suspend fun changeMembers(forge: ForgeInstance, organization: String, change: MemberChange): ForgeResult<Unit>
}

/** Something done to an organisation's members. */
sealed interface MemberChange {
    data class Role(val member: String, val role: String) : MemberChange

    data class Remove(val member: String) : MemberChange

    data class Invite(val invitee: String, val role: String) : MemberChange

    data class Cancel(val invitation: String) : MemberChange
}

@Singleton
class DefaultOwnerSettingsRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    private val users: UserRepository,
) : OwnerSettingsRepository {

    override suspend fun offersSettings(forge: ForgeInstance, login: String, organization: Boolean): Boolean {
        val account = accounts.accountOn(forge) ?: return false
        val api = clients.ownerSettings(forge)
        if (!organization) return api.profileSupported && account.user.login.equals(login, ignoreCase = true)
        if (!api.organizationsSupported) return false
        val token = accounts.tokenOn(forge) ?: return false
        return when (val owns = api.administers(token, login)) {
            is ForgeResult.Success -> owns.value
            is ForgeResult.Failure -> owns.error == ForgeError.Unauthorized
        }
    }

    override suspend fun settings(forge: ForgeInstance, owner: Owner): ForgeResult<OwnerSettings> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.ownerSettings(forge).settings(token, owner)
    }

    override fun blocksSupported(forge: ForgeInstance, owner: Owner): Boolean = clients.ownerSettings(forge).blocksSupported(owner)

    override suspend fun blocked(forge: ForgeInstance, owner: Owner): ForgeResult<List<UserSummary>> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.ownerSettings(forge).blocked(token, owner)
    }

    override suspend fun setBlocked(forge: ForgeInstance, owner: Owner, login: String, blocked: Boolean): ForgeResult<Unit> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.ownerSettings(forge).setBlocked(token, owner, login.trim().removePrefix("@"), blocked)
    }

    override fun membersSupported(forge: ForgeInstance): Boolean = clients.ownerSettings(forge).membersSupported

    override suspend fun members(forge: ForgeInstance, organization: String): ForgeResult<OrgMembers> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.ownerSettings(forge).members(token, organization)
    }

    override suspend fun changeMembers(forge: ForgeInstance, organization: String, change: MemberChange): ForgeResult<Unit> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        val api = clients.ownerSettings(forge)
        return when (change) {
            is MemberChange.Role -> api.setRole(token, organization, change.member, change.role)
            is MemberChange.Remove -> api.removeMember(token, organization, change.member)
            is MemberChange.Invite -> api.invite(token, organization, change.invitee.trim().removePrefix("@"), change.role)
            is MemberChange.Cancel -> api.cancelInvitation(token, organization, change.invitation)
        }
    }

    override suspend fun change(forge: ForgeInstance, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> {
        val token = accounts.tokenOn(forge) ?: return ForgeResult.Failure(ForgeError.Unauthorized)
        return clients.ownerSettings(forge).change(token, owner, change).also {
            // The profile page shows much of it: read again, it shows the change once it is opened.
            if (it is ForgeResult.Success) {
                val login = when (owner) {
                    Owner.Me -> accounts.accountOn(forge)?.user?.login
                    is Owner.Organization -> owner.login
                }
                login?.let { users.user(forge, it) }
            }
        }
    }
}
