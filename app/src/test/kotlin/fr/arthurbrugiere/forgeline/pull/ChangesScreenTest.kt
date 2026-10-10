package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import fr.arthurbrugiere.forgeline.core.testing.changedFile
import fr.arthurbrugiere.forgeline.core.testing.commit
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class ChangesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val tools = RepoId("octo", "tools")
    private val pull = ChangesTarget.Pull(IssueRef(tools, 88, isPullRequest = true))

    private fun diff(path: String, patch: String? = "@@ -1,2 +1,2 @@ fun upload()\n val tries = 1\n-send(file)\n+retry { send(file) }", change: FileChange = FileChange.MODIFIED, previousPath: String? = null) =
        changedFile(path, patch, change, previousPath).let { FileDiff(it, it.patch?.let(::parsePatch).orEmpty()) }

    private val upload = diff("src/Upload.kt")

    private fun setContent(state: ChangesUiState) = composeRule.setContent {
        ForgelineTheme {
            ChangesScreen(
                state = state,
                onBack = { events += "back" },
                onRefresh = { events += "refresh" },
                onLoadMore = { events += "more" },
                onErrorShown = { events += "error-shown" },
                onToggle = { events += "toggle:$it" },
                review = ReviewActions(
                    onStartComment = { path, line -> events += "remark:$path:${line.number}" },
                    onCancelComment = { events += "remark-cancel" },
                    onAddComment = { events += "remark-add:$it" },
                    onRemoveComment = { events += "remark-remove:${it.body}" },
                    onOpen = { events += "review-open" },
                    onClose = { events += "review-close" },
                    onSubmit = { verdict, body -> events += "review:$verdict:$body" },
                    onSentShown = { events += "sent-shown" },
                ),
                nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
            )
        }
    }

    private fun loaded(vararg files: FileDiff) = ChangesUiState(pull, files = files.toList(), isLoading = false, verdicts = ReviewVerdict.entries.toSet())

    @Test
    fun a_pull_request_s_change_reads_file_by_file_line_by_line() {
        setContent(loaded(upload, diff("docs/retry.md", "@@ -0,0 +1 @@\n+Retries three times.", FileChange.ADDED)))

        composeRule.onNode(hasText("Files changed") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("octo/tools#88").assertIsDisplayed()
        composeRule.onNodeWithText("2 files · +2 −1").assertIsDisplayed()
        composeRule.onNode(hasText("src/Upload.kt") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("@@ -1,2 +1,2 @@ fun upload()").assertIsDisplayed()
        // A screen reader hears what happened to each line, not a sign.
        composeRule.onNodeWithContentDescription("Line 1: val tries = 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, removed: send(file)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Line 2, added: retry { send(file) }").assertIsDisplayed()
        composeRule.onNode(hasText("docs/retry.md") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("Added").assertIsDisplayed()
    }

    @Test
    fun a_file_s_heading_folds_it_and_says_where_it_was_moved_from() {
        setContent(loaded(diff("new/Name.kt", change = FileChange.RENAMED, previousPath = "old/Name.kt")).copy(toggled = setOf("new/Name.kt")))

        composeRule.onNodeWithText("Moved from old/Name.kt").assertIsDisplayed()
        // Folded: its lines are not on screen.
        composeRule.onAllNodes(hasContentDescription("Line 1: val tries = 1")).assertCountEquals(0)
        composeRule.onNodeWithText("new/Name.kt").performClick()

        assertThat(events).containsExactly("toggle:new/Name.kt")
    }

    @Test
    fun a_file_with_nothing_to_read_says_why() {
        setContent(loaded(diff("logo.png", patch = null)))

        composeRule.onNodeWithText("Not shown", substring = true).assertIsDisplayed()
    }

    @Test
    fun signed_out_lines_are_only_read_and_no_review_is_offered() {
        setContent(loaded(upload))

        composeRule.onNodeWithContentDescription("Line 2, added: retry { send(file) }").performClick()

        assertThat(events).isEmpty()
        composeRule.onNodeWithText("Review").assertDoesNotExist()
    }

    @Test
    fun signed_in_a_tap_on_a_line_starts_a_remark_on_it() {
        setContent(loaded(upload).copy(canReview = true))

        composeRule.onNodeWithContentDescription("Line 2, added: retry { send(file) }").performClick()
        composeRule.onNodeWithText("Review").performClick()

        assertThat(events).containsExactly("remark:src/Upload.kt:2", "review-open").inOrder()
    }

    @Test
    fun a_remark_is_written_about_its_line_and_added_to_the_review() {
        val line = upload.hunks[0].lines[2]
        setContent(loaded(upload).copy(canReview = true, drafting = LineTarget("src/Upload.kt", line)))

        composeRule.onNodeWithText("Upload.kt, line 2").assertIsDisplayed()
        composeRule.onNodeWithText("Add to the review").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("Why twice?")
        composeRule.onNodeWithText("Add to the review").performClick()

        assertThat(events).containsExactly("remark-add:Why twice?")
    }

    @Test
    fun remarks_wait_under_their_lines_and_are_counted_on_the_button() {
        val remark = LineComment("src/Upload.kt", oldLine = null, newLine = 2, body = "Why twice?")
        setContent(loaded(upload).copy(canReview = true, comments = listOf(remark)))

        composeRule.onNodeWithText("Why twice?").assertIsDisplayed()
        composeRule.onNodeWithText("Finish review (1 remark)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove this remark").performClick()

        assertThat(events).containsExactly("remark-remove:Why twice?")
    }

    @Test
    fun a_review_gives_a_verdict_and_says_how_many_remarks_go_with_it() {
        val remark = LineComment("src/Upload.kt", oldLine = null, newLine = 2, body = "Why twice?")
        setContent(loaded(upload).copy(canReview = true, comments = listOf(remark), isReviewOpen = true))

        composeRule.onNodeWithText("1 remark on a line goes with it").assertIsDisplayed()
        composeRule.onNodeWithText("Request changes").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("One thing.")
        composeRule.onNodeWithText("Send review").performClick()

        assertThat(events).containsExactly("review:REQUEST_CHANGES:One thing.")
    }

    @Test
    fun a_comment_that_says_nothing_cannot_be_sent_and_an_approval_can() {
        setContent(loaded(upload).copy(canReview = true, isReviewOpen = true))

        composeRule.onNodeWithText("Send review").assertIsNotEnabled()
        composeRule.onNodeWithText("Approve").performClick()
        composeRule.onNodeWithText("Send review").assertIsEnabled()
    }

    @Test
    fun a_forge_that_cannot_ask_for_changes_does_not_offer_it_and_a_refusal_is_said() {
        setContent(
            loaded(upload).copy(
                canReview = true, isReviewOpen = true, verdicts = setOf(ReviewVerdict.COMMENT, ReviewVerdict.APPROVE),
                reviewError = ForgeError.Http(422, "Can not approve your own pull request"),
            ),
        )

        composeRule.onNodeWithText("Request changes").assertDoesNotExist()
        composeRule.onNodeWithText("The forge refused the review: Can not approve your own pull request").assertIsDisplayed()
    }

    @Test
    fun a_commit_opens_on_who_wrote_it_and_what_it_says() {
        setContent(
            ChangesUiState(
                ChangesTarget.OfCommit(tools, "1a2b3c4d5e6f"), files = listOf(upload), isLoading = false,
                commit = commit("1a2b3c4d5e6f", "Retry uploads\n\nOn slow links."),
            ),
        )

        composeRule.onNode(hasText("Retry uploads") and isHeading()).assertIsDisplayed()
        composeRule.onNodeWithText("octocat · 2 hours ago · 1a2b3c4").assertIsDisplayed()
        composeRule.onNodeWithText("On slow links.").assertIsDisplayed()
    }

    @Test
    fun loading_a_failure_and_an_empty_change_each_say_so() {
        setContent(ChangesUiState(pull, error = ForgeError.Network, isLoading = false))

        composeRule.onNodeWithText("Couldn't load the change").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun the_end_of_a_long_change_asks_for_more_when_it_comes_in_sight() {
        setContent(loaded(upload).copy(nextPage = 2))

        composeRule.onNodeWithText("1 file · +1 −1 · more below").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Loading the change"))
        composeRule.waitForIdle()

        assertThat(events).contains("more")
    }
}
