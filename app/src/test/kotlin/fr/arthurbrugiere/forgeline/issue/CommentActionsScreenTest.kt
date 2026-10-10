package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.testing.issueSummary
import fr.arthurbrugiere.forgeline.core.model.AttachmentRule
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
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
    private val typedReferences = mutableListOf<TypedReference?>()
    private val suggested = mutableStateOf(ReferenceOffer())

    /** What Settings holds for the test; every default unless it says otherwise. */
    private var userSettings = fr.arthurbrugiere.forgeline.core.model.UserSettings()

    private fun setContent(state: IssueUiState = opened, canComment: Boolean = true) {
        shown.value = state
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalUserSettings provides userSettings) {
            IssueScreen(
                state = shown.value,
                canComment = canComment,
                onDraftChange = { events += "draft:$it" }, onSendComment = { events += "send" }, onToggleOpen = { events += "toggle" }, onSignIn = {},
                onCommentNoticeShown = {}, onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {},
                onOpenInBrowser = {}, onLinkClick = { events += "link:$it" }, onErrorShown = {},
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
                    onPickPicture = { events += "pick" },
                    onAttachErrorShown = { events += "attach-error-shown" },
                    onReferenceTyped = { typedReferences += it },
                ),
                suggestions = suggested.value,
            )
        }
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

    private val attaching = opened.copy(attachments = AttachmentRule.ANYONE)

    @Test
    fun a_picture_is_asked_for_from_the_reader_s_turn() {
        setContent(attaching)
        reach(hasContentDescription("Attach a picture"))

        composeRule.onNode(hasContentDescription("Attach a picture")).performClick()

        assertThat(events).containsExactly("pick")
    }

    @Test
    fun where_the_reader_may_not_attach_it_is_not_offered() {
        // GitHub's API takes no file; on Forgejo a conversation's files are its author's and the writers' to add.
        setContent(opened)
        reach(hasSetTextAction())
        assertThat(composeRule.onAllNodes(hasContentDescription("Attach a picture")).fetchSemanticsNodes()).isEmpty()

        shown.value = opened.copy(attachments = AttachmentRule.AUTHOR_OR_WRITER)
        composeRule.waitForIdle()
        assertThat(composeRule.onAllNodes(hasContentDescription("Attach a picture")).fetchSemanticsNodes()).isEmpty()

        shown.value = opened.copy(attachments = AttachmentRule.AUTHOR_OR_WRITER, access = RepoAccess.WRITE)
        composeRule.waitForIdle()
        composeRule.onNode(hasContentDescription("Attach a picture")).assertIsDisplayed()
    }

    @Test
    fun while_a_picture_uploads_it_says_so_and_another_is_not_asked_for() {
        setContent(attaching.copy(isAttaching = true))
        reach(hasContentDescription("Attaching the picture"))

        composeRule.onNode(hasContentDescription("Attaching the picture")).performClick()

        assertThat(events).isEmpty()
    }

    @Test
    fun a_picture_that_wasn_t_attached_says_why() {
        setContent(attaching.copy(attachError = ForgeError.Http(413, null)))
        composeRule.waitUntil(5_000) { offered("That picture is too large: 10 MB at most.") }
        assertThat(events).containsExactly("attach-error-shown")
    }

    @Test
    fun each_reason_a_picture_wasn_t_attached_has_its_own_words() {
        setContent(attaching.copy(attachError = ForgeError.Http(403, "no")))
        composeRule.waitUntil(5_000) { offered("You can't attach a picture here.") }
    }

    @Test
    fun a_picture_that_couldn_t_be_read_or_sent_is_said_so() {
        setContent(attaching.copy(attachError = ForgeError.Unreadable))
        composeRule.waitUntil(5_000) { offered("That picture couldn't be read.") }
    }

    @Test
    fun a_picture_lost_to_the_network_can_be_tried_again() {
        setContent(attaching.copy(attachError = ForgeError.Network))
        composeRule.waitUntil(5_000) { offered("The picture wasn't attached. Try again.") }
    }

    @Test
    fun the_actions_of_the_reader_s_turn_are_large_enough_to_tap() {
        setContent(attaching.copy(draft = "Thanks", canChangeState = true))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    private fun comments() = composeRule.onAllNodes(hasTestTag(COMMENT_TAG))

    @Test
    fun a_long_press_on_a_comment_opens_its_menu() {
        setContent()

        comments()[1].performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertThat(offered("Quote reply")).isTrue()
        composeRule.onNodeWithText("Quote reply").performClick()
        assertThat(events).containsExactly("quote:Same here")
    }

    @Test
    fun a_long_press_on_the_description_opens_its_menu_too() {
        setContent(opened.copy(me = "octocat"))

        comments()[0].performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertThat(offered("Edit")).isTrue()
    }

    @Test
    fun signed_out_a_long_press_a_double_tap_and_a_pull_do_nothing() {
        setContent(canComment = false)

        comments()[1].performTouchInput { longClick() }
        comments()[1].performTouchInput { doubleClick() }
        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = right) }
        composeRule.waitForIdle()

        assertThat(offered("Quote reply")).isFalse()
        assertThat(events).isEmpty()
    }

    @Test
    fun a_double_tap_gives_a_comment_a_thumbs_up() {
        setContent()

        comments()[1].performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("react:1:THUMBS_UP")
    }

    @Test
    fun a_double_tap_on_the_description_gives_the_conversation_a_thumbs_up() {
        setContent()

        comments()[0].performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("react:null:THUMBS_UP")
    }

    @Test
    fun a_single_tap_on_a_comment_does_nothing() {
        setContent()

        comments()[1].performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(1_000)

        assertThat(events).isEmpty()
        assertThat(offered("Quote reply")).isFalse()
    }

    @Test
    fun pulling_a_comment_to_the_end_of_the_line_starts_a_reply_quoting_it() {
        setContent()

        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = left + 20f + 120.dp.toPx()) }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("quote:Same here")
    }

    @Test
    fun a_short_pull_or_one_the_other_way_quotes_nothing() {
        setContent()

        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = left + 20f + 40.dp.toPx()) }
        comments()[1].performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertThat(events).isEmpty()
    }

    @Test
    fun a_locked_conversation_can_t_be_replied_to_by_pulling_but_still_takes_a_thumbs_up() {
        setContent(opened.copy(issue = issueDetails(ref, "Crash on start").copy(isLocked = true)))

        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = left + 20f + 120.dp.toPx()) }
        comments()[1].performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertThat(events).containsExactly("react:1:THUMBS_UP")
    }

    @Test
    fun a_comment_is_rewritten_where_it_stands() {
        // Regression: the comment's text went to the reader's turn, at the very end of the conversation, with nothing
        // there to say which comment it was.
        setContent(opened.copy(editing = 1, draft = "Same here"))

        val field = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithText("Mine").fetchSemanticsNode().boundsInRoot
        assertThat(field.bottom).isAtMost(next.top)
        composeRule.onNodeWithText("Rewriting your comment").assertIsDisplayed()
        // Its words show once, in the field; the reader's turn waits at the end.
        assertThat(composeRule.onAllNodesWithText("Same here").fetchSemanticsNodes()).hasSize(1)
        assertThat(composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).hasSize(1)
        assertThat(offered("Comment")).isFalse()
    }

    @Test
    fun the_field_of_a_comment_being_rewritten_has_the_keyboard() {
        setContent(opened.copy(editing = 1, draft = "Same here"))

        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun a_comment_being_rewritten_has_no_menu_and_can_t_be_pulled() {
        setContent(opened.copy(editing = 1, draft = "Same here"))

        // The description's and the other comment's.
        assertThat(composeRule.onAllNodesWithContentDescription("Comment options").fetchSemanticsNodes()).hasSize(2)
        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = left + 20f + 120.dp.toPx()) }
        composeRule.waitForIdle()
        assertThat(events).isEmpty()
    }

    @Test
    fun once_the_rewrite_is_over_the_reader_s_turn_is_back() {
        setContent(opened.copy(editing = 1, draft = "Same here"))

        shown.value = opened
        composeRule.waitForIdle()

        reach(hasText("Comment"))
        composeRule.onNodeWithText("Comment").assertIsDisplayed()
        assertThat(offered("Rewriting your comment")).isFalse()
    }

    @Test
    fun what_went_wrong_is_still_said_once_it_has_been_taken() {
        // Regression: taking the error cleared it, which ended the effect saying it: the words went as they came.
        composeRule.setContent {
            IssueScreen(
                state = shown.value, canComment = true, onDraftChange = {}, onSendComment = {}, onToggleOpen = {}, onSignIn = {},
                onCommentNoticeShown = {}, onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {},
                onOpenInBrowser = {}, onLinkClick = {}, onErrorShown = {}, nowMillis = 0,
                comments = CommentActions(onDeleteErrorShown = { events += "taken"; shown.value = shown.value.copy(deleteError = null) }),
            )
        }
        shown.value = opened.copy(deleteError = ForgeError.Network)

        composeRule.waitUntil(5_000) { offered("The comment wasn't deleted. Try again.") }
        assertThat(events).containsExactly("taken")
    }

    // Issue #11: conversations named by number.

    @Test
    fun a_conversation_named_by_its_number_in_a_comment_is_a_link_to_it() {
        setContent(opened.copy(items = listOf(comment(1, "#12", login = "hubot"))))
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag(MARKDOWN_PENDING_TAG), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithText("#12").performClick()

        assertThat(events).containsExactly("link:https://github.com/octo/repo/issues/12")
    }

    @Test
    fun the_description_s_references_are_links_too() {
        setContent(opened.copy(issue = issueDetails(ref, "Crash on start").copy(body = "#3"), items = emptyList()))
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasTestTag(MARKDOWN_PENDING_TAG), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithText("#3").performClick()

        assertThat(events).containsExactly("link:https://github.com/octo/repo/issues/3")
    }

    @Test
    fun typing_a_reference_says_which_and_leaving_it_says_none() {
        setContent()
        reach(hasSetTextAction())

        composeRule.onNode(hasSetTextAction()).performTextInput("See #1")
        shown.value = opened.copy(draft = "See #1")
        composeRule.onNode(hasSetTextAction()).performTextInput(". ")

        assertThat(typedReferences).containsExactly(TypedReference(4, '#', "1"), null).inOrder()
    }

    private val candidates = listOf(issueSummary(120, "Crash on start"), issueSummary(121, "Fix the crash", isPullRequest = true))

    @Test
    fun the_conversations_a_reference_may_mean_are_offered_under_the_field_and_picking_one_writes_its_number() {
        setContent(opened.copy(draft = "See #cra"))
        suggested.value = ReferenceOffer(candidates)
        reach(hasContentDescription("Pull request #121, Fix the crash"))

        composeRule.onNode(hasContentDescription("Issue #120, Crash on start")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Pull request #121, Fix the crash")).performClick()

        assertThat(events).containsExactly("draft:See #121 ")
        // Picked: there is nothing left being typed.
        assertThat(typedReferences.last()).isNull()
    }

    @Test
    fun nothing_is_offered_when_nothing_is_suggested_or_while_the_comment_is_previewed() {
        setContent(opened.copy(draft = "See #cra"))
        assertThat(composeRule.onAllNodes(hasTestTag(REFERENCE_SUGGESTIONS_TAG)).fetchSemanticsNodes()).isEmpty()

        suggested.value = ReferenceOffer(candidates)
        reach(hasContentDescription("Preview"))
        composeRule.onNode(hasContentDescription("Preview")).performClick()
        composeRule.waitForIdle()

        assertThat(composeRule.onAllNodes(hasTestTag(REFERENCE_SUGGESTIONS_TAG)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun a_reference_is_suggested_for_a_comment_being_rewritten_too() {
        setContent(opened.copy(editing = 1, draft = "Same here #"))
        suggested.value = ReferenceOffer(candidates)

        composeRule.onNode(hasContentDescription("Issue #120, Crash on start")).performClick()

        assertThat(events).containsExactly("draft:Same here #120 ")
    }

    @Test
    fun suggestions_are_large_enough_to_tap() {
        setContent(opened.copy(draft = "#"))
        suggested.value = ReferenceOffer(candidates)

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun a_preview_shows_references_as_they_will_read() {
        setContent(opened.copy(draft = "Fixed by #12"))
        reach(hasContentDescription("Preview"))

        composeRule.onNode(hasContentDescription("Preview")).performClick()

        composeRule.waitUntil(5_000) { offered("Fixed by #12") }
    }

    @Test
    fun while_the_forge_is_asked_the_list_is_there_and_says_so() {
        // Regression: nothing showed under the field until a far forge had answered.
        setContent(opened.copy(draft = "See #"))
        suggested.value = ReferenceOffer(isLoading = true)
        reach(hasContentDescription("Looking for conversations"))

        composeRule.onNode(hasContentDescription("Looking for conversations")).assertIsDisplayed()
    }

    @Test
    fun what_is_known_is_offered_while_more_is_looked_for() {
        setContent(opened.copy(draft = "See #cra"))
        suggested.value = ReferenceOffer(candidates, isLoading = true)
        reach(hasContentDescription("Pull request #121, Fix the crash"))

        composeRule.onNode(hasContentDescription("Looking for conversations")).assertExists()
        composeRule.onNode(hasContentDescription("Issue #120, Crash on start")).performClick()
        assertThat(events).containsExactly("draft:See #120 ")
    }

    @Test
    fun once_the_forge_found_nothing_the_list_goes() {
        setContent(opened.copy(draft = "See #zzz"))
        suggested.value = ReferenceOffer(isLoading = true)
        composeRule.waitForIdle()

        suggested.value = ReferenceOffer()
        composeRule.waitForIdle()

        assertThat(composeRule.onAllNodes(hasTestTag(REFERENCE_SUGGESTIONS_TAG)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun with_the_double_tap_switched_off_it_reacts_to_nothing() {
        userSettings = userSettings.copy(doubleTapReaction = false)
        setContent()

        comments()[1].performTouchInput { doubleClick() }
        composeRule.waitForIdle()

        assertThat(events).isEmpty()
        // The other gestures stay.
        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = left + 20f + 120.dp.toPx()) }
        composeRule.waitForIdle()
        assertThat(events).containsExactly("quote:Same here")
    }

    @Test
    fun with_swipe_to_reply_switched_off_a_pull_quotes_nothing() {
        userSettings = userSettings.copy(swipeToReply = false)
        setContent()

        comments()[1].performTouchInput { swipeRight(startX = left + 20f, endX = left + 20f + 120.dp.toPx()) }
        composeRule.waitForIdle()
        assertThat(events).isEmpty()

        // The menu still quotes, and a double tap still reacts.
        comments()[1].performTouchInput { doubleClick() }
        composeRule.waitForIdle()
        assertThat(events).containsExactly("react:1:THUMBS_UP")
    }
}
