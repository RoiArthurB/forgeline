package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.user.MemberChange
import fr.arthurbrugiere.forgeline.core.data.user.OwnerSettingsRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange

/** Offers the settings of the logins in [offered], and keeps [api]'s for them. */
class FakeOwnerSettingsRepository(
    var offered: Set<String> = emptySet(),
    private val api: FakeOwnerSettingsApi = FakeOwnerSettingsApi(),
) : OwnerSettingsRepository {
    override suspend fun offersSettings(forge: ForgeInstance, login: String, organization: Boolean): Boolean = login in offered

    override suspend fun settings(forge: ForgeInstance, owner: Owner): ForgeResult<OwnerSettings> = api.settings("tok", owner)

    override suspend fun change(forge: ForgeInstance, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = api.change("tok", owner, change)

    override fun blocksSupported(forge: ForgeInstance, owner: Owner): Boolean = api.blocksSupported(owner)

    override suspend fun blocked(forge: ForgeInstance, owner: Owner) = api.blocked("tok", owner)

    override suspend fun setBlocked(forge: ForgeInstance, owner: Owner, login: String, blocked: Boolean) = api.setBlocked("tok", owner, login, blocked)

    override fun membersSupported(forge: ForgeInstance) = api.membersSupported

    override suspend fun members(forge: ForgeInstance, organization: String) = api.members("tok", organization)

    override suspend fun changeMembers(forge: ForgeInstance, organization: String, change: MemberChange) = when (change) {
        is MemberChange.Role -> api.setRole("tok", organization, change.member, change.role)
        is MemberChange.Remove -> api.removeMember("tok", organization, change.member)
        is MemberChange.Invite -> api.invite("tok", organization, change.invitee, change.role)
        is MemberChange.Cancel -> api.cancelInvitation("tok", organization, change.invitation)
    }
}
