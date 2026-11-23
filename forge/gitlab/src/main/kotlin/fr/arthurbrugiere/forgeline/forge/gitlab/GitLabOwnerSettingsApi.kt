package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.OrgInvitation
import fr.arthurbrugiere.forgeline.core.model.OrgMember
import fr.arthurbrugiere.forgeline.core.model.OrgMembers
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerStatus
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.model.RepoDeletion
import fr.arthurbrugiere.forgeline.core.model.StatusDuration
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
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
 * those are set on the website. What is left is the status (`/user/status`) and how its website shows changes
 * (`/user/preferences`).
 */
class GitLabOwnerSettingsApi(private val httpClient: HttpClient, private val forge: ForgeInstance) : OwnerSettingsApi {
    override val profileSupported = true
    override val organizationsSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> coroutineScope {
                val status = async { httpClient.gitlabApi(forge, token, "user", "status") }
                val preferences = httpClient.gitlabApi(forge, token, "user", "preferences")
                if (!preferences.status.isSuccess()) return@coroutineScope preferences.failure()
                val said = status.await().takeIf { it.status.isSuccess() }?.body<JsonObject>()?.toStatus()
                ForgeResult.Success(preferences.body<JsonObject>().toPreferences().copy(status = said))
            }
            is Owner.Organization -> httpClient.gitlabApi(forge, token, "groups", owner.login).toResult { body<JsonObject>().toGroup() }
        }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> {
                if (change is OwnerSettingsChange.Status) {
                    // A PUT clears what it doesn't name: an empty status is no status.
                    val body = buildJsonObject {
                        val status = change.status
                        status.emoji.trim().trim(':').takeIf { it.isNotEmpty() }?.let { put("emoji", it) }
                        status.message.trim().takeIf { it.isNotEmpty() }?.let { put("message", it) }
                        put("availability", if (status.busy) "busy" else "not_set")
                        change.clearAfter?.let { put("clear_status_after", CLEAR_AFTER.getValue(it)) }
                    }
                    val put = httpClient.gitlabApi(forge, token, "user", "status", method = HttpMethod.Put, body = body)
                    if (!put.status.isSuccess()) return@forgeCall put.failure()
                    return@forgeCall settings(token, owner)
                }
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
                        is OwnerSettingsChange.Links, is OwnerSettingsChange.Status -> return@forgeCall unsupported
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

    override fun avatarSupported(owner: Owner) = true

    /**
     * A file part named `avatar`: one's own with `PUT /user/avatar` (GitLab 17.0 and later; an older server answers 404,
     * said as it is), a group's with its `PUT`. GitLab takes away neither through the API.
     */
    override suspend fun setAvatar(token: String, owner: Owner, image: ByteArray, mimeType: String): ForgeResult<Unit> = forgeCall {
        val path = when (owner) {
            Owner.Me -> listOf("user", "avatar")
            is Owner.Organization -> listOf("groups", owner.login)
        }
        httpClient.request {
            method = HttpMethod.Put
            url {
                takeFrom("${forge.webUrl}/api/v4")
                appendPathSegments(path, encodeSlash = true)
            }
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "avatar", image,
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.${mimeType.substringAfter('/')}\"")
                            },
                        )
                    },
                ),
            )
        }.toResult { }
    }

    override val membersSupported = true

    override val renamesOrganizations = true
    override val deletesOrganizations = true

    /** A group's path, the last part of its address: its name is one of its texts. */
    override suspend fun renameOrganization(token: String, organization: String, name: String): ForgeResult<String> = forgeCall {
        httpClient.gitlabApi(forge, token, "groups", organization, method = HttpMethod.Put, body = buildJsonObject { put("path", name) })
            .toResult { body<JsonObject>().text("full_path").ifEmpty { organization.substringBeforeLast('/', "").let { if (it.isEmpty()) name else "$it/$name" } } }
    }

    /** Accepted, not done: GitLab deletes a group in the background, after a while where it keeps deleted groups. */
    override suspend fun deleteOrganization(token: String, organization: String): ForgeResult<RepoDeletion> = forgeCall {
        httpClient.gitlabApi(forge, token, "groups", organization, method = HttpMethod.Delete).toResult { RepoDeletion.SCHEDULED }
    }

    /**
     * A group's own members with their access levels (inherited ones are its parent's to change), and the emails asked
     * to join (`/invitations`). Someone already on GitLab is added at once, by username; anyone else is invited by email.
     */
    override suspend fun members(token: String, organization: String): ForgeResult<OrgMembers> = forgeCall {
        coroutineScope {
            val invitations = async { httpClient.gitlabApi(forge, token, "groups", organization, "invitations", query = mapOf("per_page" to "100")) }
            val members = httpClient.gitlabApi(forge, token, "groups", organization, "members", query = mapOf("per_page" to "100"))
            if (!members.status.isSuccess()) return@coroutineScope members.failure()
            val listed = members.body<JsonArray>().mapNotNull { it as? JsonObject }.map {
                OrgMember(it.number("id").toString(), it.text("username"), it.text("avatar_url").ifEmpty { null }, it.number("access_level").toString())
            }
            val pending = invitations.await().takeIf { it.status.isSuccess() }?.body<JsonArray>()?.mapNotNull { it as? JsonObject }?.map {
                OrgInvitation(it.text("invite_email"), it.text("invite_email"), it.number("access_level").toString())
            }.orEmpty()
            ForgeResult.Success(OrgMembers(listed, roles = ACCESS_LEVELS, invitations = pending, invitesByEmail = true))
        }
    }

    override suspend fun setRole(token: String, organization: String, member: String, role: String): ForgeResult<Unit> = forgeCall {
        httpClient.gitlabApi(forge, token, "groups", organization, "members", member, method = HttpMethod.Put, body = buildJsonObject { put("access_level", role.toInt()) })
            .toResult { }
    }

    override suspend fun removeMember(token: String, organization: String, member: String): ForgeResult<Unit> = forgeCall {
        httpClient.gitlabApi(forge, token, "groups", organization, "members", member, method = HttpMethod.Delete).toResult { }
    }

    override suspend fun invite(token: String, organization: String, invitee: String, role: String): ForgeResult<Unit> = forgeCall {
        val email = '@' in invitee
        val body = buildJsonObject {
            put(if (email) "email" else "username", invitee)
            put("access_level", role.toInt())
        }
        httpClient.gitlabApi(forge, token, "groups", organization, if (email) "invitations" else "members", method = HttpMethod.Post, body = body).toResult { }
    }

    /** An invitation is named by its email. */
    override suspend fun cancelInvitation(token: String, organization: String, invitation: String): ForgeResult<Unit> = forgeCall {
        httpClient.gitlabApi(forge, token, "groups", organization, "invitations", invitation, method = HttpMethod.Delete).toResult { }
    }

    private fun JsonObject.number(field: String) = (this[field] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.toStatus() = OwnerStatus(
        emoji = text("emoji"),
        message = text("message"),
        busy = text("availability") == "busy",
        expiresAt = text("clear_status_at").takeIf { it.isNotEmpty() }?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
    )

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

        /** Guest, Planner, Reporter, Developer, Maintainer, Owner. */
        val ACCESS_LEVELS = listOf("10", "15", "20", "30", "40", "50")

        val CLEAR_AFTER = mapOf(
            StatusDuration.MIN_30 to "30_minutes", StatusDuration.HOUR_3 to "3_hours", StatusDuration.HOUR_8 to "8_hours", StatusDuration.DAY_1 to "1_day",
            StatusDuration.DAY_3 to "3_days", StatusDuration.DAY_7 to "7_days", StatusDuration.DAY_30 to "30_days",
        )

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
