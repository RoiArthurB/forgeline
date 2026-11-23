package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.OwnerSettingsApi
import fr.arthurbrugiere.forgeline.core.model.OrgInvitation
import fr.arthurbrugiere.forgeline.core.model.OrgMember
import fr.arthurbrugiere.forgeline.core.model.OrgMembers
import fr.arthurbrugiere.forgeline.core.model.Owner
import fr.arthurbrugiere.forgeline.core.model.OwnerChoice
import fr.arthurbrugiere.forgeline.core.model.OwnerChoiceValue
import fr.arthurbrugiere.forgeline.core.model.OwnerSettings
import fr.arthurbrugiere.forgeline.core.model.OwnerSettingsChange
import fr.arthurbrugiere.forgeline.core.model.OwnerSwitch
import fr.arthurbrugiere.forgeline.core.model.OwnerText
import fr.arthurbrugiere.forgeline.core.model.UserSummary
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

    /** Who each owner blocks, by its key (`me`, or the organisation's login). */
    val blocks: MutableMap<String, MutableList<UserSummary>> = mutableMapOf("me" to mutableListOf(UserSummary("spammer", null, false)))

    override fun blocksSupported(owner: Owner): Boolean = true

    override suspend fun blocked(token: String, owner: Owner): ForgeResult<List<UserSummary>> {
        calls += "blocked:${owner.key()}"
        failure?.let { return ForgeResult.Failure(it) }
        return ForgeResult.Success(blocks[owner.key()].orEmpty().toList())
    }

    override suspend fun setBlocked(token: String, owner: Owner, login: String, blocked: Boolean): ForgeResult<Unit> {
        calls += "${if (blocked) "block" else "unblock"}:${owner.key()}:$login"
        writeFailure?.let { return ForgeResult.Failure(it) }
        val list = blocks.getOrPut(owner.key()) { mutableListOf() }
        if (blocked) list += UserSummary(login, null, false) else list.removeAll { it.login == login }
        return ForgeResult.Success(Unit)
    }

    override val membersSupported = true

    /** Each organisation's members, by login. */
    val members: MutableMap<String, OrgMembers> = mutableMapOf(
        "octo" to OrgMembers(
            members = listOf(OrgMember("mona", "mona", null, "admin"), OrgMember("hubot", "hubot", null, "member")),
            roles = listOf("admin", "member"),
            invitations = listOf(OrgInvitation("7", "new@octo.example", "member")),
            invitesByEmail = true,
        ),
    )

    override suspend fun members(token: String, organization: String): ForgeResult<OrgMembers> {
        calls += "members:$organization"
        failure?.let { return ForgeResult.Failure(it) }
        return members[organization]?.let { ForgeResult.Success(it) } ?: ForgeResult.Failure(ForgeError.Http(404, "Not Found"))
    }

    private fun write(call: String, organization: String, change: (OrgMembers) -> OrgMembers): ForgeResult<Unit> {
        calls += call
        writeFailure?.let { return ForgeResult.Failure(it) }
        members[organization] = change(members[organization] ?: return ForgeResult.Failure(ForgeError.Http(404, "Not Found")))
        return ForgeResult.Success(Unit)
    }

    override suspend fun setRole(token: String, organization: String, member: String, role: String) = write("role:$organization:$member:$role", organization) { m ->
        m.copy(members = m.members.map { if (it.id == member) it.copy(role = role) else it })
    }

    override suspend fun removeMember(token: String, organization: String, member: String) = write("remove:$organization:$member", organization) { m ->
        m.copy(members = m.members.filterNot { it.id == member })
    }

    override suspend fun invite(token: String, organization: String, invitee: String, role: String) = write("invite:$organization:$invitee:$role", organization) { m ->
        m.copy(invitations = m.invitations + OrgInvitation("new-$invitee", invitee, role))
    }

    override suspend fun cancelInvitation(token: String, organization: String, invitation: String) = write("cancel:$organization:$invitation", organization) { m ->
        m.copy(invitations = m.invitations.filterNot { it.id == invitation })
    }

    override suspend fun administers(token: String, organization: String): ForgeResult<Boolean> {
        calls += "administers:$organization"
        return ForgeResult.Success(organization in administered)
    }
}
