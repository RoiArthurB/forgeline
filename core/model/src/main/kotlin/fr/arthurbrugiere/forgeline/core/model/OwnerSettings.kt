package fr.arthurbrugiere.forgeline.core.model

/** Whose settings these are: the signed-in person's own profile, or an organisation's (a GitLab group's) by its login. */
sealed interface Owner {
    data object Me : Owner

    data class Organization(val login: String) : Owner
}

/** Something written about a person or an organisation on their profile. */
enum class OwnerText(val multiline: Boolean = false) {
    NAME,
    BIO(multiline = true),
    PRONOUNS,
    COMPANY,
    LOCATION,
    WEBSITE,
    TWITTER,
    EMAIL,
    BILLING_EMAIL,
}

/** Something a person or an organisation switches on or off: what their profile shows, or what members may do. */
enum class OwnerSwitch {
    HIREABLE,
    HIDE_EMAIL,
    HIDE_PRONOUNS,
    HIDE_ACTIVITY,
    MEMBERS_CREATE_PUBLIC_REPOS,
    MEMBERS_CREATE_PRIVATE_REPOS,
    MEMBERS_CREATE_PAGES,
    MEMBERS_FORK_PRIVATE_REPOS,
    COMMIT_SIGNOFF,
    ORGANIZATION_PROJECTS,
    REPOSITORY_PROJECTS,
    REPO_ADMINS_CHANGE_TEAM_ACCESS,
    REQUEST_ACCESS,
    REQUIRE_TWO_FACTOR,
    SHARE_WITH_GROUP_LOCK,
    EMAILS,
    MENTIONS,
    LFS,

    /** How the forge's website shows a change to this person: one file at a time, and with its changed whitespace. */
    DIFFS_FILE_BY_FILE,
    SHOW_WHITESPACE,
}

/** A setting that is one among a few, its options the forge's own words for them: see [OwnerChoiceValue]. */
enum class OwnerChoice {
    /** Who sees the organisation: `public`, `limited` (signed-in people), `private`; a GitLab group's `internal`. */
    VISIBILITY,

    /** What every member may do in every repository: `none`, `read`, `write`, `admin`. */
    DEFAULT_REPO_PERMISSION,

    /** Who creates projects in a group: `noone`, `owner`, `maintainer`, `developer`. */
    PROJECT_CREATION,

    /** Who creates subgroups: `owner`, `maintainer`. */
    SUBGROUP_CREATION,

    /** How the forge's website shows a diff to this person: `unified`, `split`. */
    DIFF_STYLE,

    /**
     * The two figures a Tangled profile shows, each one of `merged-pull-request-count`, `closed-pull-request-count`,
     * `open-pull-request-count`, `open-issue-count`, `closed-issue-count`, `repository-count`, `star-count`, or none (``).
     */
    FIRST_STAT,
    SECOND_STAT,

    /**
     * Who may comment, open issues and pull requests in a GitHub account's or organisation's public repositories for a
     * while: everyone (``), `existing_users` (accounts older than a day), `contributors_only`, `collaborators_only`.
     */
    INTERACTION_LIMIT,

    /** For how long a new limit holds: `one_day`, `three_days`, `one_week`, `one_month`, `six_months`. */
    INTERACTION_LIMIT_DURATION,
}

/** How long a status shows before it clears itself, GitLab's own choices; null keeps it until it is changed. */
enum class StatusDuration(val minutes: Long) { MIN_30(30), HOUR_3(180), HOUR_8(480), DAY_1(1_440), DAY_3(4_320), DAY_7(10_080), DAY_30(43_200) }

/**
 * What someone says they are up to, beside their name: an [emoji] as the forge writes it (GitHub `:coffee:` or the
 * character itself, GitLab `coffee`), a [message], whether they are [busy], and until when it shows.
 */
data class OwnerStatus(
    val emoji: String = "",
    val message: String = "",
    val busy: Boolean = false,
    val expiresAt: java.time.Instant? = null,
) {
    val isEmpty: Boolean get() = emoji.isBlank() && message.isBlank() && !busy
}

/** What [OwnerChoice] is set to, and what else it can be here, as the forge names them. */
data class OwnerChoiceValue(val value: String, val options: List<String>)

/**
 * What a person or an organisation has set about themselves, as far as their forge's API tells and lets change. A
 * forge without a setting leaves it out of the maps, the order of [texts] being the order they read in. [links] is
 * null where the forge keeps no list of other places to find them, and [maxLinks] how many it keeps.
 */
data class OwnerSettings(
    val texts: Map<OwnerText, String> = emptyMap(),
    val switches: Map<OwnerSwitch, Boolean> = emptyMap(),
    val choices: Map<OwnerChoice, OwnerChoiceValue> = emptyMap(),
    val links: List<String>? = null,
    val maxLinks: Int = 0,
    /** Null where the forge keeps no status (an organisation's, a Forgejo profile's). */
    val status: OwnerStatus? = null,
    /**
     * The repositories the profile shows first, in order; null where they aren't set through the API (GitHub's pins).
     * [pinnable] are those that can be, [maxPinned] how many at most.
     */
    val pinned: List<RepoId>? = null,
    val pinnable: List<RepoId> = emptyList(),
    val maxPinned: Int = 0,
)

/** One thing to change in a person's or an organisation's settings. */
sealed interface OwnerSettingsChange {
    data class Text(val field: OwnerText, val value: String) : OwnerSettingsChange

    data class Switch(val field: OwnerSwitch, val on: Boolean) : OwnerSettingsChange

    data class Choice(val field: OwnerChoice, val value: String) : OwnerSettingsChange

    /** The other places to find them, all of them, in order. */
    data class Links(val links: List<String>) : OwnerSettingsChange

    /** The repositories shown first, all of them, in order. */
    data class Pinned(val repos: List<RepoId>) : OwnerSettingsChange

    /** A new status, showing for [clearAfter] (always when null); an empty one clears it. */
    data class Status(val status: OwnerStatus, val clearAfter: StatusDuration? = null) : OwnerSettingsChange
}

/** These settings once [change] is made: what is shown while the forge is told. */
fun OwnerSettings.with(change: OwnerSettingsChange): OwnerSettings = when (change) {
    is OwnerSettingsChange.Text -> copy(texts = texts + (change.field to change.value))
    is OwnerSettingsChange.Switch -> copy(switches = switches + (change.field to change.on))
    is OwnerSettingsChange.Choice -> choices[change.field]?.let { copy(choices = choices + (change.field to it.copy(value = change.value))) } ?: this
    is OwnerSettingsChange.Links -> copy(links = change.links)
    is OwnerSettingsChange.Status -> copy(status = change.status)
    is OwnerSettingsChange.Pinned -> copy(pinned = change.repos)
}

/** Someone in an organisation, by the [id] its forge acts on them by, and their [role] there (null where it isn't told). */
data class OrgMember(val id: String, val login: String, val avatarUrl: String?, val role: String?)

/** Someone asked to join an organisation who hasn't yet: by login or by email, with the role they'd have. */
data class OrgInvitation(val id: String, val invitee: String, val role: String)

/**
 * An organisation's members and who is asked to join. [roles] are what someone can be made, as the forge names them
 * (GitHub's `admin` and `member`, GitLab's access levels `10` to `50`, a Forgejo team's id), [roleNames] the names of
 * those the app can't name itself (Forgejo's teams). Where [changesRoles] is false a member's role isn't changed here
 * (Forgejo: by their teams), and where [invitesByEmail] is false only someone already on the forge can be asked.
 */
data class OrgMembers(
    val members: List<OrgMember>,
    val roles: List<String>,
    val inviteRoles: List<String> = roles,
    val roleNames: Map<String, String> = emptyMap(),
    val invitations: List<OrgInvitation> = emptyList(),
    val changesRoles: Boolean = true,
    val invitesByEmail: Boolean = false,
)
