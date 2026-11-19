package fr.arthurbrugiere.forgeline.forge.tangled

import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Reaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Tangled's issues and pull requests are one kind of record since 2026-11, the ticket (`org.tangled.track.ticket`): a
// pull request is a ticket with a target branch, and its change is a patch record beside it. Tickets live on the
// repository's knot, at `at://<repository DID>/org.tangled.track.ticket/<number>`: the record key is the number the
// web app shows (`tangled.org/<owner>/<repo>/issues/4786`), one numbering for issues and pull requests both, as on
// GitHub and Forgejo. The index embeds a ticket in its lists as `value`, which its lexicons type `unknown` (it served
// former `sh.tangled.repo.issue` records there too): read leniently here, only what Forgeline shows.

internal const val TICKET = "org.tangled.track.ticket"
internal const val COMMENT = "org.tangled.feed.comment"
internal const val REACTION = "org.tangled.feed.reaction"
internal const val LABEL_DEFINITION = "org.tangled.label.definition"
internal const val REVIEW_ROUND = "org.tangled.review.unspecced.round"

/** The ticket numbered [number] of the repository [repoDid]. */
internal fun ticketUri(repoDid: String, number: Int): String = "at://$repoDid/$TICKET/$number"

/** A ticket record: an issue, or a pull request when it has a [targetBranch]. */
@Serializable
internal data class TicketRecordJson(
    val title: TextJson? = null,
    val body: TextJson? = null,
    val state: String? = null,
    val tangledLabels: List<LabelTokenJson> = emptyList(),
    val assignees: List<String> = emptyList(),
    val targetBranch: String? = null,
    val createdAt: String? = null,
)

/** Markdown as records keep it, `org.tangled.markup.markdown` (or its `#inline` kind, for a title). */
@Serializable
internal data class TextJson(val text: String = "")

/** A label put on a ticket, by the record key of the repository's label definition. */
@Serializable
internal data class LabelTokenJson(val key: String = "")

/** A repository's label: `org.tangled.label.definition`, kept under a key made from its name. */
@Serializable
internal data class LabelDefinitionJson(val name: String = "", val color: String? = null)

/** An `org.tangled.feed.reaction`: one emoji someone ([editor]) gave a ticket or a comment. */
@Serializable
internal data class ReactionRecordJson(
    val reaction: String = "",
    @SerialName("x-tngl-editor") val editor: String? = null,
)

/**
 * An `org.tangled.review.patch`: one version of a pull request's change, from [base] to [head], [source] naming the
 * repository and branch [head] came from. A pull request's latest patch is what it would merge.
 */
@Serializable
internal data class PatchRecordJson(
    val base: OidJson? = null,
    val head: OidJson? = null,
    val source: PatchSourceJson? = null,
    val createdAt: String? = null,
)

@Serializable
internal data class OidJson(val oid: String = "")

@Serializable
internal data class PatchSourceJson(val repo: String? = null, val branch: String? = null)

/** What `org.tangled.temp.review.listPatches` answers, which no lexicon describes yet: the patches of one ticket. */
@Serializable
internal data class PatchListJson(val items: List<PatchItemJson> = emptyList())

@Serializable
internal data class PatchItemJson(val uri: String = "", val value: PatchRecordJson = PatchRecordJson())

/**
 * Where a ticket stands. An issue is `open`, or `completed` once done and `closed` when it won't be; a pull request is
 * `open`, `closed`, or merged, which the index calls `completed` since 2026-11-18 and its lexicon still `merged`.
 */
internal fun ticketState(state: String?, isPullRequest: Boolean): IssueState = when (state) {
    "open", null -> IssueState.OPEN
    "merged" -> IssueState.MERGED
    "completed" -> if (isPullRequest) IssueState.MERGED else IssueState.CLOSED
    else -> IssueState.CLOSED
}

/** Why an issue is closed, as the forges that keep a reason say it: `completed` is done, `closed` won't be. */
internal fun stateReason(state: String?): String? = when (state) {
    "completed" -> "completed"
    "closed" -> "not_planned"
    else -> null
}

/** The emoji `org.tangled.feed.reaction` takes, for each of Forgeline's reactions. */
internal val REACTION_EMOJI: Map<Reaction, String> = mapOf(
    Reaction.THUMBS_UP to "👍",
    Reaction.THUMBS_DOWN to "👎",
    Reaction.LAUGH to "😆",
    Reaction.HOORAY to "🎉",
    Reaction.CONFUSED to "🫤",
    Reaction.HEART to "❤️",
    Reaction.ROCKET to "🚀",
    Reaction.EYES to "👀",
)

internal fun reactionOf(emoji: String): Reaction? = REACTION_EMOJI.entries.firstOrNull { it.value == emoji || it.value.removeSuffix("️") == emoji }?.key

/**
 * Record keys made of time: a TID is a 64-bit number (microseconds since 1970, and a clock id) written in 13
 * characters of base32, sortable. Comments are keyed by one, which Forgeline keeps as a comment's [Long] id.
 */
internal object Tid {
    private const val ALPHABET = "234567abcdefghijklmnopqrstuvwxyz"

    fun decode(tid: String): Long? {
        if (tid.length != 13) return null
        var value = 0L
        for (char in tid) {
            val digit = ALPHABET.indexOf(char)
            if (digit < 0) return null
            value = (value shl 5) or digit.toLong()
        }
        return value
    }

    fun encode(value: Long): String {
        val chars = CharArray(13)
        var rest = value
        for (index in 12 downTo 0) {
            chars[index] = ALPHABET[(rest and 31).toInt()]
            rest = rest ushr 5
        }
        return String(chars)
    }

    /** A new key, for now: microseconds since 1970, with a clock id of its own. */
    fun now(micros: Long, clockId: Int = 0): String = encode((micros shl 10) or (clockId.toLong() and 1023))
}

/** A label definition's record key, as Tangled's web app makes it from the label's name: the knot refuses a TID. */
internal fun labelKey(name: String): String = name.trim().lowercase()
    .replace(Regex("[^a-z0-9._~-]+"), "-")
    .trim('-')
    .take(64)
