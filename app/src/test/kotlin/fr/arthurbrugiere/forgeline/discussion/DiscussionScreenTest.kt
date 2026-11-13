package fr.arthurbrugiere.forgeline.discussion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Discussion
import fr.arthurbrugiere.forgeline.core.model.DiscussionReactions
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.discussionComment
import fr.arthurbrugiere.forgeline.core.testing.discussionSummary
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import fr.arthurbrugiere.forgeline.ui.assertTextFits
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class DiscussionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val repo = RepoId("octo", "repo")
    private val summary = discussionSummary(7, "How do I page?", category = "Q&A", comments = 2, isAnswered = true, upvotes = 3)
    private val read = Discussion(
        summary, "I can't find it in the docs.",
        listOf(
            discussionComment("c1", "Use the cursor.", login = "bob", isAnswer = true, upvotes = 5, replies = listOf(discussionComment("r1", "Thanks, that was it.", login = "alice"))),
            discussionComment("c2", "Same question here.", login = "carol"),
        ),
    )
    private val loaded = DiscussionUiState(repo, 7, summary, read, isLoading = false)

    /** What the screen is told a reference typed may mean. */
    private var suggestionsShown = fr.arthurbrugiere.forgeline.issue.ReferenceOffer()

    private fun setContent(state: DiscussionUiState, signedIn: Boolean = true, roomy: Boolean = false) = setContent(mutableStateOf(state), signedIn, roomy)

    private fun setContent(state: androidx.compose.runtime.State<DiscussionUiState>, signedIn: Boolean = true, roomy: Boolean = false) {
        composeRule.setContent {
            ForgelineTheme {
                androidx.compose.runtime.CompositionLocalProvider(fr.arthurbrugiere.forgeline.ui.LocalUserSettings provides fr.arthurbrugiere.forgeline.core.model.UserSettings(roomyTargets = roomy)) {
                    DiscussionScreen(
                        state = state.value, signedIn = signedIn,
                        onBack = { events += "back" }, onRefresh = { events += "refresh" }, onOpenRepo = { events += "repo:${it.fullName}" },
                        onOpenUser = { events += "user:$it" }, onOpenInBrowser = { events += "browser:$it" }, onLinkClick = { events += "link:$it" },
                        onSignIn = { events += "sign-in" }, onErrorShown = { events += "error-shown" },
                        onSend = { body, under -> events += "send:${under?.id ?: "discussion"}:$body" },
                        onSetAnswer = { comment, answer -> events += "answer:${comment.id}:$answer" },
                        onSendErrorShown = { events += "send-error-shown" },
                        onReact = { id, reaction -> events += "react:$id:$reaction" },
                        onUpvote = { events += "upvote:$it" },
                        onEditComment = { comment, body -> events += "edit:${comment.id}:$body" },
                        onDeleteComment = { events += "delete:${it.id}" },
                        onEditDiscussion = { title, body -> events += "rewrite:$title:$body" },
                        onDeleteDiscussion = { events += "delete:discussion" },
                        onReactionErrorShown = { events += "reaction-error-shown" },
                        onScrolled = { events += "scrolled" },
                        onToggleSubscribed = { events += "subscribe" },
                        suggestions = state.value.let { suggestionsShown },
                        onReferenceTyped = { typed -> typed?.let { events += "typed:${it.field}:${it.words}" } },
                        onLoadSavedReplies = { events += "saved-replies" },
                        nowMillis = Instant.parse("2026-09-26T10:00:00Z").toEpochMilli(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun reach(text: String) = composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private fun shown(text: String) = composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun at_large_text_an_answer_still_names_who_wrote_it() {
        // Regression: at 2x, the answer and upvote tags beside it left the author's name no room at all.
        val answer = discussionComment("c1", "Use the cursor.", login = "hubot", isAnswer = true, upvotes = 5)
        setContent(loaded.copy(discussion = read.copy(comments = listOf(answer))))

        composeRule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("hubot"))
        composeRule.assertTextFits("hubot", oneLine = true)
    }

    @Test
    fun a_discussion_is_headed_by_what_it_asks_where_it_was_filed_and_who_started_it() {
        setContent(loaded)

        composeRule.onNodeWithText("How do I page? - #7").assertIsDisplayed()
        composeRule.onNodeWithText("Q&A").assertIsDisplayed()
        composeRule.onNodeWithText("Answered").assertIsDisplayed()
        composeRule.onNode(hasContentDescription("3 upvotes")).assertIsDisplayed()
        composeRule.onNodeWithText("alice started this 2 hours ago").assertIsDisplayed()
    }

    @Test
    fun its_text_its_comments_and_their_replies_are_read_in_order_and_the_answer_says_so() {
        setContent(loaded)

        composeRule.waitUntil(5_000) { shown("I can't find it in the docs.") }
        reach("Use the cursor.")
        assertThat(composeRule.onAllNodesWithText("Answer", useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
        reach("Same question here.")
        assertThat(shown("Use the cursor.")).isTrue()
        assertThat(shown("Thanks, that was it.")).isTrue()
        val answer = composeRule.onNodeWithText("Use the cursor.").fetchSemanticsNode().boundsInRoot
        val reply = composeRule.onNodeWithText("Thanks, that was it.").fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithText("Same question here.").fetchSemanticsNode().boundsInRoot
        assertThat(answer.top).isLessThan(reply.top)
        assertThat(reply.top).isLessThan(next.top)
        // A reply stands in from the comment it answers.
        assertThat(reply.left).isGreaterThan(next.left - 1f)
    }

    @Test
    fun a_discussion_nobody_answered_yet_says_so() {
        setContent(loaded.copy(discussion = read.copy(summary = summary.copy(comments = 0), comments = emptyList())))

        reach("No comments yet.")
        composeRule.onNodeWithText("No comments yet.").assertIsDisplayed()
    }

    @Test
    fun when_only_the_first_comments_were_read_it_says_so_and_offers_the_rest_on_the_forge() {
        setContent(loaded.copy(discussion = read.copy(summary = summary.copy(comments = 80))))

        reach("Not every comment and reply could be read here.")
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Open on GitHub"))
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/discussions/7")
    }

    @Test
    fun a_whole_discussion_does_not_say_some_is_missing() {
        setContent(loaded)

        reach("Open on GitHub")
        assertThat(shown("Not every comment")).isFalse()
    }

    @Test
    fun while_it_loads_what_its_list_said_heads_the_page() {
        setContent(DiscussionUiState(repo, 7, summary = summary, isLoading = true))

        composeRule.onNodeWithText("How do I page? - #7").assertIsDisplayed()
    }

    @Test
    fun opened_from_a_link_it_is_headed_by_its_number_until_it_is_read() {
        setContent(DiscussionUiState(repo, 7, isLoading = true))

        composeRule.onNodeWithText("#7").assertIsDisplayed()
    }

    @Test
    fun signed_out_it_says_an_account_is_needed_and_offers_to_sign_in_or_the_forge() {
        setContent(DiscussionUiState(repo, 7, isLoading = false, error = ForgeError.Unauthorized), signedIn = false)

        composeRule.onNodeWithText("Sign in to read discussions").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("sign-in", "browser:https://github.com/octo/repo/discussions/7").inOrder()
    }

    @Test
    fun one_that_is_gone_says_so_and_offers_the_forge_rather_than_trying_again() {
        setContent(DiscussionUiState(repo, 7, isLoading = false, error = ForgeError.Http(404, "Not Found")))

        composeRule.onNodeWithText("It may have been deleted, or moved to an issue.").assertIsDisplayed()
        composeRule.onNodeWithText("Open on GitHub").performClick()

        assertThat(events).containsExactly("browser:https://github.com/octo/repo/discussions/7")
    }

    @Test
    fun one_that_couldn_t_be_read_offline_can_be_asked_for_again() {
        setContent(DiscussionUiState(repo, 7, isLoading = false, error = ForgeError.Network))

        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun a_refresh_that_failed_is_only_said_over_what_was_read() {
        setContent(loaded.copy(error = ForgeError.Network))

        composeRule.waitUntil(5_000) { shown("Couldn't refresh") || events.contains("error-shown") }
        assertThat(events).contains("error-shown")
        assertThat(shown("Couldn't load the discussion")).isFalse()
    }

    @Test
    fun its_repository_and_the_people_in_it_open() {
        setContent(loaded)

        composeRule.onNodeWithText("octo/repo").performClick()
        reach("Same question here.")
        composeRule.onNodeWithText("carol").performClick()

        assertThat(events).containsExactly("repo:octo/repo", "user:carol").inOrder()
    }

    @Test
    fun every_target_is_large_enough_to_tap() {
        setContent(loaded)

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    @Test
    fun its_repository_and_its_people_are_said_to_open_and_with_roomy_links_its_repository_line_is_a_finger_tall() {
        // Regression: TalkBack heard "bob" and "octo/repo" as plain words, and the repository was a 19dp line of text.
        setContent(loaded, roomy = true)

        composeRule.onNodeWithText("octo/repo").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("bob").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
    }

    @Test
    fun its_repository_line_keeps_its_own_height_unless_links_are_asked_to_be_roomy() {
        setContent(loaded)
        composeRule.onNodeWithText("octo/repo").getBoundsInRoot().let { com.google.common.truth.Truth.assertThat((it.bottom - it.top).value).isAtMost(30f) }
    }

    @Test
    fun a_discussion_is_shared_by_its_address_under_what_it_asks() {
        setContent(loaded)

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        com.google.common.truth.Truth.assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octo/repo/discussions/7" to "How do I page?")
    }

    private val writable = loaded.copy(
        discussion = read.copy(
            id = "D_1",
            comments = listOf(read.comments[0].copy(canUnmarkAnswer = true), read.comments[1].copy(canMarkAnswer = true)),
        ),
    )

    @Test
    fun a_comment_is_written_at_the_end_and_its_field_emptied_once_sent() {
        val state = mutableStateOf(writable)
        setContent(state)

        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performTextInput("Found it, thanks.")
        composeRule.onNodeWithContentDescription("Send comment").performClick()
        assertThat(events).containsExactly("send:discussion:Found it, thanks.")

        state.value = writable.copy(sent = DiscussionViewModel.COMMENT to 1)
        composeRule.waitForIdle()
        assertThat(shown("Found it, thanks.")).isFalse()
    }

    @Test
    fun a_reply_is_written_under_the_comment_it_answers() {
        setContent(writable)

        reach("Same question here.")
        composeRule.onAllNodesWithText("Reply")[1].performClick()
        composeRule.onNodeWithText("Reply to carol").performTextInput("Did the cursor help?")
        composeRule.onNodeWithContentDescription("Send reply").performClick()

        assertThat(events).containsExactly("send:c2:Did the cursor help?")
    }

    @Test
    fun an_answer_is_picked_or_taken_back_where_the_reader_may() {
        setContent(writable)

        reach("No longer the answer")
        composeRule.onNodeWithText("No longer the answer").performClick()
        reach("Mark as answer")
        composeRule.onNodeWithText("Mark as answer").performClick()

        assertThat(events).containsExactly("answer:c1:false", "answer:c2:true").inOrder()
    }

    @Test
    fun a_locked_discussion_says_so_and_offers_no_field_nor_reply() {
        setContent(writable.copy(discussion = writable.discussion!!.copy(locked = true)))

        reach("This discussion is locked")
        assertThat(shown("Comment on this discussion")).isFalse()
        assertThat(shown("Reply")).isFalse()
        // Picking the answer is still the author's.
        assertThat(shown("Mark as answer")).isTrue()
    }

    @Test
    fun a_discussion_that_can_t_be_commented_on_from_here_offers_no_field() {
        // Read without what the forge names it by.
        setContent(loaded)

        reach("Open on GitHub")
        assertThat(shown("Comment on this discussion")).isFalse()
        assertThat(shown("Reply")).isFalse()
    }

    @Test
    fun a_comment_that_didn_t_go_through_says_why_and_stays_written() {
        val state = mutableStateOf(writable)
        setContent(state)
        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performTextInput("Hello")

        state.value = writable.copy(sendError = ForgeError.Http(403, "Discussion is locked"))

        composeRule.waitUntil(5_000) { shown("You can't comment here.") }
        assertThat(events).contains("send-error-shown")
        assertThat(shown("Hello")).isTrue()
    }

    private val reactable = read.copy(
        id = "D1",
        reactions = DiscussionReactions(mapOf(Reaction.HEART to 2), setOf(Reaction.HEART), canReact = true),
        comments = read.comments.map { comment ->
            comment.copy(
                reactions = DiscussionReactions(if (comment.id == "c1") mapOf(Reaction.ROCKET to 3) else emptyMap(), canReact = true),
                replies = comment.replies.map { it.copy(reactions = DiscussionReactions(canReact = true)) },
            )
        },
    )

    @Test
    fun the_reactions_given_show_and_tapping_one_gives_it_or_takes_it_back() {
        setContent(loaded.copy(discussion = reactable))

        composeRule.onNodeWithContentDescription("Heart, 2").performClick()
        reach("Use the cursor.")
        composeRule.onNodeWithContentDescription("Rocket, 3").performClick()

        assertThat(events).containsExactly("react:D1:HEART", "react:c1:ROCKET").inOrder()
    }

    @Test
    fun a_reaction_is_picked_from_a_comment_s_or_a_reply_s_menu() {
        setContent(loaded.copy(discussion = reactable))

        reach("Thanks, that was it.")
        // The text's, the two comments' and the reply's menus, in their order.
        composeRule.onAllNodes(hasContentDescription("Comment options"))[2].performClick()
        composeRule.onNodeWithContentDescription("Eyes").performClick()

        assertThat(events).containsExactly("react:r1:EYES")
    }

    @Test
    fun where_the_forge_takes_no_reaction_from_the_reader_those_given_only_show() {
        // Signed out, or kept out by a lock: GitHub says so of each.
        setContent(loaded.copy(discussion = reactable.copy(reactions = reactable.reactions.copy(canReact = false))))

        assertThat(shown("❤️ 2")).isTrue()
        assertThat(composeRule.onAllNodes(hasContentDescription("Heart, 2")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun quoting_a_reply_starts_a_reply_in_its_thread_with_its_words() {
        setContent(loaded.copy(discussion = reactable.copy(id = "D1")))

        reach("Thanks, that was it.")
        composeRule.onAllNodes(hasContentDescription("Comment options"))[2].performClick()
        composeRule.onNodeWithText("Quote reply").performClick()
        composeRule.onNodeWithContentDescription("Send reply").performClick()

        assertThat(events).containsExactly("send:c1:> Thanks, that was it.\n\n")
    }

    @Test
    fun a_reaction_that_didn_t_go_through_is_said() {
        setContent(loaded.copy(discussion = reactable, reactionError = ForgeError.Network))

        assertThat(events).contains("reaction-error-shown")
    }

    @Test
    fun where_the_reader_may_upvote_the_count_is_a_toggle_that_says_whether_theirs_is_given() {
        val votable = read.copy(
            id = "D1", upvoted = true, canUpvote = true,
            comments = read.comments.map { it.copy(canUpvote = it.id == "c2") },
        )
        setContent(loaded.copy(discussion = votable))

        composeRule.onNode(hasContentDescription("Upvote, 3 upvotes")).assertIsOn().performClick()
        reach("Same question here.")
        // None given to carol's yet: the toggle offers the first.
        composeRule.onNode(hasContentDescription("Upvote, 0 upvotes")).assertIsOff().performClick()

        assertThat(events).containsExactly("upvote:D1", "upvote:c2").inOrder()
        // Bob's answer takes none from the reader: its count only shows.
        assertThat(composeRule.onAllNodes(hasContentDescription("5 upvotes")).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun signed_out_upvotes_only_show() {
        setContent(loaded)

        assertThat(composeRule.onAllNodes(hasContentDescription("Upvote", substring = true)).fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodes(hasContentDescription("3 upvotes")).fetchSemanticsNodes()).hasSize(1)
    }

    private val own = read.copy(
        id = "D1", canEdit = true, canDelete = true,
        comments = read.comments.map { comment -> comment.copy(canEdit = comment.id == "c2", canDelete = comment.id == "c2", replies = comment.replies.map { it.copy(canEdit = true, canDelete = true) }) },
    )

    @Test
    fun the_reader_s_own_comment_is_rewritten_where_it_stands() {
        setContent(loaded.copy(discussion = own))

        reach("Same question here.")
        // The text's, bob's, the reply's, then carol's.
        composeRule.onAllNodes(hasContentDescription("Comment options"))[3].performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithText("Same question here.").performTextReplacement("Same question, still.")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("edit:c2:Same question, still.")
    }

    @Test
    fun a_reply_is_deleted_once_the_reader_says_so() {
        setContent(loaded.copy(discussion = own))

        reach("Thanks, that was it.")
        composeRule.onAllNodes(hasContentDescription("Comment options"))[2].performClick()
        composeRule.onNodeWithText("Delete").performClick()
        assertThat(events).isEmpty()
        composeRule.onNodeWithText("Delete this comment?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Delete").onLast().performClick()

        assertThat(events).containsExactly("delete:r1")
    }

    @Test
    fun the_discussion_s_title_and_text_are_rewritten_and_it_can_be_deleted() {
        setContent(loaded.copy(discussion = own))

        composeRule.onAllNodes(hasContentDescription("Comment options"))[0].performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("Delete this discussion?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Delete").onLast().performClick()
        composeRule.onAllNodes(hasContentDescription("Comment options"))[0].performClick()
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNode(hasSetTextAction() and hasText("How do I page?")).performTextReplacement("How do I page through?")
        composeRule.onNodeWithText("Save").performClick()

        assertThat(events).containsExactly("delete:discussion", "rewrite:How do I page through?:I can't find it in the docs.").inOrder()
    }

    @Test
    fun what_the_reader_may_not_change_offers_no_edit_nor_delete() {
        setContent(loaded.copy(discussion = own))

        reach("Use the cursor.")
        composeRule.onAllNodes(hasContentDescription("Comment options"))[1].performClick()

        assertThat(shown("Edit")).isFalse()
        assertThat(shown("Delete")).isFalse()
    }

    @Test
    fun once_deleted_the_page_goes_back() {
        setContent(loaded.copy(discussion = own, deleted = true))

        assertThat(events).contains("back")
    }

    @Test
    fun while_the_rest_is_read_it_says_so_rather_than_that_some_is_missing() {
        setContent(loaded.copy(discussion = read.copy(summary = summary.copy(comments = 80)), readingRest = true))

        reach("Reading the rest of the discussion")
        assertThat(shown("Not every comment")).isFalse()
    }

    @Test
    fun each_comment_and_reply_is_found_where_the_list_draws_it() {
        // Header, text; bob's, alice's reply, bob's actions; carol's, her actions; the end.
        assertThat(listOf("c1", "r1", "c2", DiscussionViewModel.END).map { listIndexOf(it, read, canComment = true) }).containsExactly(2, 3, 5, 7).inOrder()
        // Signed out, nothing is offered beside them.
        assertThat(listOf("c2", DiscussionViewModel.END).map { listIndexOf(it, read, canComment = false) }).containsExactly(4, 5).inOrder()
        assertThat(listIndexOf("gone", read, canComment = true)).isNull()
        assertThat(listIndexOf(DiscussionViewModel.END, read.copy(comments = emptyList()), canComment = true)).isEqualTo(3)
    }

    @Test
    fun sent_to_what_is_new_the_list_goes_there_and_says_so() {
        val many = read.copy(id = "D1", comments = (1..20).map { discussionComment("c$it", "Comment number $it.", login = "user$it") })
        setContent(loaded.copy(discussion = many, scrollTo = "c15"))
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Comment number 15.").assertIsDisplayed()
        assertThat(events).contains("scrolled")
    }

    @Test
    fun the_reader_following_it_hears_of_every_reply_and_may_stop() {
        setContent(loaded.copy(discussion = read.copy(subscribed = true)))

        reach("You hear of every reply here.")
        composeRule.onNodeWithText("Unsubscribe").performClick()

        assertThat(events).containsExactly("subscribe")
    }

    @Test
    fun signed_out_nothing_is_said_of_following_it() {
        setContent(loaded)

        assertThat(shown("Subscribe")).isFalse()
        assertThat(shown("You hear of")).isFalse()
    }

    private val commentable = loaded.copy(discussion = read.copy(id = "D1"))

    @Test
    fun a_reference_typed_in_the_comment_lists_what_it_may_mean_and_picking_one_writes_its_number() {
        suggestionsShown = fr.arthurbrugiere.forgeline.issue.ReferenceOffer(listOf(fr.arthurbrugiere.forgeline.core.testing.issueSummary(12, "Paging breaks")), field = "")
        setContent(commentable)

        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performTextInput("See #")
        composeRule.onNodeWithContentDescription("Issue #12, Paging breaks").performClick()
        composeRule.onNodeWithContentDescription("Send comment").performClick()

        // Typed in the comment's own field, as a reference with nothing after its mark yet.
        assertThat(events).containsAtLeast("typed::", "send:discussion:See #12 ").inOrder()
    }

    @Test
    fun the_reader_s_saved_replies_go_in_the_comment_or_in_a_reply() {
        setContent(commentable.copy(offersSavedReplies = true, savedReplies = fr.arthurbrugiere.forgeline.repo.Loadable.Loaded(listOf(fr.arthurbrugiere.forgeline.core.model.SavedReply("Thanks", "Thanks for this!")))))

        reach("Comment on this discussion")
        composeRule.onNodeWithContentDescription("Saved replies").performClick()
        composeRule.onNodeWithText("Thanks").performClick()
        composeRule.onNodeWithContentDescription("Send comment").performClick()

        assertThat(events).containsAtLeast("saved-replies", "send:discussion:Thanks for this!").inOrder()
    }

    @Test
    fun signed_out_or_on_a_forge_without_them_no_saved_reply_is_offered() {
        setContent(commentable)

        reach("Comment on this discussion")
        assertThat(composeRule.onAllNodes(hasContentDescription("Saved replies")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun what_was_written_is_previewed_as_it_will_read() {
        setContent(commentable)

        reach("Comment on this discussion")
        composeRule.onNodeWithText("Comment on this discussion").performTextInput("**Found it**")
        composeRule.onNodeWithContentDescription("Preview").performClick()

        // Read as it will be, in place of its field.
        assertThat(composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(fr.arthurbrugiere.forgeline.issue.WRITING_PREVIEW_TAG)).fetchSemanticsNodes()).hasSize(1)
        assertThat(composeRule.onAllNodes(hasText("Comment on this discussion")).fetchSemanticsNodes()).isEmpty()
    }
}
