package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A Tangled profile: the `org.tangled.actor.profile` record (key `self`) on the person's own AT Protocol server. A
 * change reads the record as it is and writes it back whole with that one field changed, so its picture, pinned
 * repositories and whatever a later Tangled adds are kept. Tangled has no organisations.
 */
class TangledOwnerSettingsApi internal constructor(private val account: TangledAccount) : OwnerSettingsApi {
    override val profileSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        record(token).map { it.toSettings() }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        val current = when (val read = record(token)) {
            is ForgeResult.Failure -> return@forgeCall read
            is ForgeResult.Success -> read.value
        }
        val changed = JsonObject(
            current.toMutableMap().apply {
                put("\$type", JsonPrimitive(PROFILE))
                when (change) {
                    is OwnerSettingsChange.Text -> {
                        val field = TEXTS[change.field] ?: return@forgeCall unsupported
                        if (change.value.isBlank()) remove(field) else put(field, JsonPrimitive(change.value.trim()))
                    }
                    is OwnerSettingsChange.Links -> {
                        val links = change.links.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_LINKS)
                        if (links.isEmpty()) remove("links") else put("links", JsonArray(links.map(::JsonPrimitive)))
                    }
                    is OwnerSettingsChange.Choice -> {
                        val shown = current.stats().toMutableList().apply { while (size < 2) add("") }
                        when (change.field) {
                            OwnerChoice.FIRST_STAT -> shown[0] = change.value
                            OwnerChoice.SECOND_STAT -> shown[1] = change.value
                            else -> return@forgeCall unsupported
                        }
                        val stats = shown.filter { it.isNotEmpty() }.distinct()
                        if (stats.isEmpty()) remove("stats") else put("stats", JsonArray(stats.map(::JsonPrimitive)))
                    }
                    is OwnerSettingsChange.Switch, is OwnerSettingsChange.Status -> return@forgeCall unsupported
                }
            },
        )
        account.putRecord(token, PROFILE, SELF, changed).map { changed.toSettings() }
    }

    override fun avatarSupported(owner: Owner) = owner == Owner.Me

    override fun avatarRemovable(owner: Owner) = owner == Owner.Me

    /** Kept on the person's own server as a blob, then named by the profile record (a megabyte at most). */
    override suspend fun setAvatar(token: String, owner: Owner, image: ByteArray, mimeType: String): ForgeResult<Unit> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        val blob = when (val uploaded = account.uploadBlob(token, image, mimeType)) {
            is ForgeResult.Failure -> return@forgeCall uploaded
            is ForgeResult.Success -> uploaded.value
        }
        rewrite(token) { it + ("avatar" to blob) }
    }

    override suspend fun removeAvatar(token: String, owner: Owner): ForgeResult<Unit> = forgeCall {
        if (owner != Owner.Me) return@forgeCall unsupported
        rewrite(token) { it - "avatar" }
    }

    /** Writes the profile record back with [change] made to what it holds now. */
    private suspend fun rewrite(token: String, change: (Map<String, kotlinx.serialization.json.JsonElement>) -> Map<String, kotlinx.serialization.json.JsonElement>): ForgeResult<Unit> {
        val current = when (val read = record(token)) {
            is ForgeResult.Failure -> return read
            is ForgeResult.Success -> read.value
        }
        val record = JsonObject(change(current + ("\$type" to JsonPrimitive(PROFILE))))
        return account.putRecord(token, PROFILE, SELF, record).map { }
    }

    /** The profile record as it is, or an empty one for someone who never wrote one. */
    private suspend fun record(token: String): ForgeResult<JsonObject> {
        val did = when (val me = account.did(token)) {
            is ForgeResult.Failure -> return me
            is ForgeResult.Success -> me.value
        }
        return account.getRecord(did, PROFILE, SELF).map { it ?: JsonObject(emptyMap()) }
    }

    private fun JsonObject.toSettings(): OwnerSettings {
        val stats = stats()
        return OwnerSettings(
            texts = TEXTS.mapValuesTo(LinkedHashMap()) { (_, field) -> (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty() },
            choices = linkedMapOf(
                OwnerChoice.FIRST_STAT to OwnerChoiceValue(stats.getOrElse(0) { "" }, STATS),
                OwnerChoice.SECOND_STAT to OwnerChoiceValue(stats.getOrElse(1) { "" }, STATS),
            ),
            links = (this["links"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            maxLinks = MAX_LINKS,
        )
    }

    private fun JsonObject.stats() = (this["stats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)

        const val SELF = "self"
        const val MAX_LINKS = 5

        val TEXTS = linkedMapOf(OwnerText.BIO to "description", OwnerText.PRONOUNS to "pronouns", OwnerText.LOCATION to "location")

        /** None first, then the lexicon's known values. */
        val STATS = listOf(
            "", "merged-pull-request-count", "closed-pull-request-count", "open-pull-request-count", "open-issue-count", "closed-issue-count",
            "repository-count", "star-count",
        )
    }
}
