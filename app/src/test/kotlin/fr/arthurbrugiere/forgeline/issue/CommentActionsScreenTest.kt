package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.model.Reaction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The menu on each comment, the question before a comment is deleted, and the reader's turn while one is rewritten. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class CommentActionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val ref = IssueRef(RepoId("octo", "repo"), 7)

    /** octocat's issue, with a comment of hubot's and one of the reader's, who is "me". */
    private val opened = IssueUiState(
        ref, issueDetails(ref, "Crash on start"),
        listOf(comment(1, "Same here", login = "hubot"), comment(2, "Mine", login = "me")),
        me = "me",
    )
    private val shown = mutableStateOf(opened)

    private fun setContent(state: IssueUiState = opened, canComment: Boolean = true) {
        shown.value = state
        composeRule.setContent {
            IssueScreen(
                state = shown.value,
                canComment = canComment,
                onDraftChange = { events += "draft:$it" }, onSendComment = { events += "send" }, onToggleOpen = { events += "toggle" }, onSignIn = {},
                onCommentNoticeShown = {}, onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {},
                onOpenInBrowser = {}, onLinkClick = {}, onErrorShown = {},
                nowMillis = Instant.parse("2026-09-26T09:00:00Z").toEpochMilli(),
                comments = CommentActions(
                    onQuote = { events += "quote:$it" },
                    onEdit = { events += "edit:$it" },
                    onCancelEdit = { events += "cancel" },
                    onDelete = { events += "delete:$it" },
                    onDeleteErrorShown = { events += "delete-error-shown" },
                    onEditIssue = { events += "edit-issue" },
                    onReact = { comment, reaction -> events += "react:$comment:$reaction" },
                    onReactionErrorShown = { events += "reaction-error-shown" },
                ),
            )
        }
        composeRule.waitForIdle()
    }

    private fun reach(matcher: SemanticsMatcher) = composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)

    /** Opens the menu of the [index]th text of the conversation: 0 is the description, then the comments in order. */
    private fun openMenu(index: Int) {
        composeRule.onAllNodesWithContentDescription("Comment options")[index].performClick()
        composeRule.waitForIdle()
    }

    private fun offered(label: String) = composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun the_description_and_every_comment_have_a_menu() {
        setContent()

        assertThat(composeRule.onAllNodesWithContentDescription("Comment options").fetchSemanticsNodes()).hasSize(3)
    }

    @Test
    fun someone_else_s_comment_can_only_be_quoted() {
        setContent()

        openMenu(1)

        assertThat(offered("Edit")).isFalse()
        assertThat(offered("Delete")).isFalse()
        composeRule.onNodeWithText("Quote reply").performClick()
        assertThat(events).containsExactly("quote:Same here")
    }

    @Test
    fun one_s_own_comment_can_be_rewritten() {
        setContent()

        openMenu(2)
        composeRule.onNodeWithText("Edit").performClick()

        assertThat(events).containsExactly("edit:2")
    }

    @Test
    fun deleting_a_comment_is_asked_about_first() {
        setContent()
        openMenu(2)

        composeRule.onNodeWithText("Delete").performClick()

        // Nothing yet: the question stands between the tap and the deletion.
        assertThat(events).isEmpty()
        composeRule.onNodeWithText("Delete this comment?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertThat(events).containsExactly("delete:2")
        assertThat(offered("Delete this comment?")).isFalse()
    }

    @Test
    fun saying_no_deletes_nothing() {
        setContent()
        openMenu(2)
        composeRule.onNodeWithText("Delete").performClick()

        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(events).isEmpty()
        assertThat(offered("Delete this comment?")).isFalse()
    }

    @Test
    fun whoever_can_write_to_the_repository_may_delete_anyone_s_comment_but_not_rewrite_it() {
        setContent(opened.copy(access = RepoAccess.WRITE))

        openMenu(1)

        assertThat(offered("Delete")).isTrue()
        assertThat(offered("Edit")).isFalse()
    }

    @Test
    fun the_description_is_edited_by_its_author() {
        setContent(opened.copy(issue = issueDetails(ref).copy(author = ForgeUser("me", null, null))))

        openMenu(0)
        // A description is not deleted: the issue is, from the sheet that manages it.
        assertThat(offered("Delete")).isFalse()
        composeRule.onNodeWithText("Edit").performClick()

        assertThat(events).containsExactly("edit-issue")
    }

    @Test
    fun someone_else_s_description_can_only_be_quoted() {
        setContent()

        openMenu(0)

        assertThat(offered("Edit")).isFalse()
        composeRule.onNodeWithText("Quote reply").performClick()
        assertThat(events).containsExactly("quote:Body of #7")
    }

    @Test
    fun signed_out_there_is_no_menu_at_all() {
        // Nothing to quote into, nothing of one's own.
        setContent(opened.copy(me = null), canComment = false)

        assertThat(composeRule.onAllNodesWithContentDescription("Comment options").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun a_locked_conversation_offers_no_quote_to_who_can_t_comment_but_still_lets_them_take_their_words_back() {
        setContent(opened.copy(issue = issueDetails(ref).copy(isLocked = true)))

        // The reader's own comment: no reply to start, no rewrite to write, but it can still be deleted.
        openMenu(2)
        assertThat(offered("Quote reply")).isFalse()
        assertThat(offered("Edit")).isFalse()
        assertThat(offered("Delete")).isTrue()
        // Reactions are not comments: a locked conversation still takes them.
        composeRule.onNode(hasContentDescription("Heart")).assertIsDisplayed()
    }

    @Test
    fun a_review_s_words_have_no_menu() {
        val review = fr.arthurbrugiere.forgeline.core.model.TimelineItem.Review(
            5, ForgeUser("rev", null, null), fr.arthurbrugiere.forgeline.core.model.ReviewState.COMMENTED, "Looks odd", Instant.parse("2026-09-26T08:30:00Z"),
        )
        setContent(opened.copy(items = listOf(review)))

        // The description's only.
        assertThat(composeRule.onAllNodesWithContentDescription("Comment options").fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun the_menu_buttons_are_large_enough_to_tap() {
        setContent()

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun while_a_comment_is_rewritten_the_reader_s_turn_says_so_and_saves_instead_of_commenting() {
        setContent(opened.copy(editing = 2, draft = "Mine", canChangeState = true))

        reach(hasText("Save"))
        composeRule.onNodeWithText("Rewriting your comment").assertIsDisplayed()
        // Closing the conversation is not offered beside a rewrite.
        assertThat(offered("Close issue")).isFalse()
        assertThat(offered("Comment")).isFalse()
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertThat(events).containsExactly("send", "cancel").inOrder()
    }

    @Test
    fun a_rewrite_on_its_way_can_t_be_sent_again_or_given_up() {
        setContent(opened.copy(editing = 2, draft = "Mine", isCommenting = true))

        reach(hasText("Saving"))
        composeRule.onNodeWithText("Saving").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun a_rewrite_that_wasn_t_saved_says_why() {
        setContent(opened.copy(editing = 2, draft = "Mine", commentError = ForgeError.Network))

        reach(hasText("Save"))
        composeRule.onNodeWithText("Not sent: check your connection and send again. Your comment is kept.").assertIsDisplayed()
    }

    private fun selection(): TextRange =
        composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]

    @Test
    fun text_put_in_the_reader_s_turn_leaves_the_cursor_after_it() {
        // Regression guard: a quote ends where the reply starts; with the cursor left at the start, the reply would
        // be typed above what it answers.
        setContent()
        reach(hasSetTextAction())

        shown.value = opened.copy(draft = "> Same here\n\n", draftPlaced = 1)
        composeRule.waitForIdle()

        assertThat(selection()).isEqualTo(TextRange("> Same here\n\n".length))
    }

    @Test
    fun typing_goes_on_after_a_quote() {
        setContent()
        reach(hasSetTextAction())
        shown.value = opened.copy(draft = "> Same here\n\n", draftPlaced = 1)
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction()).performTextInput("Me too")

        assertThat(events).containsExactly("draft:> Same here\n\nMe too")
    }

    @Test
    fun a_draft_already_there_when_the_conversation_opens_has_the_cursor_at_its_end() {
        setContent(opened.copy(draft = "Half a thought"))
        reach(hasSetTextAction())

        assertThat(selection()).isEqualTo(TextRange("Half a thought".length))
    }

    @Test
    fun a_comment_that_couldn_t_be_deleted_is_said_once() {
        setContent(opened.copy(deleteError = ForgeError.Network))

        composeRule.waitUntil(5_000) { offered("The comment wasn't deleted. Try again.") }
        assertThat(events).containsExactly("delete-error-shown")
    }

    @Test
    fun every_reaction_is_offered_at_the_top_of_a_comment_s_menu() {
        setContent()

        openMenu(1)

        listOf("Thumbs up", "Thumbs down", "Laugh", "Hooray", "Confused", "Heart", "Rocket", "Eyes").forEach {
            composeRule.onNode(hasContentDescription(it)).assertIsDisplayed()
        }
        composeRule.onNode(hasContentDescription("Rocket")).performClick()
        assertThat(events).containsExactly("react:1:ROCKET")
        // Chosen, the menu is gone.
        assertThat(offered("Quote reply")).isFalse()
    }

    @Test
    fun a_reaction_from_the_description_s_menu_goes_to_the_conversation_itself() {
        setContent()

        openMenu(0)
        composeRule.onNode(hasContentDescription("Heart")).performClick()

        assertThat(events).containsExactly("react:null:HEART")
    }

    private val reacted = opened.copy(
        items = listOf(comment(1, "Same here", login = "hubot").copy(reactions = mapOf(Reaction.THUMBS_UP to 4, Reaction.EYES to 1))),
    )

    @Test
    fun a_reaction_already_there_is_joined_or_taken_back_with_a_tap_and_says_what_it_is() {
        setContent(reacted)

        // Read as what it is and how many, not as a picture and a digit.
        composeRule.onNode(hasContentDescription("Thumbs up, 4")).assertIsDisplayed().performClick()

        assertThat(events).containsExactly("react:1:THUMBS_UP")
    }

    @Test
    fun reactions_that_can_be_tapped_are_large_enough_to_tap() {
        setContent(reacted)

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun signed_out_reactions_are_only_read() {
        setContent(reacted.copy(me = null), canComment = false)

        composeRule.onNodeWithText("👍 4").assertIsDisplayed()
        assertThat(composeRule.onAllNodes(hasContentDescription("Thumbs up, 4")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun a_reaction_that_wasn_t_saved_is_said_once() {
        setContent(opened.copy(reactionError = ForgeError.Network))

        composeRule.waitUntil(5_000) { offered("The reaction wasn't saved. Try again.") }
        assertThat(events).containsExactly("reaction-error-shown")
    }

    private fun previewShown() = composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(WRITING_PREVIEW_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun fieldShown() = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun what_is_written_can_be_read_as_it_will_look_and_written_again() {
        setContent(opened.copy(draft = "Fixed in **1.2**, for *good*"))
        reach(hasContentDescription("Preview"))

        composeRule.onNode(hasContentDescription("Preview")).performClick()

        // Rendered: the marks are gone, the words stay; and the field gave its place.
        composeRule.waitUntil(5_000) { offered("Fixed in 1.2, for good") }
        assertThat(previewShown()).isTrue()
        assertThat(fieldShown()).isFalse()
        // It can be sent from there.
        composeRule.onNodeWithText("Comment").assertIsEnabled()

        composeRule.onNode(hasContentDescription("Back to writing")).performClick()

        assertThat(fieldShown()).isTrue()
        assertThat(previewShown()).isFalse()
    }

    @Test
    fun there_is_nothing_to_preview_before_something_is_written() {
        setContent()
        reach(hasSetTextAction())

        assertThat(composeRule.onAllNodes(hasContentDescription("Preview")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun once_the_comment_is_sent_the_field_is_back_for_the_next_one() {
        setContent(opened.copy(draft = "Thanks"))
        reach(hasContentDescription("Preview"))
        composeRule.onNode(hasContentDescription("Preview")).performClick()
        assertThat(previewShown()).isTrue()

        shown.value = opened.copy(draft = "", draftPlaced = 1)
        composeRule.waitForIdle()

        assertThat(fieldShown()).isTrue()
        assertThat(previewShown()).isFalse()
    }

    @Test
    fun a_comment_refused_while_previewed_still_says_why() {
        setContent(opened.copy(draft = "Thanks"))
        reach(hasContentDescription("Preview"))
        composeRule.onNode(hasContentDescription("Preview")).performClick()

        shown.value = opened.copy(draft = "Thanks", commentError = ForgeError.Network)

        composeRule.waitUntil(5_000) { offered("Not sent: check your connection and send again. Your comment is kept.") }
    }

    @Test
    fun a_rewrite_can_be_previewed_too() {
        setContent(opened.copy(editing = 2, draft = "Mine, *corrected*"))
        reach(hasContentDescription("Preview"))

        composeRule.onNode(hasContentDescription("Preview")).performClick()

        composeRule.waitUntil(5_000) { offered("Mine, corrected") }
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }
}
