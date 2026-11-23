package fr.arthurbrugiere.forgeline.core.testing

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
}
