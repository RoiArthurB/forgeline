package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.KeptSuggestion
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.ThreadComment
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ReviewThreadsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val at = Instant.parse("2026-10-20T10:00:00Z")

    private val open = ReviewThread(
        "T1", "src/Upload.kt", 3, onOldSide = false,
        hunk = "@@ -1,2 +1,3 @@\n fun upload() {\n-    send(file)\n+    retry { send(file) }",
        comments = listOf(
            ThreadComment(1, ForgeUser("hubot", null, null), "Why not three times?", at),
            ThreadComment(2, ForgeUser("octocat", null, null), "Flaky links.", at),
        ),
        resolved = false, canResolve = true,
    )
    private val resolved = ReviewThread(
        "T2", "docs/retry.md", 1, onOldSide = false, hunk = null,
        comments = listOf(ThreadComment(3, ForgeUser("hubot", null, null), "Typo here", at)), resolved = true, canResolve = true,
    )

    private val actions = PullActions(
        onReply = { thread, body -> events += "reply:${thread.id}:$body" },
        onSetResolved = { thread, resolved -> events += "resolved:${thread.id}:$resolved" },
        onApplySuggestion = { thread, suggestion -> events += "suggestion:${thread.id}:${suggestion.joinToString("|")}" },
        onEditThreadComment = { thread, comment, body -> events += "edit:${thread.id}:${comment.id}:$body" },
        onDeleteThreadComment = { thread, comment -> events += "delete:${thread.id}:${comment.id}" },
        onToggleThreadReaction = { thread, comment, reaction -> events += "react:${thread.id}:${comment.id}:$reaction" },
    )

    private fun setContent(
        threads: List<ReviewThread>,
        state: PullRequestUiState = PullRequestUiState(resolvesThreads = true),
        canReply: Boolean = true,
        canCommit: Boolean = false,
    ) = composeRule.setContent {
        ForgelineTheme {
            ReviewThreads(
                threads, state, actions, canReply, ReadmeContext("https://raw.example/", "https://blob.example/"), at.toEpochMilli(), onOpenUser = {}, onLinkClick = {},
                canCommit = canCommit, branch = "fix/retry",
            )
        }
    }

    private val suggesting = open.copy(line = 2, comments = listOf(ThreadComment(1, ForgeUser("hubot", null, null), "Three times:\n```suggestion\n    retry(3) { send(file) }\n```", at)))

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun an_open_thread_says_where_it_is_shows_the_code_and_what_was_said() {
        setContent(listOf(open, resolved))

        composeRule.onNodeWithText("1 open thread").assertIsDisplayed()
        composeRule.onNodeWithText("src/Upload.kt:3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, added:     retry { send(file) }").assertIsDisplayed()
        waitFor("Why not three times?")
        composeRule.onNodeWithText("Flaky links.").assertIsDisplayed()
        // The resolved one waits behind a row.
        composeRule.onNodeWithText("Typo here").assertDoesNotExist()
        composeRule.onNodeWithText("Show 1 resolved thread").performClick()
        waitFor("Typo here")
        composeRule.onNodeWithText("docs/retry.md:1 · Resolved").assertIsDisplayed()
        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun a_suggestion_shows_as_the_change_it_would_make() {
        setContent(listOf(suggesting))

        waitFor("Three times:")
        composeRule.onNodeWithText("Suggested change").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, removed:     retry { send(file) }").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, added:     retry(3) { send(file) }").assertIsDisplayed()
        // The fence itself isn't shown as code.
        composeRule.onAllNodes(hasText("suggestion", substring = true)).assertCountEquals(0)
    }

    @Test
    fun a_suggestion_is_committed_to_the_branch_once_the_reader_says_yes() {
        setContent(listOf(suggesting), PullRequestUiState(appliesSuggestions = true), canCommit = true)

        composeRule.onNodeWithText("Commit suggestion").performClick()
        composeRule.onNodeWithText("The change goes onto the branch fix/retry as a new commit.").assertIsDisplayed()
        composeRule.onNodeWithText("Commit").performClick()

        assertThat(events).containsExactly("suggestion:T1:    retry(3) { send(file) }")
        composeRule.onNodeWithText("Commit this suggestion?").assertDoesNotExist()
    }

    @Test
    fun a_suggestion_called_off_commits_nothing() {
        setContent(listOf(suggesting), PullRequestUiState(appliesSuggestions = true), canCommit = true)

        composeRule.onNodeWithText("Commit suggestion").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(events).isEmpty()
    }

    @Test
    fun a_suggestion_is_not_offered_to_commit_where_its_lines_moved_on_or_are_not_known() {
        // Code that moved on since, and lines the forge didn't give (GitLab's threads).
        setContent(listOf(suggesting.copy(id = "A", outdated = true), suggesting.copy(id = "B", hunk = null)), PullRequestUiState(appliesSuggestions = true), canCommit = true)

        composeRule.onAllNodes(hasText("Suggested change")).assertCountEquals(2)
        composeRule.onNodeWithText("Commit suggestion").assertDoesNotExist()
    }

    @Test
    fun a_suggestion_is_not_offered_to_commit_to_a_reader_who_may_not_push() {
        setContent(listOf(suggesting), PullRequestUiState(appliesSuggestions = true), canCommit = false)

        composeRule.onNodeWithText("Suggested change").assertIsDisplayed()
        composeRule.onNodeWithText("Commit suggestion").assertDoesNotExist()
    }

    @Test
    fun a_suggestion_is_not_offered_to_commit_where_the_forge_cannot() {
        setContent(listOf(suggesting), PullRequestUiState(appliesSuggestions = false), canCommit = true)

        composeRule.onNodeWithText("Commit suggestion").assertDoesNotExist()
    }

    @Test
    fun a_suggestion_being_committed_can_t_be_pressed_again() {
        setContent(listOf(suggesting), PullRequestUiState(appliesSuggestions = true, busyThread = "T1"), canCommit = true)

        composeRule.onNodeWithText("Commit suggestion").assertIsNotEnabled()
    }

    @Test
    fun a_suggestion_to_take_lines_away_says_so() {
        setContent(listOf(open.copy(line = 2, comments = listOf(ThreadComment(1, ForgeUser("hubot", null, null), "```suggestion\n```", at)))))

        composeRule.onNodeWithText("Suggests removing these lines").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, removed:     retry { send(file) }").assertIsDisplayed()
    }

    @Test
    fun a_thread_is_answered_where_it_stands() {
        setContent(listOf(open))

        composeRule.onNodeWithText("Reply").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Three is plenty.")
        composeRule.onNodeWithContentDescription("Send reply").performClick()

        assertThat(events).containsExactly("reply:T1:Three is plenty.")
    }

    @Test
    fun a_thread_is_resolved_and_a_resolved_one_reopened() {
        setContent(listOf(open, resolved))

        composeRule.onNodeWithText("Resolve").performClick()
        composeRule.onNodeWithText("Show 1 resolved thread").performClick()
        composeRule.onNodeWithText("Reopen").performClick()

        assertThat(events).containsExactly("resolved:T1:true", "resolved:T2:false").inOrder()
    }

    @Test
    fun a_thread_being_changed_can_t_be_pressed_again() {
        setContent(listOf(open), PullRequestUiState(resolvesThreads = true, busyThread = "T1"))

        composeRule.onNodeWithText("Resolve").assertIsNotEnabled()
    }

    @Test
    fun a_forge_that_can_t_resolve_or_a_thread_the_reader_may_not_offers_only_to_reply() {
        setContent(listOf(open), PullRequestUiState(resolvesThreads = false))

        composeRule.onNodeWithText("Reply").assertIsDisplayed()
        composeRule.onNodeWithText("Resolve").assertDoesNotExist()
    }

    @Test
    fun signed_out_threads_are_read_and_nothing_more() {
        setContent(listOf(open), canReply = false)

        waitFor("Why not three times?")
        composeRule.onNodeWithText("Reply").assertDoesNotExist()
        composeRule.onNodeWithText("Resolve").assertDoesNotExist()
    }

    @Test
    fun threads_all_resolved_say_so() {
        setContent(listOf(resolved))

        composeRule.onNodeWithText("1 thread, resolved").assertIsDisplayed()
    }

    // As GitLab keeps it (gitlab.com, 2026-10-25): no hunk on the thread, the suggestion says what it replaces itself.
    private fun keptBy(appliable: Boolean, applied: Boolean) = ReviewThread(
        "D1", "README.md", 3, onOldSide = false, hunk = null, resolved = false, canResolve = true,
        comments = listOf(
            ThreadComment(
                1, ForgeUser("hubot", null, null), "Better:\n```suggestion:-0+0\nA line the suggestion wrote.\n```", at,
                listOf(KeptSuggestion(23996652, 3, listOf("A line the merge request adds."), listOf("A line the suggestion wrote."), appliable, applied)),
            ),
        ),
    )

    @Test
    fun a_suggestion_the_forge_keeps_shows_what_it_replaces_and_is_committed_by_it() {
        setContent(listOf(keptBy(appliable = true, applied = false)), PullRequestUiState(appliesSuggestions = true), canCommit = true)

        waitFor("Better:")
        composeRule.onNodeWithContentDescription("Line 3, removed: A line the merge request adds.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 3, added: A line the suggestion wrote.").assertIsDisplayed()
        composeRule.onNodeWithText("Commit suggestion").performClick()
        composeRule.onNodeWithText("Commit").performClick()

        assertThat(events).containsExactly("suggestion:D1:A line the suggestion wrote.")
    }

    @Test
    fun a_suggestion_the_forge_applied_already_says_so_and_offers_nothing() {
        setContent(listOf(keptBy(appliable = false, applied = true)), PullRequestUiState(appliesSuggestions = true), canCommit = true)

        waitFor("Better:")
        composeRule.onNodeWithText("Suggested change · committed").assertIsDisplayed()
        composeRule.onNodeWithText("Commit suggestion").assertDoesNotExist()
    }

    private val mine = PullRequestUiState(resolvesThreads = true, me = "octocat", editsThreadComments = true, deletesThreadComments = true)

    @Test
    fun the_reader_s_own_remark_is_rewritten_in_its_place() {
        setContent(listOf(open), state = mine)

        // Only the reader's own remark has options: hubot's has none.
        composeRule.onAllNodes(hasContentDescription("Comment options")).assertCountEquals(1)
        composeRule.onNode(hasContentDescription("Comment options")).performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNode(hasText("Flaky links.") and hasSetTextAction()).performTextReplacement("Flaky links, twice a day.")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("edit:T1:2:Flaky links, twice a day.")
    }

    @Test
    fun the_reader_s_own_remark_is_taken_back_once_they_say_so() {
        setContent(listOf(open), state = mine)

        composeRule.onNode(hasContentDescription("Comment options")).performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete this comment?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Delete").onLast().performClick()

        assertThat(events).containsExactly("delete:T1:2")
    }

    @Test
    fun where_a_remark_can_only_be_rewritten_deleting_isn_t_offered() {
        // Forgejo answers yes to deleting one and keeps it.
        setContent(listOf(open), state = mine.copy(deletesThreadComments = false))

        composeRule.onNode(hasContentDescription("Comment options")).performClick()

        composeRule.onNodeWithText("Edit").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun a_remark_shows_its_reactions_and_takes_one_from_anyone_signed_in() {
        val reacted = open.copy(comments = listOf(open.comments.first().copy(reactions = mapOf(Reaction.THUMBS_UP to 2)), open.comments.last()))
        setContent(listOf(reacted), state = PullRequestUiState(resolvesThreads = true, me = "someone", reactsToThreadComments = true))

        // Tappable, it says what it is and how many gave it.
        composeRule.onNode(hasContentDescription("Thumbs up, 2")).assertIsDisplayed()
        // Not the reader's own, yet a reaction can be given to either.
        composeRule.onAllNodes(hasContentDescription("Comment options")).assertCountEquals(2)
        composeRule.onAllNodes(hasContentDescription("Comment options"))[0].performClick()
        composeRule.onNode(hasContentDescription("Heart")).performClick()

        assertThat(events).containsExactly("react:T1:1:HEART")
    }
}
