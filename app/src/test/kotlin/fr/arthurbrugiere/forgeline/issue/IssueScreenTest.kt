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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onRoot
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
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
                onDraftChange = { events += "draft:$it" }, onSendComment = { events += "send" }, onToggleOpen = { events += "toggle" }, onSignIn = { events += "signin" },
                onCommentNoticeShown = { events += "noticed" },
                onBack = {}, onRefresh = { events += "refresh" }, onLoadMore = { events += "more" },
                onOpenIssue = { events += "issue:${it.repo.fullName}#${it.number}" },
                onOpenRepo = { events += "repo:${it.fullName}" },
                onOpenUser = { events += "user:$it" },
                onOpenInBrowser = { events += "browser:$it" },
                onLinkClick = { events += "link:$it" },
                onErrorShown = {},
                nowMillis = at.toEpochMilli(),
                onToEnd = { events += "end" },
                onScrolled = {
                    events += "scrolled"
                    shown.value = shown.value.copy(scrollTo = null)
                },
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
        // Read signed out. Signed in, each can be tapped and says what it is: see CommentActionsScreenTest.
        setContent(IssueUiState(ref, issueDetails(ref).copy(reactions = mapOf(Reaction.THUMBS_UP to 3, Reaction.ROCKET to 1))), canComment = false)

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

    private val closable = opened.copy(canChangeState = true)

    @Test
    fun whoever_may_close_an_open_issue_finds_it_beside_the_comment_s_action() {
        setContent(closable)

        reach(hasText("Close issue"))
        composeRule.onNodeWithText("Close issue").performClick()

        assertThat(events).containsExactly("toggle")
    }

    @Test
    fun a_closed_issue_offers_to_reopen() {
        setContent(closable.copy(issue = issueDetails(ref, "Crash on start", IssueState.CLOSED)))

        reach(hasText("Reopen issue"))
        composeRule.onNodeWithText("Close issue").assertDoesNotExist()
    }

    @Test
    fun a_pull_request_is_named_as_one() {
        val pull = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1)
        setContent(closable.copy(issue = issueDetails(ref, "Fix it").copy(pullRequest = pull)))
        reach(hasText("Close pull request"))

        shown.value = closable.copy(issue = issueDetails(ref, "Fix it", IssueState.CLOSED).copy(pullRequest = pull))
        reach(hasText("Reopen pull request"))
    }

    @Test
    fun a_merged_pull_request_offers_neither() {
        val merged = issueDetails(ref, "Fix it", IssueState.MERGED).copy(pullRequest = PullRequestInfo(false, true, "main", "fix/it", 12, 3, 2, 1))
        setContent(closable.copy(issue = merged))

        reach(hasText("Comment"))
        composeRule.onNodeWithText("Close pull request").assertDoesNotExist()
        composeRule.onNodeWithText("Reopen pull request").assertDoesNotExist()
    }

    @Test
    fun whoever_may_not_close_it_is_offered_nothing() {
        setContent(opened)

        reach(hasText("Comment"))
        composeRule.onNodeWithText("Close issue").assertDoesNotExist()
    }

    @Test
    fun signed_out_nothing_can_be_closed() {
        // The permission was known, then the account was signed out.
        setContent(closable, canComment = false)

        reach(hasText("Sign in to GitHub to comment."))
        composeRule.onNodeWithText("Close issue").assertDoesNotExist()
    }

    @Test
    fun while_it_closes_the_action_says_so_and_waits() {
        setContent(closable.copy(isChangingState = true))

        reach(hasText("Closing"))
        composeRule.onNodeWithText("Closing").assertIsNotEnabled()

        shown.value = closable.copy(issue = issueDetails(ref, "Crash on start", IssueState.CLOSED), isChangingState = true)
        reach(hasText("Reopening"))
    }

    @Test
    fun a_refused_state_change_says_who_can() {
        setContent(closable.copy(stateError = ForgeError.Http(403, "no")))

        reach(hasText("Only whoever opened it and the repository's maintainers can.", substring = true))
        composeRule.onNodeWithText("Close issue").assertIsEnabled()
    }

    @Test
    fun the_close_action_is_large_enough_to_press() {
        setContent(closable)
        reach(hasText("Close issue"))

        composeRule.assertEveryTargetIsAtLeast48dp()
    }

    private val lockedIssue = opened.copy(issue = issueDetails(ref, "Crash on start", IssueState.CLOSED).copy(isLocked = true))

    @Test
    fun a_locked_conversation_says_so_and_takes_no_comment_from_outsiders() {
        setContent(lockedIssue)

        composeRule.onNodeWithText("Locked").assertIsDisplayed()
        reach(hasText("This conversation is locked.", substring = true))
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("Comment").assertDoesNotExist()
    }

    @Test
    fun whoever_can_write_to_the_repository_still_comments_on_a_locked_conversation() {
        setContent(lockedIssue.copy(access = RepoAccess.WRITE))

        reach(hasSetTextAction())
        composeRule.onNodeWithText("This conversation is locked.", substring = true).assertDoesNotExist()
    }

    @Test
    fun triaging_alone_doesn_t_open_a_locked_conversation() {
        setContent(lockedIssue.copy(access = RepoAccess.TRIAGE))

        reach(hasText("This conversation is locked.", substring = true))
    }

    @Test
    fun who_it_is_assigned_to_and_its_milestone_show_under_the_title() {
        val triaged = issueDetails(ref, "Crash on start").copy(
            assignees = listOf(ForgeUser("octocat", null, null), ForgeUser("hubot", null, null)), milestone = Milestone(4, "2026.10"),
        )
        setContent(opened.copy(issue = triaged))

        composeRule.onNodeWithText("Assigned to octocat, hubot").assertIsDisplayed()
        composeRule.onNodeWithText("Milestone: 2026.10").assertIsDisplayed()
    }

    @Test
    fun an_issue_nobody_triaged_says_nothing_of_it() {
        setContent(opened)

        composeRule.onNodeWithText("Assigned to", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Milestone:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Locked").assertDoesNotExist()
    }

    @Test
    fun what_was_done_to_the_conversation_reads_as_sentences() {
        fun event(event: ConversationEvent, subject: String? = null) = TimelineItem.Event(event, ForgeUser("maintainer", null, null), subject, at)
        setContent(
            opened.copy(
                items = listOf(
                    event(ConversationEvent.LOCKED), event(ConversationEvent.CONVERTED_TO_DISCUSSION), event(ConversationEvent.UNLOCKED),
                    event(ConversationEvent.PINNED), event(ConversationEvent.UNPINNED),
                    event(ConversationEvent.ASSIGNED, "octocat"), event(ConversationEvent.ASSIGNED, "maintainer"),
                    event(ConversationEvent.UNASSIGNED, "octocat"), event(ConversationEvent.UNASSIGNED, "Maintainer"),
                    event(ConversationEvent.MILESTONED, "2026.10"), event(ConversationEvent.DEMILESTONED, "2026.10"),
                    event(ConversationEvent.TRANSFERRED),
                ),
            ),
        )

        listOf(
            "maintainer locked this and limited it to collaborators",
            "maintainer converted this to a discussion",
            "maintainer unlocked this",
            "maintainer pinned this",
            "maintainer unpinned this",
            "maintainer assigned octocat",
            "maintainer took this on",
            "maintainer unassigned octocat",
            "maintainer stepped away from this",
            "maintainer added this to the 2026.10 milestone",
            "maintainer removed this from the 2026.10 milestone",
            "maintainer transferred this from another repository",
        ).forEach { sentence -> reach(hasText(sentence, substring = true)) }
    }

    @Test
    fun the_header_offers_to_manage_an_issue_to_whoever_is_signed_in() {
        setContent(opened)
        composeRule.onNodeWithContentDescription("Manage").assertIsDisplayed()
    }

    @Test
    fun signed_out_there_is_nothing_to_manage() {
        setContent(opened, canComment = false)

        composeRule.onNodeWithContentDescription("Manage").assertDoesNotExist()
    }

    @Test
    fun a_pull_request_is_only_managed_by_whoever_has_a_role_in_its_repository() {
        val pull = issueDetails(ref, "Fix it").copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))
        setContent(opened.copy(issue = pull))
        composeRule.onNodeWithContentDescription("Manage").assertDoesNotExist()

        shown.value = opened.copy(issue = pull, access = RepoAccess.TRIAGE, supported = setOf(ConversationAction.LABELS))
        composeRule.onNodeWithContentDescription("Manage").assertIsDisplayed()
    }

    @Test
    fun the_day_it_is_due_shows_under_the_title() {
        setContent(opened.copy(issue = issueDetails(ref, "Crash on start").copy(dueDate = java.time.LocalDate.parse("2026-10-10"))))

        composeRule.onNodeWithText("Due Oct 10, 2026").assertIsDisplayed()
    }

    @Test
    fun deadlines_time_and_dependencies_read_as_sentences() {
        fun event(event: ConversationEvent, subject: String? = null) = TimelineItem.Event(event, ForgeUser("maintainer", null, null), subject, at)
        setContent(
            opened.copy(
                items = listOf(
                    event(ConversationEvent.DEADLINE_SET, "2026-10-10"), event(ConversationEvent.DEADLINE_REMOVED),
                    event(ConversationEvent.TRACKING_STARTED), event(ConversationEvent.TRACKING_STOPPED), event(ConversationEvent.TIME_ADDED),
                    event(ConversationEvent.DEPENDENCY_ADDED, "#3 Schema first"), event(ConversationEvent.DEPENDENCY_REMOVED, "other/docs#106 Website copy"),
                ),
            ),
        )

        listOf(
            "maintainer set the due date to Oct 10, 2026",
            "maintainer removed the due date",
            "maintainer started working on this",
            "maintainer stopped working on this",
            "maintainer recorded time spent",
            "maintainer made this depend on #3 Schema first",
            "maintainer removed the dependency on other/docs#106 Website copy",
        ).forEach { sentence -> reach(hasText(sentence, substring = true)) }
    }

    /**
     * Lets [millis] pass frame by frame and no faster than a real clock, the main thread taking what other threads
     * have finished in between, as on a device. Comments are read off the main thread: a test clock that runs ahead
     * is done holding the list in place before they have landed, which no device does.
     */
    private fun letTimePass(millis: Long) {
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false
        repeat((millis / 16).toInt()) {
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
            composeRule.waitForIdle()
        }
        composeRule.mainClock.autoAdvance = autoAdvance
    }

    private fun longConversation(nextPage: Int? = null) =
        IssueUiState(ref, issueDetails(ref, "Crash on start"), (1..40L).map { comment(it, "Comment number $it") }, nextPage = nextPage)

    @Test
    fun a_conversation_that_fits_the_screen_offers_no_way_across_it() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start")))

        composeRule.onNodeWithContentDescription("Go to the top").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Go to the latest").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi", fontScale = 2.0f)
    fun with_the_readers_turn_in_sight_nothing_floats_over_it() {
        // Regression: a conversation just longer than the screen offered its end from on top of "Comment".
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start"), listOf(comment(1, "Same here", login = "hubot"))))
        waitFor("Same here")

        composeRule.onNodeWithText("Comment").assertExists()
        composeRule.onNodeWithContentDescription("Go to the latest").assertDoesNotExist()
    }

    @Test
    fun at_the_top_of_a_long_conversation_only_the_end_is_offered() {
        setContent(longConversation())

        composeRule.onNodeWithContentDescription("Go to the top").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Go to the latest").performClick()

        // The rest may still be to load: the view model says when the list can go there.
        assertThat(events).containsExactly("end")
    }

    @Test
    fun asked_to_the_end_the_list_lands_on_the_readers_turn_and_offers_the_way_back_up() {
        setContent(longConversation())

        shown.value = shown.value.copy(scrollTo = ScrollTarget.End)

        letTimePass(1_500)
        composeRule.onNodeWithText("Add a comment").assertIsDisplayed()
        // Regression: comments laid out after the jump pushed the end away, and the list stopped short of it.
        composeRule.onNodeWithContentDescription("Go to the latest").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Go to the top").performClick()

        composeRule.onNodeWithText("Crash on start - #7").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Go to the top").assertDoesNotExist()
    }

    @Test
    fun asked_to_an_entry_the_list_opens_on_it_with_both_ends_offered() {
        // The comment above the one asked for is a long one, as they are where it matters.
        val long = (1..30).joinToString("\n\n") { "Paragraph $it of a long remark." }
        setContent(longConversation().let { state -> state.copy(items = state.items.mapIndexed { i, item -> if (i == 18) comment(19, long) else item }) })

        composeRule.mainClock.autoAdvance = false
        shown.value = shown.value.copy(scrollTo = ScrollTarget.Item(19))

        letTimePass(2_000)
        composeRule.mainClock.autoAdvance = true
        waitFor("Comment number 20")
        composeRule.onNodeWithText("Comment number 20").assertIsDisplayed()
        // Regression: the comment above it grew once its Markdown was read, and the list ended up in the middle of
        // that one instead (seen on a phone, on a conversation of 237 comments, 2026-10-04).
        val top = composeRule.onNodeWithText("Comment number 20").fetchSemanticsNode().boundsInRoot.top
        val height = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        assertThat(top).isLessThan(height / 8)
        // The one before it is above the screen, not on it.
        composeRule.onNodeWithText("Paragraph 1 of a long remark.").assertIsNotDisplayed()
        composeRule.onNodeWithContentDescription("Go to the top").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Go to the latest").assertIsDisplayed()
    }

    @Test
    fun at_the_end_of_what_is_loaded_the_rest_is_still_one_tap_away() {
        setContent(longConversation(nextPage = 2))
        shown.value = shown.value.copy(scrollTo = ScrollTarget.End)
        letTimePass(1_500)
        composeRule.onNodeWithText("Add a comment").assertIsDisplayed()
        events.clear()

        composeRule.onNodeWithContentDescription("Go to the latest").performClick()

        assertThat(events).containsExactly("end")
    }

    @Test
    fun while_the_rest_loads_the_way_to_the_end_waits() {
        setContent(longConversation(nextPage = 2).copy(isLoadingMore = true))

        composeRule.onNodeWithContentDescription("Go to the latest").performClick()

        assertThat(events).isEmpty()
    }

    @Test
    fun the_list_holds_its_place_only_until_the_reader_moves_it() {
        setContent(longConversation())
        composeRule.mainClock.autoAdvance = false

        shown.value = shown.value.copy(scrollTo = ScrollTarget.Item(19))
        composeRule.mainClock.advanceTimeBy(100)
        assertThat(events).doesNotContain("scrolled")
        // A finger on the list, dragging: it is the reader's now, well before the list would have let go by itself.
        composeRule.onNode(androidx.compose.ui.test.hasScrollAction()).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 300f))
        }
        composeRule.mainClock.advanceTimeBy(100)

        assertThat(events).contains("scrolled")
    }

    @Test
    fun going_to_the_top_wins_over_a_place_still_being_held() {
        setContent(longConversation())
        composeRule.mainClock.autoAdvance = false
        shown.value = shown.value.copy(scrollTo = ScrollTarget.Item(19))
        letTimePass(100)

        // Asked for while the list still holds comment 20 in place: it must not be pulled back there.
        composeRule.onNodeWithContentDescription("Go to the top").performClick()
        letTimePass(1_500)

        composeRule.onNodeWithText("Crash on start - #7").assertIsDisplayed()
    }
}
