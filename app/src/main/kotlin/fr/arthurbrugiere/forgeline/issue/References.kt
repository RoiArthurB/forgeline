package fr.arthurbrugiere.forgeline.issue

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.ReferenceLinks
import fr.arthurbrugiere.forgeline.core.model.ForgeType
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.RepoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the conversations a text of this repository names by number live: `#12`, and `!12` where merge requests are numbered apart. */
fun RepoId.referenceLinks(): ReferenceLinks = when (forge.type) {
    ForgeType.GITLAB -> ReferenceLinks(forge.webUrl, fullName, issuePath = "/-/issues/", mergeRequestPath = "/-/merge_requests/")
    else -> ReferenceLinks(forge.webUrl, fullName, issuePath = "/issues/")
}

/** The marks that name a conversation by number on this repository's forge. */
val RepoId.referenceMarks: String get() = if (forge.type.numbersMergeRequestsApart) "#!" else "#"

/**
 * A reference being written: its [mark] stands at [start] in the text, and [words] is what was typed after it, up to
 * the cursor. A number, or the start of a title.
 */
data class TypedReference(val start: Int, val mark: Char, val words: String)

/** How much of a title is followed while it is typed after a mark: past that, it is no longer a reference. */
private const val MAX_TYPED = 40

/**
 * The reference the cursor stands at the end of, if any: one of [marks] at the start of a word, then letters, digits,
 * dashes and single spaces up to the cursor. `#` alone counts: it asks for what is recent.
 */
fun typedReference(text: String, cursor: Int, marks: String): TypedReference? {
    if (cursor !in 0..text.length) return null
    var at = cursor - 1
    while (at >= 0 && cursor - at <= MAX_TYPED) {
        val char = text[at]
        if (char in marks) {
            // A mark glued to a word is part of it (`issue#12`, an address's anchor); a heading's `# ` is not a reference.
            val startsWord = at == 0 || text[at - 1].isWhitespace() || text[at - 1] in "(["
            val words = text.substring(at + 1, cursor)
            return TypedReference(at, char, words).takeIf { startsWord && !words.startsWith(' ') && "  " !in words }
        }
        if (!(char.isLetterOrDigit() || char == '-' || char == '_' || char == ' ')) return null
        at--
    }
    return null
}

/** The text with [typed] replaced by the reference to [number], and the cursor after it, ready to go on writing. */
fun TextFieldValue.withReference(typed: TypedReference, number: Int): TextFieldValue {
    val end = selection.end.coerceIn(typed.start, text.length)
    val written = "${typed.mark}$number"
    val rest = text.substring(end)
    // A space follows, to go on writing: unless one is there already, or a comma or a full stop comes next.
    val space = if (rest.isEmpty() || rest[0].isLetterOrDigit()) " " else ""
    val after = typed.start + written.length + if (space.isNotEmpty() || rest.startsWith(' ')) 1 else 0
    return TextFieldValue(text.substring(0, typed.start) + written + space + rest, TextRange(after))
}

/**
 * Finds the conversations a reference being typed may mean: the repository's recent open ones for a mark alone or a
 * number, and those the forge finds for words.
 */
class ReferenceSuggestions(private val list: suspend (RepoId, pullRequests: Boolean, IssueQuery) -> ForgeResult<List<IssueSummary>>) {
    @Inject
    constructor(repos: RepoRepository) : this({ repo, pullRequests, query -> if (pullRequests) repos.pullRequests(repo, query) else repos.issues(repo, query) })

    /** Suggests nothing: for tests of what has nothing to do with references. */
    constructor() : this({ _, _, _ -> ForgeResult.Success(emptyList()) })

    private suspend fun both(repo: RepoId, mark: Char, query: IssueQuery): List<IssueSummary> = coroutineScope {
        // Where merge requests are numbered apart, each mark names its own kind; elsewhere `#` names either.
        val apart = repo.forge.type.numbersMergeRequestsApart
        val issues = if (!apart || mark == '#') async { list(repo, false, query) } else null
        val pulls = if (!apart || mark == '!') async { list(repo, true, query) } else null
        listOfNotNull(issues?.await(), pulls?.await()).flatMap { (it as? ForgeResult.Success)?.value.orEmpty() }
    }

    /** At most [LIMIT] conversations, the most recent first. What can't be loaded suggests nothing rather than failing. */
    suspend fun suggest(repo: RepoId, typed: TypedReference): List<IssueSummary> {
        val words = typed.words.trim()
        val found = when {
            words.isEmpty() -> both(repo, typed.mark, IssueQuery())
            words.all(Char::isDigit) -> both(repo, typed.mark, IssueQuery()).filter { it.number.toString().startsWith(words) }
            else -> both(repo, typed.mark, IssueQuery(text = words))
        }
        return found.distinctBy { it.isPullRequest to it.number }.sortedByDescending { it.number }.take(LIMIT)
    }

    companion object {
        const val LIMIT = 5
    }
}

/**
 * What a writing screen keeps of the reference being typed: the conversations it may mean, asked for once the writing
 * pauses, and dropped when the answer comes for something no longer typed.
 */
class ReferenceTyping(private val scope: CoroutineScope, private val suggestions: ReferenceSuggestions, private val repo: RepoId) {
    private val _suggested = MutableStateFlow<List<IssueSummary>>(emptyList())
    val suggested: StateFlow<List<IssueSummary>> = _suggested.asStateFlow()

    private var asking: Job? = null
    private var typed: TypedReference? = null

    /** Called with the reference at the cursor each time the text or the cursor moves; null when there is none. */
    fun typed(reference: TypedReference?) {
        if (reference == typed) return
        typed = reference
        asking?.cancel()
        if (reference == null) {
            _suggested.value = emptyList()
            return
        }
        asking = scope.launch {
            delay(PAUSE_MILLIS)
            _suggested.value = suggestions.suggest(repo, reference)
        }
    }

    companion object {
        /** How long the writing must pause before the forge is asked: a search per letter would be many. */
        const val PAUSE_MILLIS = 250L
    }
}
