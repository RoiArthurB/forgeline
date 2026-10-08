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
 * number, and those the forge finds for words. The recent ones are asked for once and kept, so a number narrows them
 * without waiting, and a title shows those it matches while the forge is searched.
 */
class ReferenceSuggestions(private val list: suspend (RepoId, pullRequests: Boolean, IssueQuery) -> ForgeResult<List<IssueSummary>>) {
    @Inject
    constructor(repos: RepoRepository) : this({ repo, pullRequests, query -> if (pullRequests) repos.pullRequests(repo, query) else repos.issues(repo, query) })

    /** Suggests nothing: for tests of what has nothing to do with references. */
    constructor() : this({ _, _, _ -> ForgeResult.Success(emptyList()) })

    /** The recent open conversations each mark names, by repository, once the forge has listed them. */
    private val recent = java.util.concurrent.ConcurrentHashMap<Pair<RepoId, Char>, List<IssueSummary>>()

    /** What the forge listed, or null when nothing could be asked: an empty answer is an answer. */
    private suspend fun both(repo: RepoId, mark: Char, query: IssueQuery): List<IssueSummary>? = coroutineScope {
        // Where merge requests are numbered apart, each mark names its own kind; elsewhere `#` names either.
        val apart = repo.forge.type.numbersMergeRequestsApart
        val issues = if (!apart || mark == '#') async { list(repo, false, query) } else null
        val pulls = if (!apart || mark == '!') async { list(repo, true, query) } else null
        val answers = listOfNotNull(issues?.await(), pulls?.await()).filterIsInstance<ForgeResult.Success<List<IssueSummary>>>()
        if (answers.isEmpty()) null else answers.flatMap { it.value }
    }

    private fun List<IssueSummary>.best() = distinctBy { it.isPullRequest to it.number }.sortedByDescending { it.number }.take(LIMIT)

    private fun List<IssueSummary>.matching(words: String) = when {
        words.isEmpty() -> this
        words.all(Char::isDigit) -> filter { it.number.toString().startsWith(words) }
        else -> filter { it.title.contains(words, ignoreCase = true) }
    }

    /** Whether [typed] takes a word from the forge: its recent conversations weren't listed yet, or a title is looked for. */
    fun asksForge(repo: RepoId, typed: TypedReference): Boolean {
        val words = typed.words.trim()
        return recent[repo to typed.mark] == null || !(words.isEmpty() || words.all(Char::isDigit))
    }

    /** What can be suggested for [typed] without asking: those of the recent conversations it matches. Empty before they were listed. */
    fun known(repo: RepoId, typed: TypedReference): List<IssueSummary> = recent[repo to typed.mark].orEmpty().matching(typed.words.trim()).best()

    /** At most [LIMIT] conversations, the most recent first. What can't be loaded suggests what is known rather than failing. */
    suspend fun suggest(repo: RepoId, typed: TypedReference): List<IssueSummary> {
        val words = typed.words.trim()
        val listed = recent[repo to typed.mark] ?: both(repo, typed.mark, IssueQuery())?.also { recent[repo to typed.mark] = it }.orEmpty()
        if (words.isEmpty() || words.all(Char::isDigit)) return listed.matching(words).best()
        // A title: what the forge finds, and the recent ones that match while it is one letter behind.
        return (both(repo, typed.mark, IssueQuery(text = words)).orEmpty() + listed.matching(words)).best()
    }

    companion object {
        const val LIMIT = 5
    }
}

/** What is offered for the reference being typed, and whether more is on its way. */
data class ReferenceOffer(val suggestions: List<IssueSummary> = emptyList(), val isLoading: Boolean = false)

/**
 * What a writing screen keeps of the reference being typed. What is known shows at once, with a sign that the forge
 * is being asked when it is; a title is looked for once the writing pauses, and an answer for something no longer
 * typed is dropped.
 */
class ReferenceTyping(private val scope: CoroutineScope, private val suggestions: ReferenceSuggestions, private val repo: RepoId) {
    private val _offer = MutableStateFlow(ReferenceOffer())
    val offer: StateFlow<ReferenceOffer> = _offer.asStateFlow()

    private var asking: Job? = null
    private var typed: TypedReference? = null

    /** Called with the reference at the cursor each time the text or the cursor moves; null when there is none. */
    fun typed(reference: TypedReference?) {
        if (reference == typed) return
        typed = reference
        asking?.cancel()
        if (reference == null) {
            _offer.value = ReferenceOffer()
            return
        }
        val asks = suggestions.asksForge(repo, reference)
        // Regression: nothing showed until the forge had answered, which is seconds from a far one.
        _offer.value = ReferenceOffer(suggestions.known(repo, reference), isLoading = asks)
        if (!asks) return
        asking = scope.launch {
            // A title is searched for, and a search per letter would be many: the recent ones are simply listed, once.
            if (reference.words.isNotBlank()) delay(PAUSE_MILLIS)
            _offer.value = ReferenceOffer(suggestions.suggest(repo, reference))
        }
    }

    companion object {
        /** How long the writing must pause before the forge is searched. */
        const val PAUSE_MILLIS = 250L
    }
}
