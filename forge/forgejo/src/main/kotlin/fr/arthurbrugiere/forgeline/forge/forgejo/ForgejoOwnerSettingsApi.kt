package fr.arthurbrugiere.forgeline.forge.forgejo

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A Forgejo or Gitea profile (`/user/settings`) and an organisation's (`PATCH /orgs/{org}`, its owners alone).
 * Pronouns, and hiding them, are Forgejo's: a Gitea server doesn't send them, and they aren't offered there.
 */
class ForgejoOwnerSettingsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : OwnerSettingsApi {
    override val profileSupported = true
    override val organizationsSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> httpClient.forgejoApi(forge, token, "user", "settings").toResult { body<JsonElement>().settingsObject().toProfile() }
            is Owner.Organization -> httpClient.forgejoApi(forge, token, "orgs", owner.login).toResult { body<JsonObject>().toOrganization() }
        }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> {
                val body = buildJsonObject {
                    when (change) {
                        is OwnerSettingsChange.Text -> put(PROFILE_TEXTS[change.field] ?: return@forgeCall unsupported, change.value)
                        is OwnerSettingsChange.Switch -> put(PROFILE_SWITCHES[change.field] ?: return@forgeCall unsupported, change.on)
                        is OwnerSettingsChange.Choice -> if (change.field == OwnerChoice.DIFF_STYLE) put("diff_view_style", change.value) else return@forgeCall unsupported
                        is OwnerSettingsChange.Links, is OwnerSettingsChange.Status -> return@forgeCall unsupported
                    }
                }
                httpClient.forgejoApi(forge, token, "user", "settings", method = HttpMethod.Patch, body = body)
                    .toResult { body<JsonElement>().settingsObject().toProfile() }
            }
            is Owner.Organization -> {
                val body = buildJsonObject {
                    when (change) {
                        is OwnerSettingsChange.Text -> put(ORGANIZATION_TEXTS[change.field] ?: return@forgeCall unsupported, change.value)
                        is OwnerSettingsChange.Switch ->
                            if (change.field == OwnerSwitch.REPO_ADMINS_CHANGE_TEAM_ACCESS) put("repo_admin_change_team_access", change.on) else return@forgeCall unsupported
                        is OwnerSettingsChange.Choice -> if (change.field == OwnerChoice.VISIBILITY) put("visibility", change.value) else return@forgeCall unsupported
                        is OwnerSettingsChange.Links, is OwnerSettingsChange.Status -> return@forgeCall unsupported
                    }
                }
                httpClient.forgejoApi(forge, token, "orgs", owner.login, method = HttpMethod.Patch, body = body).toResult { body<JsonObject>().toOrganization() }
            }
        }
    }

    /** Only an organisation's owners change its settings: its Owners team. */
    override suspend fun administers(token: String, organization: String): ForgeResult<Boolean> = forgeCall {
        val me = httpClient.forgejoApi(forge, token, "user")
        if (!me.status.isSuccess()) return@forgeCall me.failure()
        val login = me.body<JsonObject>().text("login")
        httpClient.forgejoApi(forge, token, "users", login, "orgs", organization, "permissions").toResult { body<JsonObject>().bool("is_owner") == true }
    }

    override fun blocksSupported(owner: Owner) = true

    /**
     * Gitea lists who is blocked (`/user/blocks`); Forgejo only their ids (`/user/list_blocked`), each then looked up
     * (`/users/search?uid=`).
     */
    override suspend fun blocked(token: String, owner: Owner): ForgeResult<List<UserSummary>> = forgeCall {
        val gitea = forge.type == ForgeType.GITEA
        val list = httpClient.forgejoApi(forge, token, *owner.base(), if (gitea) "blocks" else "list_blocked", query = mapOf("limit" to "50"))
        if (!list.status.isSuccess()) return@forgeCall list.failure()
        val entries = list.body<JsonArray>().mapNotNull { it as? JsonObject }
        if (gitea) return@forgeCall ForgeResult.Success(entries.map { it.toSummary() })
        val people = coroutineScope {
            entries.mapNotNull { (it["block_id"] as? JsonPrimitive)?.longOrNull }.map { id ->
                async {
                    val found = httpClient.forgejoApi(forge, token, "users", "search", query = mapOf("uid" to id.toString()))
                    if (!found.status.isSuccess()) return@async null
                    ((found.body<JsonObject>()["data"] as? JsonArray)?.firstOrNull() as? JsonObject)?.toSummary()
                }
            }.awaitAll().filterNotNull()
        }
        ForgeResult.Success(people)
    }

    /** Gitea's is one address, put and deleted; Forgejo's two, each put. */
    override suspend fun setBlocked(token: String, owner: Owner, login: String, blocked: Boolean): ForgeResult<Unit> = forgeCall {
        val response = if (forge.type == ForgeType.GITEA) {
            httpClient.forgejoApi(forge, token, *owner.base(), "blocks", login, method = if (blocked) HttpMethod.Put else HttpMethod.Delete)
        } else {
            httpClient.forgejoApi(forge, token, *owner.base(), if (blocked) "block" else "unblock", login, method = HttpMethod.Put)
        }
        response.toResult { }
    }

    private fun Owner.base(): Array<String> = when (this) {
        Owner.Me -> arrayOf("user")
        is Owner.Organization -> arrayOf("orgs", login)
    }

    private fun JsonObject.toSummary() = UserSummary(text("login"), text("avatar_url").ifEmpty { null }, isOrganization = false, forge = forge)

    /** Gitea before 1.18 answered its settings as a list of one. */
    private fun JsonElement.settingsObject(): JsonObject = (this as? JsonArray)?.firstOrNull() as? JsonObject ?: this as JsonObject

    private fun JsonObject.toProfile() = OwnerSettings(
        texts = PROFILE_TEXTS.filterValues { it in this }.mapValuesTo(LinkedHashMap()) { (_, field) -> text(field) },
        switches = PROFILE_SWITCHES.mapNotNull { (switch, field) -> bool(field)?.let { switch to it } }.toMap(LinkedHashMap()),
        choices = text("diff_view_style").takeIf { it.isNotEmpty() }?.let { mapOf(OwnerChoice.DIFF_STYLE to OwnerChoiceValue(it, DIFF_STYLES)) }.orEmpty(),
    )

    private fun JsonObject.toOrganization() = OwnerSettings(
        texts = ORGANIZATION_TEXTS.mapValuesTo(LinkedHashMap()) { (_, field) -> text(field) },
        switches = bool("repo_admin_change_team_access")?.let { mapOf(OwnerSwitch.REPO_ADMINS_CHANGE_TEAM_ACCESS to it) }.orEmpty(),
        choices = text("visibility").takeIf { it.isNotEmpty() }?.let { mapOf(OwnerChoice.VISIBILITY to OwnerChoiceValue(it, VISIBILITIES)) }.orEmpty(),
    )

    private fun JsonObject.text(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.bool(field: String) = (this[field] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)

        val DIFF_STYLES = listOf("unified", "split")

        /** The most open first. */
        val VISIBILITIES = listOf("public", "limited", "private")

        /** In the order they are shown. */
        val PROFILE_TEXTS = linkedMapOf(
            OwnerText.NAME to "full_name",
            OwnerText.BIO to "description",
            OwnerText.PRONOUNS to "pronouns",
            OwnerText.LOCATION to "location",
            OwnerText.WEBSITE to "website",
        )

        val PROFILE_SWITCHES = linkedMapOf(
            OwnerSwitch.HIDE_EMAIL to "hide_email",
            OwnerSwitch.HIDE_PRONOUNS to "hide_pronouns",
            OwnerSwitch.HIDE_ACTIVITY to "hide_activity",
        )

        val ORGANIZATION_TEXTS = linkedMapOf(
            OwnerText.NAME to "full_name",
            OwnerText.BIO to "description",
            OwnerText.LOCATION to "location",
            OwnerText.WEBSITE to "website",
            OwnerText.EMAIL to "email",
        )
    }
}
