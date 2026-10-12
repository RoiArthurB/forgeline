package fr.arthurbrugiere.forgeline.core.model

/** Something a repository's owner switches on or off: a feature it has, or a rule for merging into it. */
enum class RepoSwitch(val merging: Boolean = false) {
    ISSUES,
    PULL_REQUESTS,
    WIKI,
    DISCUSSIONS,
    PROJECTS,
    CI,
    RELEASES,
    PACKAGES,
    TEMPLATE,
    MERGE_COMMIT(merging = true),
    SQUASH_MERGE(merging = true),
    REBASE_MERGE(merging = true),
    FAST_FORWARD_MERGE(merging = true),
    AUTO_MERGE(merging = true),
    DELETE_BRANCH_ON_MERGE(merging = true),
    REQUIRE_PIPELINE(merging = true),
    REQUIRE_RESOLVED_THREADS(merging = true),
}

/** The one way a GitLab project merges: a merge commit, one only once rebased, or no merge commit at all. */
enum class MergeStyle { MERGE_COMMIT, SEMI_LINEAR, FAST_FORWARD }

/** Who can see a repository: everyone, everyone signed in on its server or in its enterprise, or its members. */
enum class RepoVisibility { PUBLIC, INTERNAL, PRIVATE }

/** What became of a repository asked to be deleted: gone, or kept a while by its forge before it is (gitlab.com). */
enum class RepoDeletion { DELETED, SCHEDULED }

/**
 * What a repository's owner has set, as far as its forge's API tells and lets change. A forge without a setting
 * leaves it out: no [website] field ([hasWebsite] false), a switch missing from [switches], a null [mergeStyle]
 * (GitLab alone merges one way) or [defaultMerge] (Forgejo alone proposes one).
 */
data class RepoSettings(
    val description: String,
    val website: String = "",
    val hasWebsite: Boolean = true,
    val topics: List<String> = emptyList(),
    val defaultBranch: String,
    val switches: Map<RepoSwitch, Boolean> = emptyMap(),
    /** Switched on for the project's members only (GitLab): shown as on, and said. */
    val membersOnly: Set<RepoSwitch> = emptySet(),
    val mergeStyle: MergeStyle? = null,
    val defaultMerge: MergeMethod? = null,
    val visibility: RepoVisibility = RepoVisibility.PUBLIC,
    /** What [visibility] can be here, the most open first. */
    val visibilities: List<RepoVisibility> = listOf(RepoVisibility.PUBLIC, RepoVisibility.PRIVATE),
    /** Kept to be read, and no longer written to. */
    val archived: Boolean = false,
)

/** One thing to change in a repository's settings. */
sealed interface RepoSettingsChange {
    data class Description(val text: String) : RepoSettingsChange

    data class Website(val url: String) : RepoSettingsChange

    data class Topics(val topics: List<String>) : RepoSettingsChange

    data class DefaultBranch(val branch: String) : RepoSettingsChange

    data class Switch(val switch: RepoSwitch, val on: Boolean) : RepoSettingsChange

    data class Style(val style: MergeStyle) : RepoSettingsChange

    data class DefaultMerge(val method: MergeMethod) : RepoSettingsChange

    data class Visibility(val visibility: RepoVisibility) : RepoSettingsChange

    data class Archived(val archived: Boolean) : RepoSettingsChange
}

/** These settings once [change] is made: what is shown while the forge is told. */
fun RepoSettings.with(change: RepoSettingsChange): RepoSettings = when (change) {
    is RepoSettingsChange.Description -> copy(description = change.text)
    is RepoSettingsChange.Website -> copy(website = change.url)
    is RepoSettingsChange.Topics -> copy(topics = change.topics)
    is RepoSettingsChange.DefaultBranch -> copy(defaultBranch = change.branch)
    // Switched by hand, it is on for everyone.
    is RepoSettingsChange.Switch -> copy(switches = switches + (change.switch to change.on), membersOnly = membersOnly - change.switch)
    is RepoSettingsChange.Style -> copy(mergeStyle = change.style)
    is RepoSettingsChange.DefaultMerge -> copy(defaultMerge = change.method)
    is RepoSettingsChange.Visibility -> copy(visibility = change.visibility)
    is RepoSettingsChange.Archived -> copy(archived = change.archived)
}

/**
 * A topic as forges take them: lowercase, letters, digits and hyphens, not starting with a hyphen; null when nothing
 * is left of [text].
 */
fun normalizeTopic(text: String): String? =
    text.trim().lowercase().replace(Regex("[\\s_]+"), "-").replace(Regex("[^a-z0-9-]"), "").trimStart('-').take(50).ifEmpty { null }

/** What a repository's website is built from: what a workflow uploads, or a folder of a branch as it is. */
sealed interface PagesBuild {
    data object Workflow : PagesBuild

    /** [folder] is `/` or `/docs`: GitHub publishes no other. */
    data class Branch(val branch: String, val folder: String = "/") : PagesBuild
}

/**
 * The website a forge serves from a repository. [published] says whether there is one; [canPublish] whether it is
 * switched on and off from here (GitHub) or by what the repository's CI does (GitLab). A null [httpsEnforced] or
 * [customDomain] is a setting the forge's API doesn't have, and a null [build] a site that has no source to pick.
 */
data class PagesSite(
    val published: Boolean,
    val url: String? = null,
    val canPublish: Boolean = false,
    val build: PagesBuild? = null,
    val httpsEnforced: Boolean? = null,
    val customDomain: String? = null,
)

/** One thing to change about a repository's website. */
sealed interface PagesChange {
    /** Publishes it from [build], or builds it from there from now on. */
    data class Publish(val build: PagesBuild) : PagesChange

    data class Https(val enforced: Boolean) : PagesChange

    /** Serves it at [domain]; at its forge's own address again when empty. */
    data class Domain(val domain: String) : PagesChange

    data object Unpublish : PagesChange
}
