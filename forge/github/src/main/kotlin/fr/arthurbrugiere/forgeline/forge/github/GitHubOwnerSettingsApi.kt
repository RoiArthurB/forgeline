package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.map
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
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A GitHub profile (`PATCH /user`, and its social accounts) and an organisation's settings (`PATCH /orgs/{org}`).
 * Changing a profile wants the `user` scope, an organisation's blocks and members `admin:org`: sign-ins made before
 * Forgeline asked for them are refused, and told to sign in again ([ForgeError.Unauthorized]). GitHub has no API for a
 * profile picture. The status is the GraphQL API's alone (`changeUserStatus`).
 */
class GitHubOwnerSettingsApi(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String = "https://api.github.com",
    private val clock: Clock = Clock.systemUTC(),
) : OwnerSettingsApi {
    override val profileSupported = true
    override val organizationsSupported = true

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> coroutineScope {
                val links = async { httpClient.gitHubApi(apiBaseUrl, token, "user", "social_accounts", query = mapOf("per_page" to "100")) }
                val status = async { status(token) }
                val limit = async { limit(token, owner) }
                val user = httpClient.gitHubApi(apiBaseUrl, token, "user")
                if (!user.status.isSuccess()) return@coroutineScope user.failure()
                val accounts = links.await()
                val urls = if (accounts.status.isSuccess()) accounts.body<JsonArray>().mapNotNull { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull } else emptyList()
                // A status that can't be read is one not offered, not a profile that can't be.
                val profile = user.body<JsonObject>().toProfile(urls)
                ForgeResult.Success(profile.copy(status = (status.await() as? ForgeResult.Success)?.value, choices = profile.choices + limit.await()))
            }
            is Owner.Organization -> coroutineScope {
                val limit = async { limit(token, owner) }
                val org = httpClient.gitHubApi(apiBaseUrl, token, "orgs", owner.login)
                if (!org.status.isSuccess()) return@coroutineScope org.failure()
                val settings = org.body<JsonObject>().toOrganization()
                ForgeResult.Success(settings.copy(choices = settings.choices + limit.await()))
            }
        }
    }

    /**
     * Who may interact with [owner]'s public repositories, and for how long a new limit holds (read from when the one
     * in place ends); nothing where it can't be read (a sign-in without the scope for it).
     */
    private suspend fun limit(token: String, owner: Owner): Map<OwnerChoice, OwnerChoiceValue> {
        val response = httpClient.gitHubApi(apiBaseUrl, token, *owner.limits())
        if (!response.status.isSuccess()) return emptyMap()
        // No content, or an empty object, where there is no limit.
        val body = if (response.status == HttpStatusCode.NoContent) null else runCatching { response.body<JsonObject>() }.getOrNull()
        val limit = body?.text("limit").orEmpty()
        val left = body?.text("expires_at")?.takeIf { it.isNotEmpty() }?.let { runCatching { Duration.between(clock.instant(), Instant.parse(it)) }.getOrNull() }
        val duration = left?.let { remaining -> LIMIT_DURATIONS.minBy { (_, length) -> (length - remaining).abs() }.first } ?: "one_day"
        return linkedMapOf(
            OwnerChoice.INTERACTION_LIMIT to OwnerChoiceValue(limit, LIMITS),
            OwnerChoice.INTERACTION_LIMIT_DURATION to OwnerChoiceValue(duration, LIMIT_DURATIONS.map { it.first }),
        )
    }

    /** A limit, or how long it holds, put with the other as it stands; no limit is deleting it. */
    private suspend fun setLimit(token: String, owner: Owner, change: OwnerSettingsChange.Choice): ForgeResult<Unit> {
        val current = limit(token, owner)
        val limit = if (change.field == OwnerChoice.INTERACTION_LIMIT) change.value else current[OwnerChoice.INTERACTION_LIMIT]?.value.orEmpty()
        val duration = if (change.field == OwnerChoice.INTERACTION_LIMIT_DURATION) change.value else current[OwnerChoice.INTERACTION_LIMIT_DURATION]?.value ?: "one_day"
        val response = if (limit.isEmpty()) {
            httpClient.gitHubApi(apiBaseUrl, token, *owner.limits(), method = HttpMethod.Delete)
        } else {
            httpClient.gitHubApi(
                apiBaseUrl, token, *owner.limits(), method = HttpMethod.Put,
                body = buildJsonObject {
                    put("limit", limit)
                    put("expiry", duration)
                },
            )
        }
        return if (response.status.isSuccess()) ForgeResult.Success(Unit) else response.refusal(owner.scope)
    }

    private fun Owner.limits(): Array<String> = when (this) {
        Owner.Me -> arrayOf("user", "interaction-limits")
        is Owner.Organization -> arrayOf("orgs", login, "interaction-limits")
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> {
                if (change is OwnerSettingsChange.Choice && change.field in LIMIT_CHOICES) {
                    val set = setLimit(token, owner, change)
                    if (set is ForgeResult.Failure) return@forgeCall set
                    return@forgeCall settings(token, owner)
                }
                if (change is OwnerSettingsChange.Status) {
                    val changed = setStatus(token, change)
                    if (changed is ForgeResult.Failure) return@forgeCall changed
                    return@forgeCall settings(token, owner)
                }
                val response = if (change is OwnerSettingsChange.Links) {
                    changeLinks(token, change.links) ?: return@forgeCall settings(token, owner)
                } else {
                    val body = buildJsonObject {
                        when (change) {
                            is OwnerSettingsChange.Text -> put(PROFILE_TEXTS[change.field] ?: return@forgeCall unsupported, change.value)
                            is OwnerSettingsChange.Switch -> if (change.field == OwnerSwitch.HIREABLE) put("hireable", change.on) else return@forgeCall unsupported
                            else -> return@forgeCall unsupported
                        }
                    }
                    httpClient.gitHubApi(apiBaseUrl, token, "user", method = HttpMethod.Patch, body = body)
                }
                if (!response.status.isSuccess()) return@forgeCall response.refusal(PROFILE_SCOPE)
                settings(token, owner)
            }
            is Owner.Organization -> {
                if (change is OwnerSettingsChange.Choice && change.field in LIMIT_CHOICES) {
                    val set = setLimit(token, owner, change)
                    if (set is ForgeResult.Failure) return@forgeCall set
                    return@forgeCall settings(token, owner)
                }
                val body = buildJsonObject {
                    when (change) {
                        is OwnerSettingsChange.Text -> put(ORGANIZATION_TEXTS[change.field] ?: return@forgeCall unsupported, change.value)
                        is OwnerSettingsChange.Switch -> put(ORGANIZATION_SWITCHES[change.field] ?: return@forgeCall unsupported, change.on)
                        is OwnerSettingsChange.Choice ->
                            if (change.field == OwnerChoice.DEFAULT_REPO_PERMISSION) put("default_repository_permission", change.value) else return@forgeCall unsupported
                        is OwnerSettingsChange.Links, is OwnerSettingsChange.Status -> return@forgeCall unsupported
                    }
                }
                val response = httpClient.gitHubApi(apiBaseUrl, token, "orgs", owner.login, method = HttpMethod.Patch, body = body)
                if (!response.status.isSuccess()) return@forgeCall response.refusal(ORGANIZATION_SCOPE)
                response.toResult { body<JsonObject>().toOrganization() }
            }
        }
    }

    /** Owners are the organisation's admins; a pending invitation isn't one yet. */
    override suspend fun administers(token: String, organization: String): ForgeResult<Boolean> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "user", "memberships", "orgs", organization)
        when {
            response.status.isSuccess() -> response.toResult { body<JsonObject>().let { it.text("role") == "admin" && it.text("state") == "active" } }
            // Not a member at all, or hidden from a sign-in that may not read organisations.
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Forbidden -> response.refusal(ORGANIZATION_SCOPE).let {
                if (it.error == ForgeError.Unauthorized) it else ForgeResult.Success(false)
            }
            else -> response.failure()
        }
    }

    override fun blocksSupported(owner: Owner) = true

    override suspend fun blocked(token: String, owner: Owner): ForgeResult<List<UserSummary>> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, *owner.blocks(), query = mapOf("per_page" to "100"))
        if (!response.status.isSuccess()) return@forgeCall response.refusal(owner.scope)
        response.toResult {
            body<JsonArray>().mapNotNull { it as? JsonObject }.map { UserSummary(it.text("login"), it.text("avatar_url").ifEmpty { null }, it.text("type") == "Organization") }
        }
    }

    override suspend fun setBlocked(token: String, owner: Owner, login: String, blocked: Boolean): ForgeResult<Unit> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, *owner.blocks(), login, method = if (blocked) HttpMethod.Put else HttpMethod.Delete)
        if (!response.status.isSuccess()) return@forgeCall response.refusal(owner.scope)
        ForgeResult.Success(Unit)
    }

    private fun Owner.blocks(): Array<String> = when (this) {
        Owner.Me -> arrayOf("user", "blocks")
        is Owner.Organization -> arrayOf("orgs", login, "blocks")
    }

    private val Owner.scope get() = if (this == Owner.Me) PROFILE_SCOPE else ORGANIZATION_SCOPE

    override val membersSupported = true

    /** GitHub has no API to rename an organisation; deleting it takes its repositories with it. */
    override val deletesOrganizations = true

    override suspend fun deleteOrganization(token: String, organization: String): ForgeResult<RepoDeletion> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, method = HttpMethod.Delete)
        if (!response.status.isSuccess()) response.refusal(ORGANIZATION_SCOPE) else ForgeResult.Success(RepoDeletion.DELETED)
    }

    /**
     * Owners and members apart (`/members?role=`), so each says which, and the invitations not yet taken
     * (`/invitations`), which only owners may read.
     */
    override suspend fun members(token: String, organization: String): ForgeResult<OrgMembers> = forgeCall {
        coroutineScope {
            val roles = MEMBER_ROLES.map { role ->
                async { role to httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, "members", query = mapOf("role" to role, "per_page" to "100")) }
            }
            val invitations = async { httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, "invitations", query = mapOf("per_page" to "100")) }
            val members = roles.awaitAll().flatMap { (role, response) ->
                if (!response.status.isSuccess()) return@coroutineScope response.refusal(ORGANIZATION_SCOPE)
                response.body<JsonArray>().mapNotNull { it as? JsonObject }.map { OrgMember(it.text("login"), it.text("login"), it.text("avatar_url").ifEmpty { null }, role) }
            }
            val pending = invitations.await().takeIf { it.status.isSuccess() }?.body<JsonArray>()?.mapNotNull { it as? JsonObject }?.map {
                OrgInvitation(it.number("id").toString(), it.text("login").ifEmpty { it.text("email") }, it.text("role"))
            }.orEmpty()
            ForgeResult.Success(OrgMembers(members, roles = MEMBER_ROLES, inviteRoles = INVITE_ROLES, invitations = pending, invitesByEmail = true))
        }
    }

    override suspend fun setRole(token: String, organization: String, member: String, role: String): ForgeResult<Unit> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, "memberships", member, method = HttpMethod.Put, body = buildJsonObject { put("role", role) })
        if (!response.status.isSuccess()) response.refusal(ORGANIZATION_SCOPE) else ForgeResult.Success(Unit)
    }

    override suspend fun removeMember(token: String, organization: String, member: String): ForgeResult<Unit> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, "memberships", member, method = HttpMethod.Delete)
        if (!response.status.isSuccess()) response.refusal(ORGANIZATION_SCOPE) else ForgeResult.Success(Unit)
    }

    /** By email, or by login: GitHub wants the person's id for that, looked up first. */
    override suspend fun invite(token: String, organization: String, invitee: String, role: String): ForgeResult<Unit> = forgeCall {
        val body = if ('@' in invitee) {
            buildJsonObject {
                put("email", invitee)
                put("role", role)
            }
        } else {
            val user = httpClient.gitHubApi(apiBaseUrl, token, "users", invitee)
            if (!user.status.isSuccess()) return@forgeCall user.failure()
            buildJsonObject {
                put("invitee_id", user.body<JsonObject>().number("id"))
                put("role", role)
            }
        }
        val response = httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, "invitations", method = HttpMethod.Post, body = body)
        if (!response.status.isSuccess()) response.refusal(ORGANIZATION_SCOPE) else ForgeResult.Success(Unit)
    }

    override suspend fun cancelInvitation(token: String, organization: String, invitation: String): ForgeResult<Unit> = forgeCall {
        val response = httpClient.gitHubApi(apiBaseUrl, token, "orgs", organization, "invitations", invitation, method = HttpMethod.Delete)
        if (!response.status.isSuccess()) response.refusal(ORGANIZATION_SCOPE) else ForgeResult.Success(Unit)
    }

    private fun JsonObject.number(field: String) = (this[field] as? JsonPrimitive)?.longOrNull ?: 0L

    /** The signed-in person's status, from the GraphQL API alone; an empty one for none. */
    private suspend fun status(token: String): ForgeResult<OwnerStatus> =
        httpClient.gitHubGraphQl(apiBaseUrl, token, "query { viewer { status { emoji message indicatesLimitedAvailability expiresAt } } }").map { data ->
            val status = ((data["viewer"] as? JsonObject)?.get("status") as? JsonObject) ?: return@map OwnerStatus()
            OwnerStatus(
                emoji = status.text("emoji"),
                message = status.text("message"),
                busy = status.bool("indicatesLimitedAvailability") ?: false,
                expiresAt = status.text("expiresAt").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }

    /** `changeUserStatus`; nothing written clears it. */
    private suspend fun setStatus(token: String, change: OwnerSettingsChange.Status): ForgeResult<JsonObject> {
        val status = change.status
        val input = buildJsonObject {
            put("emoji", status.emoji.trim().ifEmpty { null })
            put("message", status.message.trim().ifEmpty { null })
            put("limitedAvailability", status.busy)
            put("expiresAt", change.clearAfter?.let { clock.instant().plus(Duration.ofMinutes(it.minutes)).toString() })
        }
        return httpClient.gitHubGraphQl(
            apiBaseUrl, token,
            "mutation(${'$'}input: ChangeUserStatusInput!) { changeUserStatus(input: ${'$'}input) { status { message } } }",
            variables = buildJsonObject { put("input", input) },
        )
    }

    /** Adds the links that are new and removes those let go; null when nothing changed. */
    private suspend fun changeLinks(token: String, wanted: List<String>): HttpResponse? {
        val current = httpClient.gitHubApi(apiBaseUrl, token, "user", "social_accounts", query = mapOf("per_page" to "100"))
        if (!current.status.isSuccess()) return current
        val had = current.body<JsonArray>().mapNotNull { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull }
        val gone = had - wanted.toSet()
        val added = wanted.filter { it.isNotBlank() } - had.toSet()
        var last: HttpResponse? = null
        if (gone.isNotEmpty()) {
            last = httpClient.gitHubApi(apiBaseUrl, token, "user", "social_accounts", method = HttpMethod.Delete, body = urls(gone))
            if (!last.status.isSuccess()) return last
        }
        if (added.isNotEmpty()) last = httpClient.gitHubApi(apiBaseUrl, token, "user", "social_accounts", method = HttpMethod.Post, body = urls(added))
        return last
    }

    private fun urls(urls: List<String>) = buildJsonObject { put("account_urls", JsonArray(urls.map(::JsonPrimitive))) }

    /**
     * A refusal for want of [scope], which sign-ins made before Forgeline asked for it lack, reads as a sign-in to make
     * again; GitHub lists a classic or OAuth token's scopes with every answer (a fine-grained token's are not judged).
     */
    private suspend fun HttpResponse.refusal(scope: String): ForgeResult.Failure {
        val scopes = headers["X-OAuth-Scopes"]?.split(',')?.map { it.trim() }
        val refused = status == HttpStatusCode.NotFound || status == HttpStatusCode.Forbidden
        return if (refused && scopes != null && scope !in scopes) ForgeResult.Failure(ForgeError.Unauthorized) else failure()
    }

    private fun JsonObject.toProfile(links: List<String>) = OwnerSettings(
        texts = PROFILE_TEXTS.mapValuesTo(LinkedHashMap()) { (_, field) -> text(field) },
        switches = linkedMapOf(OwnerSwitch.HIREABLE to (bool("hireable") ?: false)),
        links = links,
        maxLinks = MAX_SOCIAL_ACCOUNTS,
    )

    private fun JsonObject.toOrganization() = OwnerSettings(
        // The billing email is told to owners alone.
        texts = ORGANIZATION_TEXTS.filter { (text, field) -> text != OwnerText.BILLING_EMAIL || field in this }.mapValuesTo(LinkedHashMap()) { (_, field) -> text(field) },
        switches = ORGANIZATION_SWITCHES.mapNotNull { (switch, field) -> bool(field)?.let { switch to it } }.toMap(LinkedHashMap()),
        choices = text("default_repository_permission").takeIf { it.isNotEmpty() }
            ?.let { mapOf(OwnerChoice.DEFAULT_REPO_PERMISSION to OwnerChoiceValue(it, PERMISSIONS)) }.orEmpty(),
    )

    private fun JsonObject.text(field: String) = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.bool(field: String) = (this[field] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        val unsupported = ForgeResult.Failure(ForgeError.Unsupported)

        const val PROFILE_SCOPE = "user"
        const val ORGANIZATION_SCOPE = "admin:org"

        /** GitHub keeps four social accounts on a profile. */
        const val MAX_SOCIAL_ACCOUNTS = 4

        val PERMISSIONS = listOf("none", "read", "write", "admin")

        val LIMIT_CHOICES = setOf(OwnerChoice.INTERACTION_LIMIT, OwnerChoice.INTERACTION_LIMIT_DURATION)

        /** No limit first, then from the widest to the narrowest. */
        val LIMITS = listOf("", "existing_users", "contributors_only", "collaborators_only")

        val LIMIT_DURATIONS = listOf(
            "one_day" to Duration.ofDays(1), "three_days" to Duration.ofDays(3), "one_week" to Duration.ofDays(7),
            "one_month" to Duration.ofDays(30), "six_months" to Duration.ofDays(182),
        )

        /** An organisation's owners are its admins. */
        val MEMBER_ROLES = listOf("admin", "member")
        val INVITE_ROLES = listOf("direct_member", "admin", "billing_manager")

        /**
         * In the order they are shown. The email is the public one, among the person's verified addresses; pronouns are
         * the website's alone (not in `PATCH /user`).
         */
        val PROFILE_TEXTS = linkedMapOf(
            OwnerText.NAME to "name",
            OwnerText.BIO to "bio",
            OwnerText.COMPANY to "company",
            OwnerText.LOCATION to "location",
            OwnerText.WEBSITE to "blog",
            OwnerText.TWITTER to "twitter_username",
            OwnerText.EMAIL to "email",
        )

        val ORGANIZATION_TEXTS = linkedMapOf(
            OwnerText.NAME to "name",
            OwnerText.BIO to "description",
            OwnerText.COMPANY to "company",
            OwnerText.LOCATION to "location",
            OwnerText.WEBSITE to "blog",
            OwnerText.TWITTER to "twitter_username",
            OwnerText.EMAIL to "email",
            OwnerText.BILLING_EMAIL to "billing_email",
        )

        val ORGANIZATION_SWITCHES = linkedMapOf(
            OwnerSwitch.MEMBERS_CREATE_PUBLIC_REPOS to "members_can_create_public_repositories",
            OwnerSwitch.MEMBERS_CREATE_PRIVATE_REPOS to "members_can_create_private_repositories",
            OwnerSwitch.MEMBERS_CREATE_PAGES to "members_can_create_pages",
            OwnerSwitch.MEMBERS_FORK_PRIVATE_REPOS to "members_can_fork_private_repositories",
            OwnerSwitch.COMMIT_SIGNOFF to "web_commit_signoff_required",
            OwnerSwitch.ORGANIZATION_PROJECTS to "has_organization_projects",
            OwnerSwitch.REPOSITORY_PROJECTS to "has_repository_projects",
        )
    }
}
