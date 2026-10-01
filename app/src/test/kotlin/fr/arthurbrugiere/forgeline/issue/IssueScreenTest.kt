package fr.arthurbrugiere.forgeline.issue

import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import org.junit.Rule
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import androidx.compose.ui.test.isHeading
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class IssueScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val ref = IssueRef(RepoId("octo", "repo"), 7)
    private val at = Instant.parse("2026-09-26T09:00:00Z")

    private fun setContent(state: IssueUiState, canComment: Boolean = true) {
        shown.value = state
        composeRule.setContent {
            IssueScreen(
                state = shown.value,
                canComment = canComment,
                onDraftChange = { events += "draft:$it" }, onSendComment = { events += "send" }, onSignIn = { events += "signin" },
                onCommentNoticeShown = { events += "noticed" },
                onBack = {}, onRefresh = { events += "refresh" }, onLoadMore = { events += "more" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenUser = { events += "user:$it" },
                onOpenInBrowser = { events += "browser:$it" },
                onLinkClick = { events += "link:$it" },
                onErrorShown = {},
                nowMillis = at.toEpochMilli(),
            )
        }
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun shows_the_issue_and_its_comments() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start"), listOf(comment(1, "Same here", login = "hubot"))))

        // The header field names the conversation, its number after the title.
        composeRule.onNodeWithText("Crash on start - #7").assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        waitFor("Same here")
        composeRule.onNodeWithText("hubot").assertIsDisplayed()
    }

    @Test
    fun a_merged_pull_request_shows_branches_and_stats() {
        val pr = issueDetails(ref, "Fix it", IssueState.MERGED)
            .copy(pullRequest = PullRequestInfo(false, true, "main", "fix/it", 12, 3, 2, 1))
        setContent(IssueUiState(ref, pr))

        composeRule.onNodeWithText("Merged").assertIsDisplayed()
        composeRule.onNodeWithText("main ← fix/it").assertIsDisplayed()
        composeRule.onNodeWithText("+12 −3 · 2 files · 1 commit").assertIsDisplayed()
    }

    @Test
    fun timeline_events_read_as_sentences() {
        val items = listOf(
            TimelineItem.Review(1, ForgeUser("rev", null, null), ReviewState.APPROVED, null, at),
            TimelineItem.StateChanged(StateChange.CLOSED, ForgeUser("maint", null, null), "completed", at),
            TimelineItem.Renamed("Old", "New", ForgeUser("maint", null, null), at),
        )
        setContent(IssueUiState(ref, issueDetails(ref), items))

        composeRule.onNodeWithText("rev approved these changes", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("maint closed this as completed", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("maint changed the title from “Old” to “New”").assertIsDisplayed()
    }

    @Test
    fun the_top_bar_names_the_conversation_before_its_number() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start")))

        composeRule.onNodeWithText("Crash on start - #7").assertIsDisplayed()
    }

    @Test
    fun the_repository_it_belongs_to_opens_from_the_header() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start")))

        composeRule.onNodeWithText("octo/repo").performClick()

        assertThat(events).containsExactly("repo:octo/repo")
    }

    @Test
    fun before_the_conversation_loads_the_top_bar_shows_its_number() {
        setContent(IssueUiState(ref))

        composeRule.onNodeWithText("#7").assertIsDisplayed()
    }

    @Test
    fun label_events_show_the_label() {
        // Regression: the label chip sat next to a full-width line and was squeezed to zero width.
        val items = listOf(
            TimelineItem.Labeled(true, Label("bug", "d73a4a"), ForgeUser("maint", null, null), at),
            TimelineItem.Labeled(false, Label("wontfix", "ffffff"), ForgeUser("maint", null, null), at),
        )
        setContent(IssueUiState(ref, issueDetails(ref), items))

        waitFor("maint removed")
        for ((line, label) in listOf("maint added" to "bug", "maint removed" to "wontfix")) {
            val text = composeRule.onNodeWithText(line).fetchSemanticsNode().boundsInRoot
            val chip = composeRule.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertThat(chip.width).isGreaterThan(0f)
            assertThat(chip.left).isAtLeast(text.right)
        }
    }

    @Test
    fun reactions_are_shown_with_their_counts() {
        setContent(IssueUiState(ref, issueDetails(ref).copy(reactions = mapOf(Reaction.THUMBS_UP to 3, Reaction.ROCKET to 1))))

        composeRule.onNodeWithText("👍 3").assertIsDisplayed()
        composeRule.onNodeWithText("🚀 1").assertIsDisplayed()
    }

    @Test
    fun cross_references_and_authors_are_navigable() {
        val source = IssueRef(RepoId("octo", "other"), 42)
        val items = listOf(TimelineItem.CrossReferenced(source, "Related bug", false, ForgeUser("bob", null, null), at))
        setContent(IssueUiState(ref, issueDetails(ref), items))

        composeRule.onNodeWithText("bob mentioned this in #42 Related bug").performClick()
        composeRule.onNodeWithText("octocat opened this", substring = true).performClick()

        assertThat(events).containsExactly("issue:octo/other#42", "user:octocat").inOrder()
    }

    @Test
    fun long_conversations_load_more_on_demand() {
        setContent(IssueUiState(ref, issueDetails(ref), nextPage = 2))

        composeRule.onNodeWithText("Load more").performClick()

        assertThat(events).containsExactly("more")
    }

    @Test
    fun without_anything_to_show_an_error_offers_a_retry() {
        setContent(IssueUiState(ref, error = ForgeError.Network))

        composeRule.onNodeWithText("Couldn't open this conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertThat(events).containsExactly("refresh")
    }

    @Test
    fun the_header_meets_touch_targets_and_names_its_hero() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start"), listOf(comment(1, "Same here", login = "hubot"))))
        waitFor("Same here")

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNode(hasText("Crash on start", substring = true) and isHeading()).assertIsDisplayed()
    }

    @Test
    fun open_in_browser_names_the_issues_forge() {
        val onCodeberg = IssueRef(RepoId("octo", "repo", ForgeInstance.Codeberg), 7)
        setContent(IssueUiState(onCodeberg, issueDetails(onCodeberg, "Crash on start")))

        composeRule.onNodeWithContentDescription("Open on Codeberg").assertIsDisplayed()
    }

    private val opened = IssueUiState(ref, issueDetails(ref, "Crash on start"), listOf(comment(1, "Same here", login = "hubot")))

    /** The comment box closes the conversation: scroll down to it. */
    private fun reach(matcher: SemanticsMatcher) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    @Test
    fun a_comment_can_be_written_at_the_end_of_the_conversation() {
        setContent(opened)

        reach(hasSetTextAction())
        composeRule.onNode(hasSetTextAction()).performTextInput("Thanks")

        assertThat(events).contains("draft:Thanks")
    }

    @Test
    fun an_empty_comment_can_t_be_sent_and_a_written_one_can() {
        setContent(opened)
        reach(hasText("Comment"))
        composeRule.onNodeWithText("Comment").assertIsNotEnabled()

        setDraft("Thanks, fixed!")
        reach(hasText("Comment"))
        composeRule.onNodeWithText("Comment").assertIsEnabled().performClick()

        assertThat(events).contains("send")
    }

    private val shown = mutableStateOf(opened)

    private fun setDraft(text: String) {
        shown.value = opened.copy(draft = text)
    }

    @Test
    fun while_a_comment_is_sent_it_can_t_be_sent_again() {
        setContent(opened.copy(draft = "Thanks", isCommenting = true))

        reach(hasText("Sending"))
        composeRule.onNodeWithText("Sending").assertIsNotEnabled()
    }

    @Test
    fun a_comment_that_wasn_t_sent_stays_written_and_says_why() {
        setContent(opened.copy(draft = "Thanks", commentError = ForgeError.Http(403, "locked")))

        reach(hasText("You can't comment here", substring = true))
        composeRule.onNodeWithText("Thanks").assertExists()
        composeRule.onNodeWithText("Comment").assertIsEnabled()
    }

    @Test
    fun signed_out_of_the_forge_the_box_asks_to_sign_in_there() {
        setContent(opened, canComment = false)

        reach(hasText("Sign in to GitHub to comment."))
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).contains("signin")
    }

    @Test
    fun nothing_can_be_written_before_the_conversation_has_loaded() {
        setContent(IssueUiState(ref))

        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("Sign in to GitHub to comment.").assertDoesNotExist()
    }

    @Test
    fun a_comment_posted_past_what_is_loaded_is_announced() {
        setContent(opened.copy(nextPage = 2, commentPostedOutOfSight = true))

        waitFor("Comment posted")
        composeRule.runOnIdle { assertThat(events).contains("noticed") }
    }
}
