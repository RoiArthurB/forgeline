package fr.arthurbrugiere.forgeline.forge.sourcehut

import com.apollographql.apollo.api.Optional
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import fr.arthurbrugiere.forgeline.forge.sourcehut.meta.ProfileQuery
import fr.arthurbrugiere.forgeline.forge.sourcehut.meta.UpdateProfileMutation
import fr.arthurbrugiere.forgeline.forge.sourcehut.meta.type.UserInput
import io.ktor.client.HttpClient

/** meta.sr.ht's GraphQL endpoint: accounts and their profiles. */
internal const val META_SRHT_QUERY = "https://meta.sr.ht/query"

/**
 * A SourceHut profile, on meta.sr.ht (`updateUser`): it wants a token with meta.sr.ht's `PROFILE` scope, written to
 * (read only, it is refused). A new email address takes effect once confirmed from the email SourceHut sends to it.
 * SourceHut has no organisations.
 */
class SourceHutOwnerSettingsApi(private val httpClient: HttpClient) : OwnerSettingsApi {
    override val profileSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        httpClient.srht(token, ProfileQuery(), META_SRHT_QUERY).map { it.me.run { settings(bio, pronouns, location, url, email) } }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        if (owner != Owner.Me || change !is OwnerSettingsChange.Text) return@forgeCall unsupported
        // Emptied is cleared: null, as meta.sr.ht asks; the email is never cleared.
        val value = Optional.present(change.value.trim().ifEmpty { null })
        val input = when (change.field) {
            OwnerText.BIO -> UserInput(bio = value)
            OwnerText.PRONOUNS -> UserInput(pronouns = value)
            OwnerText.LOCATION -> UserInput(location = value)
            OwnerText.WEBSITE -> UserInput(url = value)
            OwnerText.EMAIL -> UserInput(email = Optional.present(change.value.trim().ifEmpty { return@forgeCall unsupported }))
            else -> return@forgeCall unsupported
        }
        httpClient.srht(token, UpdateProfileMutation(Optional.present(input)), META_SRHT_QUERY)
            .map { it.updateUser.run { settings(bio, pronouns, location, url, email) } }
    }

    private fun settings(bio: String?, pronouns: String?, location: String?, url: String?, email: String) = OwnerSettings(
        texts = linkedMapOf(
            OwnerText.BIO to bio.orEmpty(),
            OwnerText.PRONOUNS to pronouns.orEmpty(),
            OwnerText.LOCATION to location.orEmpty(),
            OwnerText.WEBSITE to url.orEmpty(),
            OwnerText.EMAIL to email,
        ),
    )

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)
    }
}
