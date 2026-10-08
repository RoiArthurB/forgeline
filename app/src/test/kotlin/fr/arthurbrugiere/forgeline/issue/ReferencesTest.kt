package fr.arthurbrugiere.forgeline.issue

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.ReferenceLinks
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueQuery
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Issue #11: a conversation is named by its number, and found by it or by its title while it is typed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferencesTest {
    private val repo = RepoId("octo", "repo")
    private val gitlab = RepoId("group", "tool", ForgeInstance.GitLab)

    private fun typed(text: String, marks: String = "#") = typedReference(text, text.length, marks)

    @Test
    fun a_mark_at_the_start_of_a_word_starts_a_reference() {
        assertThat(typed("#")).isEqualTo(TypedReference(0, '#', ""))
        assertThat(typed("Same as #12")).isEqualTo(TypedReference(8, '#', "12"))
        assertThat(typed("See (#cra")).isEqualTo(TypedReference(5, '#', "cra"))
        assertThat(typed("line\n#heart beat")).isEqualTo(TypedReference(5, '#', "heart beat"))
    }

    @Test
    fun the_reference_is_the_one_the_cursor_ends_not_one_further_on() {
        val text = "Like #12 and #34"

        assertThat(typedReference(text, 8, "#")).isEqualTo(TypedReference(5, '#', "12"))
        assertThat(typedReference(text, 7, "#")).isEqualTo(TypedReference(5, '#', "1"))
        assertThat(typedReference(text, text.length, "#")).isEqualTo(TypedReference(13, '#', "34"))
    }

    @Test
    fun what_is_not_a_reference_starts_none() {
        // Glued to a word, a heading, two spaces running, a sentence's end, no mark at all, a cursor out of the text.
        for (text in listOf("issue#12", "https://example.com/a#b", "# Title", "#fix  it", "#12. Then", "no mark here", "")) {
            assertThat(typed(text)).isNull()
        }
        assertThat(typedReference("#12", 9, "#")).isNull()
        assertThat(typedReference("#12", -1, "#")).isNull()
    }

    @Test
    fun a_title_typed_at_length_is_no_longer_followed() {
        assertThat(typed("#" + "a".repeat(39))).isNotNull()
        assertThat(typed("#" + "a".repeat(41))).isNull()
    }

    @Test
    fun an_exclamation_mark_starts_one_only_where_merge_requests_have_their_own_numbers() {
        assertThat(typed("See !4", repo.referenceMarks)).isNull()
        assertThat(typed("See !4", gitlab.referenceMarks)).isEqualTo(TypedReference(4, '!', "4"))
        assertThat(repo.referenceMarks).isEqualTo("#")
    }

    @Test
    fun picking_a_conversation_writes_its_number_and_leaves_the_cursor_after_it() {
        val field = TextFieldValue("Same as #hea, I think", TextRange(12))

        val picked = field.withReference(typedReference(field.text, 12, "#")!!, 14127)

        // No space is put before the comma that follows.
        assertThat(picked.text).isEqualTo("Same as #14127, I think")
        assertThat(picked.selection).isEqualTo(TextRange(14))
    }

    @Test
    fun picking_one_does_not_double_a_space_already_there_and_keeps_the_mark_typed() {
        val field = TextFieldValue("See !4 please", TextRange(6))

        val picked = field.withReference(typedReference(field.text, 6, "#!")!!, 42)

        assertThat(picked.text).isEqualTo("See !42 please")
        assertThat(picked.selection).isEqualTo(TextRange(8))
    }

    @Test
    fun picking_one_at_the_end_of_the_text_leaves_a_space_to_go_on_writing() {
        val field = TextFieldValue("Same as #", TextRange(9))

        val picked = field.withReference(typedReference(field.text, 9, "#")!!, 7)

        assertThat(picked.text).isEqualTo("Same as #7 ")
        assertThat(picked.selection).isEqualTo(TextRange(11))
    }

    @Test
    fun each_forge_addresses_its_conversations_its_own_way() {
        assertThat(repo.referenceLinks()).isEqualTo(ReferenceLinks("https://github.com", "octo/repo", "/issues/"))
        assertThat(RepoId("forgejo", "forgejo", ForgeInstance.Codeberg).referenceLinks()).isEqualTo(ReferenceLinks("https://codeberg.org", "forgejo/forgejo", "/issues/"))
        assertThat(gitlab.referenceLinks()).isEqualTo(ReferenceLinks("https://gitlab.com", "group/tool", "/-/issues/", "/-/merge_requests/"))
    }

    // --- what is suggested

    private val asked = mutableListOf<String>()
    private var issues: ForgeResult<List<IssueSummary>> = ForgeResult.Success(listOf(issueSummary(120, "Crash on start"), issueSummary(12, "Slow list"), issueSummary(7, "Typo")))
    private var pulls: ForgeResult<List<IssueSummary>> = ForgeResult.Success(listOf(issueSummary(121, "Fix the crash", isPullRequest = true), issueSummary(3, "Docs", isPullRequest = true)))
    private val suggestions = ReferenceSuggestions { _, pullRequests, query: IssueQuery ->
        asked += (if (pullRequests) "pulls" else "issues") + (if (query.text.isEmpty()) "" else ":${query.text}")
        if (pullRequests) pulls else issues
    }

    @Test
    fun a_mark_alone_suggests_the_most_recent_issues_and_pull_requests() = runTest {
        val suggested = suggestions.suggest(repo, TypedReference(0, '#', ""))

        assertThat(suggested.map { it.number }).containsExactly(121, 120, 12, 7, 3).inOrder()
        assertThat(asked).containsExactly("issues", "pulls")
    }

    @Test
    fun digits_narrow_to_the_numbers_that_start_with_them() = runTest {
        assertThat(suggestions.suggest(repo, TypedReference(0, '#', "12")).map { it.number }).containsExactly(121, 120, 12).inOrder()
        assertThat(suggestions.suggest(repo, TypedReference(0, '#', "9"))).isEmpty()
    }

    @Test
    fun words_are_looked_for_on_the_forge() = runTest {
        suggestions.suggest(repo, TypedReference(0, '#', "crash "))

        assertThat(asked).containsExactly("issues:crash", "pulls:crash")
    }

    @Test
    fun no_more_than_five_are_suggested() = runTest {
        issues = ForgeResult.Success((1..20).map { issueSummary(it, "Issue $it") })

        assertThat(suggestions.suggest(repo, TypedReference(0, '#', ""))).hasSize(ReferenceSuggestions.LIMIT)
    }

    @Test
    fun where_merge_requests_are_numbered_apart_each_mark_suggests_its_own_kind() = runTest {
        assertThat(suggestions.suggest(gitlab, TypedReference(0, '#', "")).none { it.isPullRequest }).isTrue()
        assertThat(suggestions.suggest(gitlab, TypedReference(0, '!', "")).all { it.isPullRequest }).isTrue()
        assertThat(asked).containsExactly("issues", "pulls").inOrder()
    }

    @Test
    fun what_can_t_be_loaded_suggests_what_could_rather_than_failing() = runTest {
        pulls = ForgeResult.Failure(ForgeError.Network)

        assertThat(suggestions.suggest(repo, TypedReference(0, '#', "")).map { it.number }).containsExactly(120, 12, 7).inOrder()
    }

    @Test
    fun the_forge_is_asked_once_the_writing_pauses_and_only_for_what_is_still_typed() = runTest {
        val typing = ReferenceTyping(backgroundScope, suggestions, repo)

        typing.typed(TypedReference(0, '#', "c"))
        advanceTimeBy(100)
        typing.typed(TypedReference(0, '#', "cr"))
        advanceTimeBy(ReferenceTyping.PAUSE_MILLIS + 1)

        assertThat(asked).containsExactly("issues:cr", "pulls:cr")
        assertThat(typing.suggested.value).isNotEmpty()
    }

    @Test
    fun the_same_reference_told_again_asks_nothing_more_and_none_clears_what_was_suggested() = runTest {
        val typing = ReferenceTyping(backgroundScope, suggestions, repo)
        typing.typed(TypedReference(0, '#', ""))
        advanceTimeBy(ReferenceTyping.PAUSE_MILLIS + 1)

        // The cursor moved without the reference changing.
        typing.typed(TypedReference(0, '#', ""))
        advanceTimeBy(ReferenceTyping.PAUSE_MILLIS + 1)
        assertThat(asked).hasSize(2)

        typing.typed(null)
        assertThat(typing.suggested.value).isEmpty()
    }

    @Test
    fun a_reference_given_up_before_the_pause_asks_nothing() = runTest {
        val typing = ReferenceTyping(backgroundScope, suggestions, repo)

        typing.typed(TypedReference(0, '#', "c"))
        typing.typed(null)
        advanceUntilIdle()

        assertThat(asked).isEmpty()
        assertThat(typing.suggested.value).isEmpty()
    }
}
