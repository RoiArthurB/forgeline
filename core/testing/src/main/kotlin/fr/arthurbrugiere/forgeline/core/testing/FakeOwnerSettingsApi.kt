package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.model.with

/** A profile as GitHub's would read: the texts it keeps, whether its person is for hire, and two links. */
fun ownerSettings(): OwnerSettings = OwnerSettings(
    texts = linkedMapOf(
        OwnerText.NAME to "Mona Lisa", OwnerText.BIO to "Builds tools", OwnerText.COMPANY to "", OwnerText.LOCATION to "Paris",
        OwnerText.WEBSITE to "https://mona.example", OwnerText.TWITTER to "",
    ),
    switches = linkedMapOf(OwnerSwitch.HIREABLE to false),
    links = listOf("https://mastodon.social/@mona"),
    maxLinks = 4,
)

/** An organisation's, with a choice and a rule for its members. */
fun organizationSettings(): OwnerSettings = OwnerSettings(
    texts = linkedMapOf(OwnerText.NAME to "Octo", OwnerText.BIO to "We make tools", OwnerText.WEBSITE to "", OwnerText.EMAIL to ""),
    switches = linkedMapOf(OwnerSwitch.MEMBERS_CREATE_PUBLIC_REPOS to true),
    choices = linkedMapOf(OwnerChoice.DEFAULT_REPO_PERMISSION to OwnerChoiceValue("read", listOf("none", "read", "write", "admin"))),
)

/** Keeps one person's [profile] and their organisations' settings, and makes each change; records every call. */
class FakeOwnerSettingsApi(
    var profile: OwnerSettings = ownerSettings(),
    val organizations: MutableMap<String, OwnerSettings> = mutableMapOf("octo" to organizationSettings()),
    override val profileSupported: Boolean = true,
    override val organizationsSupported: Boolean = true,
) : OwnerSettingsApi {
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val changes: MutableList<OwnerSettingsChange> = java.util.concurrent.CopyOnWriteArrayList()

    /** When set, reading fails with it. */
    var failure: ForgeError? = null

    /** When set, every change fails with it and nothing changes. */
    var writeFailure: ForgeError? = null

    /** The organisations the signed-in person owns. */
    var administered: Set<String> = setOf("octo")

    private fun Owner.key() = when (this) {
        Owner.Me -> "me"
        is Owner.Organization -> login
    }

    private fun of(owner: Owner): OwnerSettings? = when (owner) {
        Owner.Me -> profile
        is Owner.Organization -> organizations[owner.login]
    }

    override suspend fun settings(token: String, owner: Owner): ForgeResult<OwnerSettings> {
        calls += "settings:${owner.key()}"
        failure?.let { return ForgeResult.Failure(it) }
        return of(owner)?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    override suspend fun change(token: String, owner: Owner, change: OwnerSettingsChange): ForgeResult<OwnerSettings> {
        calls += "change:${owner.key()}"
        changes += change
        writeFailure?.let { return ForgeResult.Failure(it) }
        val changed = of(owner)?.with(change) ?: return ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
        when (owner) {
            Owner.Me -> profile = changed
            is Owner.Organization -> organizations[owner.login] = changed
        }
        return ForgeResult.Success(changed)
    }

    override suspend fun administers(token: String, organization: String): ForgeResult<Boolean> {
        calls += "administers:$organization"
        return ForgeResult.Success(organization in administered)
    }
}
