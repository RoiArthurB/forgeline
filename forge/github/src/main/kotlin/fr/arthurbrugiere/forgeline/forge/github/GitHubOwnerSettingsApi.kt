package fr.arthurbrugiere.forgeline.forge.github

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.forge.map
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerStatus
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.model.UserSummary
import fr.arthurbrugiere.forgeline.forge.common.forgeCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
                val user = httpClient.gitHubApi(apiBaseUrl, token, "user")
                if (!user.status.isSuccess()) return@coroutineScope user.failure()
                val accounts = links.await()
                val urls = if (accounts.status.isSuccess()) accounts.body<JsonArray>().mapNotNull { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull } else emptyList()
                // A status that can't be read is one not offered, not a profile that can't be.
                ForgeResult.Success(user.body<JsonObject>().toProfile(urls).copy(status = (status.await() as? ForgeResult.Success)?.value))
            }
            is Owner.Organization -> httpClient.gitHubApi(apiBaseUrl, token, "orgs", owner.login).toResult { body<JsonObject>().toOrganization() }
        }
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> = forgeCall {
        when (owner) {
            Owner.Me -> {
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
