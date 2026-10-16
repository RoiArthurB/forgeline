package fr.arthurbrugiere.forgeline.core.model

import java.time.Instant

/** Kinds of Feed activity people can switch on or off; the defaults keep the Feed social, not noisy. */
enum class FeedKind(val shownByDefault: Boolean) {
    STARS(true),
    FORKS(true),
    NEW_REPOS(true),
    RELEASES(true),

    /** Release candidates, betas and other pre-releases: shown like GitHub's own feed does, and easy to switch off. */
    PRERELEASES(true),

    /** A repository's announcements (GitHub's Announcements discussions); Forgejo has none. */
    ANNOUNCEMENTS(true),
    ISSUES_OPENED(true),
    ISSUES_CLOSED(true),
    PRS_OPENED(true),
    PRS_CLOSED(true),
    COMMENTS(false),
    REVIEWS(false),
    PUSHES(false),
    BRANCHES(false),
    MEMBERS(false),
    ;

    companion object {
        val defaults: Set<FeedKind> = entries.filter { it.shownByDefault }.toSet()
    }
}

enum class IssueAction { OPENED, CLOSED, REOPENED }

enum class PullRequestAction { OPENED, CLOSED, MERGED, REOPENED }

sealed interface FeedAction {
    val kind: FeedKind

    data object Starred : FeedAction {
        override val kind = FeedKind.STARS
    }

    data class Forked(val fork: RepoId) : FeedAction {
        override val kind = FeedKind.FORKS
    }

    data class CreatedRepo(val description: String?) : FeedAction {
        override val kind = FeedKind.NEW_REPOS
    }

    data object MadePublic : FeedAction {
        override val kind = FeedKind.NEW_REPOS
    }

    data class Released(val tag: String, val name: String?, val prerelease: Boolean) : FeedAction {
        override val kind = if (prerelease) FeedKind.PRERELEASES else FeedKind.RELEASES
    }

    /** A post in a repository's Announcements, by its discussion [number]. */
    data class Announced(val number: Int, val title: String) : FeedAction {
        override val kind = FeedKind.ANNOUNCEMENTS
    }

    data class Issue(val action: IssueAction, val number: Int, val title: String) : FeedAction {
        override val kind = if (action == IssueAction.CLOSED) FeedKind.ISSUES_CLOSED else FeedKind.ISSUES_OPENED
    }

    /** GitHub's events carry no pull request title, only its number ([title] null); Forgejo's carry it. */
    data class PullRequest(val action: PullRequestAction, val number: Int, val title: String? = null) : FeedAction {
        override val kind = when (action) {
            PullRequestAction.CLOSED, PullRequestAction.MERGED -> FeedKind.PRS_CLOSED
            else -> FeedKind.PRS_OPENED
        }
    }

    data class Commented(val number: Int, val title: String?, val isPullRequest: Boolean) : FeedAction {
        override val kind = FeedKind.COMMENTS
    }

    data class Reviewed(val number: Int, val state: ReviewState) : FeedAction {
        override val kind = FeedKind.REVIEWS
    }

    data class Pushed(val branch: String) : FeedAction {
        override val kind = FeedKind.PUSHES
    }

    data class Branch(val name: String, val isTag: Boolean, val deleted: Boolean) : FeedAction {
        override val kind = FeedKind.BRANCHES
    }

    data class AddedMember(val login: String) : FeedAction {
        override val kind = FeedKind.MEMBERS
    }
}

data class FeedEvent(
    val id: String,
    val actor: ForgeUser,
    val repo: RepoId,
    val action: FeedAction,
    val createdAt: Instant,
)

/** What a Feed row shows about a repository its event names; events don't carry it, so it's fetched and cached. */
data class RepoPreview(
    val description: String?,
    val language: String?,
    val stars: Int,
    /** When something was last pushed to it; null where the forge didn't say. */
    val pushedAt: Instant? = null,
)

/** A release a Feed row is about, by its repository and tag. */
data class ReleaseRef(val repo: RepoId, val tag: String)

/** What a release row shows beyond its tag, fetched once it scrolls into view. */
data class ReleasePreview(
    /** The notes' opening, as plain text: see [releaseSummary]. */
    val summary: String?,
    /** The newest stable release before this one, which says how big a step this is: see [releaseJump]. */
    val previousTag: String?,
    /** The project's own social image, else the first picture in the notes; null when there is neither. */
    val imageUrl: String?,
    /** The owner's avatar: the project's logo, as most projects set it. */
    val logoUrl: String?,
    val reactions: Map<Reaction, Int> = emptyMap(),
)

/** What an issue or pull request row shows beyond its number, fetched once it scrolls into view. */
data class ConversationPreview(
    val title: String?,
    val labels: List<Label> = emptyList(),
    val comments: Int? = null,
    /** Lines added and removed, and files changed: pull requests only, where the forge counts them. */
    val additions: Int? = null,
    val deletions: Int? = null,
    val changedFiles: Int? = null,
)

/** Previews known so far for the Feed: repositories, releases, and issues and pull requests. */
data class FeedPreviews(
    val repos: Map<RepoId, RepoPreview> = emptyMap(),
    val releases: Map<ReleaseRef, ReleasePreview> = emptyMap(),
    val conversations: Map<IssueRef, ConversationPreview> = emptyMap(),
)

/** How big a step a release is from the one before it, read from their version numbers. */
enum class ReleaseJump { MAJOR, MINOR, PATCH }

/**
 * The step from [previous] to [tag]: the first of their numbers that differs says it (the first a major release,
 * the second a minor one, any later a patch). Null when either has no number to read, or nothing differs.
 * Calendar versions (2026.10.1) read the same way, a new month as minor.
 */
fun releaseJump(tag: String, previous: String?): ReleaseJump? {
    val now = versionNumbers(tag) ?: return null
    val before = previous?.let(::versionNumbers) ?: return null
    val index = (0 until maxOf(now.size, before.size)).firstOrNull { now.getOrElse(it) { 0 } != before.getOrElse(it) { 0 } } ?: return null
    return when (index) {
        0 -> ReleaseJump.MAJOR
        1 -> ReleaseJump.MINOR
        else -> ReleaseJump.PATCH
    }
}

private val versionCore = Regex("""(\d+(?:\.\d+)*)""")

/** The dotted numbers a tag starts its version with ("v1.25.0-pre" gives 1, 25, 0), up to three. */
private fun versionNumbers(tag: String): List<Long>? =
    versionCore.find(tag)?.value?.split('.')?.take(3)?.map { it.toLongOrNull() ?: return null }

private val markdownImage = Regex("""!\[[^\]]*]\((https://[^)\s]+)[^)]*\)""")
private val htmlImage = Regex("""<img[^>]*\ssrc=["'](https://[^"']+)["']""", RegexOption.IGNORE_CASE)

/** The first picture in [markdown] served over https, as Markdown or as an HTML `<img>`; null when there is none. */
fun firstImage(markdown: String?): String? {
    if (markdown == null) return null
    val fromMarkdown = markdownImage.find(markdown)
    val fromHtml = htmlImage.find(markdown)
    return listOfNotNull(fromMarkdown, fromHtml).minByOrNull { it.range.first }?.groupValues?.get(1)
}

/**
 * What a release's notes say first, as one plain sentence or two: the opening paragraph when the notes start with
 * prose (most projects write one), otherwise their first few changes joined by " · ". Headings, pictures, quotes,
 * code, links' addresses, credits ("by @someone in …") and pull request numbers are left out. Null when nothing
 * readable is left.
 */
fun releaseSummary(markdown: String?, maxLength: Int = 280): String? {
    if (markdown.isNullOrBlank()) return null
    var fenced = false
    val lines = markdown.replace("\r", "").lines().filter { line ->
        val fence = line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")
        if (fence) fenced = !fenced
        !fence && !fenced
    }
    val paragraphs = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()
    fun close() {
        if (current.isNotEmpty()) paragraphs += current
        current = mutableListOf()
    }
    lines.forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) close() else current += line
    }
    close()
    val prose = paragraphs.firstOrNull { paragraph -> paragraph.none { it.isStructure() } && paragraph.any { it.isProse() } }
        ?.joinToString(" ") { it.plain() }?.takeIf { it.isNotBlank() }
    val text = prose ?: lines.map { it.trim() }.filter { it.isBullet() }.map { it.plain() }.filter { it.isNotBlank() }.take(3)
        .takeIf { it.isNotEmpty() }?.joinToString(" · ")
    return text?.let { if (it.length > maxLength) it.take(maxLength).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…" else it }
}

private fun String.isBullet() = startsWith("- ") || startsWith("* ") || startsWith("+ ")

/** A line that can't open a summary: a heading, a quote, a table, a rule, a list item or a lone picture. */
private fun String.isStructure() =
    startsWith("#") || startsWith(">") || startsWith("|") || startsWith("---") || startsWith("***") || startsWith("<") ||
        isBullet() || Regex("""^\d+\. """).containsMatchIn(this) || markdownImage.matches(this)

/** A line that reads as a sentence once its links and markup are gone, not a lone link or a changelog pointer. */
private fun String.isProse(): Boolean {
    val text = plain()
    return text.count { it.isLetter() } >= 20 && !text.startsWith("Full Changelog", ignoreCase = true)
}

/** [this] line of Markdown as plain text. */
private fun String.plain(): String = removePrefix("- ").removePrefix("* ").removePrefix("+ ")
    .replace(markdownImage, "")
    .replace(Regex("""\[!\[[^\]]*]\([^)]*\)]\([^)]*\)"""), "")
    // Credits and pointers in brackets: "([@someone] - [#12])", "([wled docs])".
    .replace(Regex("""\((?:\s*\[[^\]]*]\s*-?\s*)+\)"""), "")
    .replace(Regex("""\s*\(dependency\)"""), "")
    // A link keeps its words; a bare reference to a pull request ([#12](…), (#12)) goes.
    .replace(Regex("""\(?\[#\d+]\([^)]*\)\)?"""), "")
    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""\[([^\]]*)]\[[^\]]*]"""), "$1")
    .replace(Regex("""\s+by @[\w.-]+(?:\[bot])?\s+in\s+\S+"""), "")
    .replace(Regex("""\s*\(#\d+\)"""), "")
    .replace(Regex("""https?://[^\s)]+"""), "")
    .replace(Regex("""<[^>]+>"""), "")
    .replace(Regex("""\(\s*\)"""), "")
    .replace(Regex("""(\*\*|__|\*|_|`)(\S(?:.*?\S)?)\1"""), "$2")
    .replace(Regex("""\s{2,}"""), " ")
    .trim()
    .trimEnd(';', ',')
