package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A GitLab group's settings (`PUT /groups/:id`, its Owners alone), and what a person sets on their own profile through
 * the API. GitLab lets only its administrators change a profile's name, bio, location or links (`PUT /users/:id`):
 * those are set on the website. What is left is how its website shows changes (`/user/preferences`).
 */
class GitLabOwnerSettingsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : OwnerSettingsApi {
    override val profileSupported = true
    override val organizationsSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> httpClient.gitlabApi(forge, token, "user", "preferences").toResult { body<JsonObject>().toPreferences() }
            is Owner.Organization -> httpClient.gitlabApi(forge, token, "groups", owner.login).toResult { body<JsonObject>().toGroup() }
        }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> {
                val field = (change as? OwnerSettingsChange.Switch)?.field?.let(PREFERENCES::get) ?: return@forgeCall unsupported
                // Older GitLab wants both preferences on every change: the other is sent as it stands.
                val current = httpClient.gitlabApi(forge, token, "user", "preferences")
                if (!current.status.isSuccess()) return@forgeCall current.failure()
                val now = current.body<JsonObject>()
                val body = buildJsonObject {
                    PREFERENCES.values.forEach { name -> put(name, if (name == field) change.on else now.bool(name) ?: false) }
                }
                httpClient.gitlabApi(forge, token, "user", "preferences", method = HttpMethod.Put, body = body).toResult { body<JsonObject>().toPreferences() }
            }
            is Owner.Organization -> {
                val body = buildJsonObject {
                    when (change) {
                        is OwnerSettingsChange.Text -> put(GROUP_TEXTS[change.field] ?: return@forgeCall unsupported, change.value)
                        // Mentions are said switched off by GitLab: on here is not disabled there.
                        is OwnerSettingsChange.Switch -> when (change.field) {
                            OwnerSwitch.MENTIONS -> put("mentions_disabled", !change.on)
                            else -> put(GROUP_SWITCHES[change.field] ?: return@forgeCall unsupported, change.on)
                        }
                        is OwnerSettingsChange.Choice -> put(GROUP_CHOICES[change.field]?.first ?: return@forgeCall unsupported, change.value)
                        is OwnerSettingsChange.Links -> return@forgeCall unsupported
                    }
                }
                httpClient.gitlabApi(forge, token, "groups", owner.login, method = HttpMethod.Put, body = body).toResult { body<JsonObject>().toGroup() }
            }
        }
    }

    /** A group's Owners (access level 50), inherited from a parent group too, change its settings. */
    override suspend fun administers(token: String, organization: String): ForgeResult<Boolean> = forgeCall {
        val me = httpClient.gitlabApi(forge, token, "user")
        if (!me.status.isSuccess()) return@forgeCall me.failure()
        val id = (me.body<JsonObject>()["id"] as? JsonPrimitive)?.intOrNull ?: return@forgeCall ForgeResult.Failure(ForgeError.Unreadable)
        val member = httpClient.gitlabApi(forge, token, "groups", organization, "members", "all", id.toString())
        if (member.status.value == 404) return@forgeCall ForgeResult.Success(false)
        member.toResult { ((body<JsonObject>()["access_level"] as? JsonPrimitive)?.intOrNull ?: 0) >= OWNER }
    }

    private fun JsonObject.toPreferences() = OwnerSettings(
        switches = PREFERENCES.mapNotNull { (switch, field) -> bool(field)?.let { switch to it } }.toMap(LinkedHashMap()),
    )

    private fun JsonObject.toGroup() = OwnerSettings(
        texts = GROUP_TEXTS.mapValuesTo(LinkedHashMap()) { (_, field) -> text(field) },
        switches = buildMap {
            GROUP_SWITCHES.forEach { (switch, field) -> bool(field)?.let { put(switch, it) } }
            // Older GitLab says it the other way round.
            if (OwnerSwitch.EMAILS !in this) bool("emails_disabled")?.let { put(OwnerSwitch.EMAILS, !it) }
            bool("mentions_disabled")?.let { put(OwnerSwitch.MENTIONS, !it) }
        }.toSortedMap(compareBy { it.ordinal }),
        choices = GROUP_CHOICES.mapNotNull { (choice, field) -> text(field.first).takeIf { it.isNotEmpty() }?.let { choice to OwnerChoiceValue(it, field.second) } }
            .toMap(LinkedHashMap()),
    )

    private fun JsonObject.text(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.bool(field: String) = (this[field] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)

        const val OWNER = 50

        val PREFERENCES = linkedMapOf(
            OwnerSwitch.DIFFS_FILE_BY_FILE to "view_diffs_file_by_file",
            OwnerSwitch.SHOW_WHITESPACE to "show_whitespace_in_diffs",
        )

        val GROUP_TEXTS = linkedMapOf(OwnerText.NAME to "name", OwnerText.BIO to "description")

        val GROUP_SWITCHES = linkedMapOf(
            OwnerSwitch.REQUEST_ACCESS to "request_access_enabled",
            OwnerSwitch.REQUIRE_TWO_FACTOR to "require_two_factor_authentication",
            OwnerSwitch.SHARE_WITH_GROUP_LOCK to "share_with_group_lock",
            OwnerSwitch.EMAILS to "emails_enabled",
            OwnerSwitch.LFS to "lfs_enabled",
        )

        /** Each choice's field, and its options as GitLab names them, the most open first. */
        val GROUP_CHOICES = linkedMapOf(
            OwnerChoice.VISIBILITY to ("visibility" to listOf("public", "internal", "private")),
            OwnerChoice.PROJECT_CREATION to ("project_creation_level" to listOf("developer", "maintainer", "owner", "noone")),
            OwnerChoice.SUBGROUP_CREATION to ("subgroup_creation_level" to listOf("maintainer", "owner")),
        )
    }
}
