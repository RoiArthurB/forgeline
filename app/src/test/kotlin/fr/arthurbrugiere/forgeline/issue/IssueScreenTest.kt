package fr.arthurbrugiere.forgeline.issue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.HideReason
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Label
import fr.arthurbrugiere.forgeline.core.model.Milestone
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
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

    private fun setContent(state: IssueUiState, canComment: Boolean = true, commentingAs: String? = null) {
        shown.value = state
        composeRule.setContent {
            CompositionLocalProvider(LocalCommentingAs provides commentingAs) { Screen(canComment) }
        }
    }

    /** What the reference being typed may mean, as the view-model would offer it; and each one typed. */
    private val offer = androidx.compose.runtime.mutableStateOf(ReferenceOffer())
    private val typedReferences = mutableListOf<TypedReference?>()

    @Composable
    private fun Screen(canComment: Boolean) {
        IssueScreen(
            state = shown.value,
            suggestions = offer.value,
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
            onToggleSubscribed = { events += "subscription" },
            comments = CommentActions(
                onSetHidden = { id, reason -> events += "hide:$id:$reason" },
                onAddSubIssue = { events += "sub+:$it" },
                onReferenceTyped = { typedReferences += it },
                onRemoveSubIssue = { events += "sub-:${it.number}" },
                onSetProjectStatus = { item, status -> events += "project:${item.title}:${status?.name}" },
                onLoadSavedReplies = { events += "load-replies" },
                onUseSavedReply = { events += "reply:${it.title}" },
            ),
            onScrolled = {
                events += "scrolled"
                shown.value = shown.value.copy(scrollTo = null)
            },
        )
    }

    private fun waitFor(text: String) =
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun shares_the_address_of_the_conversation_under_its_title() {
        setContent(IssueUiState(ref, issueDetails(ref, "Crash on start")))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()).isEqualTo("https://github.com/octo/repo/issues/7" to "Crash on start")
    }

    @Test
    fun a_long_press_on_share_copies_the_address_of_a_pull_request() {
        val pr = issueDetails(ref, "Fix it").copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))
        setContent(IssueUiState(ref, pr))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performTouchInput { longClick() }

        assertThat(fr.arthurbrugiere.forgeline.ui.copiedText()).isEqualTo("https://github.com/octo/repo/pull/7")
    }

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
        // One unboxed line: where it went, then how much it changed; with no panel to count them, files and commits too.
        composeRule.onNodeWithText("main ← fix/it  +12 −3 · 2 files · 1 commit").assertIsDisplayed()
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
    fun a_conversation_closed_by_a_commit_shows_the_commit_s_first_line_and_opens_it() {
        // Regression: only "closed this as completed" showed, never the commit that did it.
        val url = "https://github.com/gama-platform/gama/commit/c2a01032c89c63026099bacb814e8786fce8e9a8"
        val commit = ClosingCommit("c2a01032c89c63026099bacb814e8786fce8e9a8", "Detect duplicate default facet definitions\n\nFixes #1118", url)
        val items = listOf(TimelineItem.StateChanged(StateChange.CLOSED, ForgeUser("AlexisDrogoul", null, null), "completed", at, commit))
        setContent(IssueUiState(ref, issueDetails(ref), items))

        composeRule.onNodeWithText("AlexisDrogoul closed this as completed", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("c2a0103  Detect duplicate default facet definitions").performClick()

        assertThat(events).containsExactly("link:$url")
    }

    @Test
    fun a_closing_commit_whose_message_couldn_t_be_read_still_shows_its_name() {
        val commit = ClosingCommit("abc1234def", null, "https://github.com/other/repo/commit/abc1234def")
        setContent(IssueUiState(ref, issueDetails(ref), listOf(TimelineItem.StateChanged(StateChange.CLOSED, null, null, at, commit))))

        composeRule.onNodeWithText("abc1234").assertIsDisplayed()
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

        // Another repository's, it says which, and when.
        val time = fr.arthurbrugiere.forgeline.ui.relative(at, at.toEpochMilli())
        composeRule.onNodeWithText("bob mentioned this in octo/other#42 Related bug $time").performClick()
        composeRule.onNodeWithText("octocat opened this", substring = true).performClick()

        assertThat(events).containsExactly("issue:octo/other#42", "user:octocat").inOrder()
    }

    @Test
    fun who_opened_it_and_who_wrote_each_comment_are_said_to_open() {
        // Regression: TalkBack heard an author's name as plain text, not as something that opens their profile.
        setContent(IssueUiState(ref, issueDetails(ref), listOf(TimelineItem.Comment(1, ForgeUser("hubot", null, null), "Same here.", at, emptyMap(), nodeId = "IC_1"))))

        composeRule.onNodeWithText("octocat opened this", substring = true).assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        composeRule.onNodeWithText("hubot").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
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
        reach(hasContentDescription("Comment"))
        composeRule.onNodeWithContentDescription("Comment").assertIsNotEnabled()

        setDraft("Thanks, fixed!")
        reach(hasContentDescription("Comment"))
        composeRule.onNodeWithContentDescription("Comment").assertIsEnabled().performClick()

        assertThat(events).contains("send")
    }

    private val shown = mutableStateOf(opened)

    private fun setDraft(text: String) {
        shown.value = opened.copy(draft = text)
    }

    @Test
    fun while_a_comment_is_sent_it_can_t_be_sent_again() {
        setContent(opened.copy(draft = "Thanks", isCommenting = true))

        reach(hasContentDescription("Sending"))
        composeRule.onNodeWithContentDescription("Sending").assertIsNotEnabled()
    }

    @Test
    fun a_comment_that_wasn_t_sent_stays_written_and_says_why() {
        setContent(opened.copy(draft = "Thanks", commentError = ForgeError.Http(403, "locked")))

        reach(hasText("You can't comment here", substring = true))
        composeRule.onNodeWithText("Thanks").assertExists()
        composeRule.onNodeWithContentDescription("Comment").assertIsEnabled()
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

        reach(hasContentDescription("Comment"))
        composeRule.onNodeWithText("Close pull request").assertDoesNotExist()
        composeRule.onNodeWithText("Reopen pull request").assertDoesNotExist()
    }

    @Test
    fun whoever_may_not_close_it_is_offered_nothing() {
        setContent(opened)

        reach(hasContentDescription("Comment"))
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

    @Test
    fun whether_the_reader_hears_of_every_reply_is_said_under_their_turn_and_changed_there() {
        setContent(opened.copy(subscribed = false))

        reach(hasText("You hear of this only when you are mentioned."))
        composeRule.onNodeWithText("Subscribe").performClick()
        composeRule.assertEveryTargetIsAtLeast48dp()

        shown.value = opened.copy(subscribed = true)
        reach(hasText("You hear of every reply here."))
        composeRule.onNodeWithText("Unsubscribe").performClick()

        assertThat(events).containsExactly("subscription", "subscription")
    }

    @Test
    fun a_subscription_being_changed_waits_and_one_that_didn_t_change_says_so() {
        setContent(opened.copy(subscribed = true, isSubscribing = true))
        reach(hasText("Unsubscribe"))
        composeRule.onNodeWithText("Unsubscribe").assertIsNotEnabled()

        shown.value = opened.copy(subscribed = true, subscriptionError = ForgeError.Network)
        reach(hasText("Nothing changed. Try again."))
    }

    @Test
    fun until_the_forge_says_nothing_is_offered_about_subscribing() {
        setContent(opened)

        reach(hasContentDescription("Comment"))
        composeRule.onNodeWithText("Subscribe").assertDoesNotExist()
    }

    private val lockedIssue = opened.copy(issue = issueDetails(ref, "Crash on start", IssueState.CLOSED).copy(isLocked = true))

    @Test
    fun a_locked_conversation_says_so_and_takes_no_comment_from_outsiders() {
        setContent(lockedIssue)

        composeRule.onNodeWithText("Locked").assertIsDisplayed()
        reach(hasText("This conversation is locked.", substring = true))
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Comment").assertDoesNotExist()
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
        composeRule.onNodeWithText("Manage").assertIsDisplayed()
    }

    @Test
    fun signed_out_there_is_nothing_to_manage() {
        setContent(opened, canComment = false)

        composeRule.onNodeWithText("Manage").assertDoesNotExist()
    }

    @Test
    fun a_pull_request_is_only_managed_by_whoever_has_a_role_in_its_repository() {
        val pull = issueDetails(ref, "Fix it").copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))
        setContent(opened.copy(issue = pull))
        composeRule.onNodeWithText("Manage").assertDoesNotExist()

        shown.value = opened.copy(issue = pull, access = RepoAccess.TRIAGE, supported = setOf(ConversationAction.LABELS))
        composeRule.onNodeWithText("Manage").assertIsDisplayed()
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

    @Test
    fun every_triage_event_reads_as_a_sentence_and_one_the_forge_left_unnamed_reads_without_it() {
        fun event(event: ConversationEvent, subject: String? = null) = TimelineItem.Event(event, ForgeUser("maintainer", null, null), subject, at)
        val sentences = listOf(
            event(ConversationEvent.LOCKED, "too heated") to "maintainer locked this as too heated and limited it to collaborators",
            event(ConversationEvent.LOCKED, "something new") to "maintainer locked this and limited it to collaborators",
            event(ConversationEvent.REVIEW_REQUESTED, "octocat") to "maintainer asked octocat for a review",
            event(ConversationEvent.REVIEW_REQUEST_REMOVED, "code reviewers") to "maintainer no longer asks code reviewers for a review",
            event(ConversationEvent.REVIEW_DISMISSED, "Outdated") to "maintainer dismissed a review: “Outdated”",
            event(ConversationEvent.REVIEW_DISMISSED) to "maintainer dismissed a review",
            event(ConversationEvent.PUSHED, "3") to "maintainer pushed 3 commits",
            event(ConversationEvent.PUSHED, "1") to "maintainer pushed 1 commit",
            event(ConversationEvent.PUSHED) to "maintainer pushed commits",
            event(ConversationEvent.FORCE_PUSHED, "d1d25e7") to "maintainer force-pushed the branch to d1d25e7",
            event(ConversationEvent.FORCE_PUSHED) to "maintainer force-pushed the branch",
            event(ConversationEvent.READY_FOR_REVIEW) to "maintainer marked this ready for review",
            event(ConversationEvent.CONVERTED_TO_DRAFT) to "maintainer made this a draft",
            event(ConversationEvent.MARKED_DUPLICATE, "#3 Crash") to "maintainer marked this a duplicate of #3 Crash",
            event(ConversationEvent.MARKED_DUPLICATE) to "maintainer marked this a duplicate",
            event(ConversationEvent.UNMARKED_DUPLICATE) to "maintainer no longer marks this a duplicate",
            event(ConversationEvent.DUPLICATE_ADDED, "#9 Same crash") to "maintainer marked #9 Same crash a duplicate of this",
            event(ConversationEvent.DUPLICATE_REMOVED, "#9 Same crash") to "maintainer no longer marks #9 Same crash a duplicate of this",
            event(ConversationEvent.ADDED_TO_PROJECT) to "maintainer added this to a project",
            event(ConversationEvent.ADDED_TO_PROJECT, "Roadmap") to "maintainer added this to the project Roadmap",
            event(ConversationEvent.REMOVED_FROM_PROJECT) to "maintainer removed this from a project",
            event(ConversationEvent.PROJECT_STATUS_CHANGED, "Todo") to "maintainer moved this to Todo on a project",
            event(ConversationEvent.PROJECT_STATUS_CHANGED) to "maintainer changed its status on a project",
            event(ConversationEvent.STATUS_CHANGED, "In progress") to "maintainer set the status to In progress",
            event(ConversationEvent.COMMENT_DELETED) to "maintainer deleted a comment",
            event(ConversationEvent.COMMENT_PINNED) to "maintainer pinned a comment",
            event(ConversationEvent.COMMENT_UNPINNED) to "maintainer unpinned a comment",
            event(ConversationEvent.DEPLOYED, "production") to "maintainer deployed this to production",
            event(ConversationEvent.DEPLOYED) to "maintainer deployed this",
            event(ConversationEvent.AUTO_MERGE_ENABLED) to "maintainer set this to merge once its checks pass",
            event(ConversationEvent.AUTO_MERGE_DISABLED) to "maintainer stopped this from merging by itself",
            event(ConversationEvent.ADDED_TO_MERGE_QUEUE) to "maintainer added this to the merge queue",
            event(ConversationEvent.REMOVED_FROM_MERGE_QUEUE) to "maintainer removed this from the merge queue",
            event(ConversationEvent.BASE_CHANGED, "release") to "maintainer changed the base branch to release",
            event(ConversationEvent.BASE_CHANGED) to "maintainer changed the base branch",
            event(ConversationEvent.LINKED, "#6 Fix it") to "maintainer linked #6 Fix it, which closes this once merged",
            event(ConversationEvent.UNLINKED, "#6 Fix it") to "maintainer unlinked #6 Fix it",
            event(ConversationEvent.USER_BLOCKED) to "maintainer blocked someone for what they said here",
            event(ConversationEvent.TYPE_SET, "Bug") to "maintainer set the type to Bug",
            event(ConversationEvent.TYPE_REMOVED, "Bug") to "maintainer removed the type Bug",
            event(ConversationEvent.TYPE_REMOVED) to "maintainer removed the type",
            event(ConversationEvent.SUB_ISSUE_ADDED, "#7 Seven") to "maintainer added the sub-issue #7 Seven",
            event(ConversationEvent.SUB_ISSUE_REMOVED, "#7 Seven") to "maintainer removed the sub-issue #7 Seven",
            event(ConversationEvent.PARENT_ADDED, "#3 Three") to "maintainer made this part of #3 Three",
            event(ConversationEvent.PARENT_REMOVED, "#3 Three") to "maintainer took this out of #3 Three",
            event(ConversationEvent.BLOCKING_ADDED, "#5 Five") to "maintainer made #5 Five depend on this",
            event(ConversationEvent.BLOCKING_REMOVED, "#5 Five") to "maintainer removed the dependency of #5 Five on this",
            event(ConversationEvent.TIME_ADDED, "1h 30m") to "maintainer recorded 1h 30m of time spent",
            event(ConversationEvent.TIME_REMOVED) to "maintainer took back recorded time",
            event(ConversationEvent.ESTIMATE_SET, "2h") to "maintainer set the time estimate to 2h",
            event(ConversationEvent.ESTIMATE_REMOVED) to "maintainer removed the time estimate",
            event(ConversationEvent.TRACKING_CANCELLED) to "maintainer stopped their timer without recording it",
            event(ConversationEvent.RELATED_ADDED, "#4 Four") to "maintainer marked this related to #4 Four",
            event(ConversationEvent.RELATED_REMOVED, "#4 Four") to "maintainer removed the relation to #4 Four",
            event(ConversationEvent.DESCRIPTION_CHANGED) to "maintainer changed the description",
            event(ConversationEvent.CONFIDENTIAL) to "maintainer made this confidential",
            event(ConversationEvent.NOT_CONFIDENTIAL) to "maintainer made this visible to everyone",
            event(ConversationEvent.WEIGHT_SET, "3") to "maintainer set the weight to 3",
            event(ConversationEvent.WEIGHT_REMOVED) to "maintainer removed the weight",
            event(ConversationEvent.ITERATION_SET, "Sprint 4") to "maintainer added this to the Sprint 4 iteration",
            event(ConversationEvent.ITERATION_REMOVED) to "maintainer removed this from an iteration",
            event(ConversationEvent.TRANSFERRED, "group/old#2") to "maintainer moved this here from group/old#2",
            event(ConversationEvent.MOVED_AWAY, "group/new#8") to "maintainer moved this to group/new#8",
            event(ConversationEvent.CLONED, "group/new#9") to "maintainer copied this to group/new#9",
            event(ConversationEvent.APPROVAL_REVOKED) to "maintainer took back their approval",
            event(ConversationEvent.THREADS_RESOLVED) to "maintainer resolved every thread",
        )
        // Every kind of event has its sentence here.
        assertThat(ConversationEvent.entries - sentences.map { it.first.event }.toSet() - OLDER_EVENTS).isEmpty()
        setContent(opened.copy(items = sentences.map { it.first }))

        sentences.forEach { (_, sentence) -> reach(hasText(sentence, substring = true)) }
    }

    @Test
    fun an_event_that_names_a_conversation_opens_it_and_a_referencing_commit_opens_its_page() {
        val target = IssueRef(RepoId("other", "docs"), 7)
        val sub = TimelineItem.Event(ConversationEvent.SUB_ISSUE_ADDED, ForgeUser("maintainer", null, null), "other/docs#7 Seven", at, target)
        val commit = TimelineItem.CommitReferenced(
            fr.arthurbrugiere.forgeline.core.model.ClosingCommit("675df2e1a797", "Fix the leak\n\nLonger text", "https://github.com/gama-platform/gama/commit/675df2e1a797"),
            ForgeUser("alexis", null, null),
            at,
        )
        setContent(opened.copy(items = listOf(sub, commit)))

        reach(hasText("maintainer added the sub-issue other/docs#7 Seven", substring = true))
        composeRule.onNodeWithText("maintainer added the sub-issue other/docs#7 Seven", substring = true).performClick()
        assertThat(events).contains("issue:other/docs#7")
        reach(hasText("alexis mentioned this in a commit", substring = true))
        reach(hasText("675df2e  Fix the leak"))
        composeRule.onNodeWithText("675df2e  Fix the leak").performClick()
        assertThat(events.joinToString()).contains("https://github.com/gama-platform/gama/commit/675df2e1a797")
    }

    @Test
    fun a_mention_gitlab_left_untitled_names_it_by_its_reference() {
        val gitlab = RepoId("group", "project", fr.arthurbrugiere.forgeline.core.model.ForgeInstance.GitLab)
        val who = ForgeUser("maintainer", null, null)
        setContent(
            opened.copy(
                ref = IssueRef(gitlab, 7),
                issue = issueDetails(IssueRef(gitlab, 7), "Crash on start"),
                items = listOf(
                    TimelineItem.CrossReferenced(IssueRef(gitlab, 3, isPullRequest = true), "", true, who, at),
                    TimelineItem.CrossReferenced(IssueRef(RepoId("group", "other", gitlab.forge), 9, isPullRequest = false), "", false, who, at),
                ),
            ),
        )

        reach(hasText("maintainer mentioned this in !3", substring = true))
        reach(hasText("maintainer mentioned this in group/other#9", substring = true))
    }

    @Test
    fun the_boards_it_is_on_show_its_column_and_whoever_may_moves_it_on_one() {
        val statuses = listOf(fr.arthurbrugiere.forgeline.core.model.ProjectStatus("a", "Todo"), fr.arthurbrugiere.forgeline.core.model.ProjectStatus("b", "Done"))
        val open = fr.arthurbrugiere.forgeline.core.model.ProjectItem("i1", "p1", "Roadmap", "Todo", "f1", statuses, canChange = true)
        val closed = fr.arthurbrugiere.forgeline.core.model.ProjectItem("i2", "p2", "Old board", null, "f2", statuses, canChange = false)
        setContent(opened.copy(projects = listOf(open, closed)))

        reach(hasText("Roadmap · Todo"))
        reach(hasText("Old board · No status"))
        // A board the reader may not move it on is only read.
        composeRule.onNodeWithText("Old board · No status").assertHasNoClickAction()
        composeRule.onNodeWithText("Roadmap · Todo").performClick()
        composeRule.onNodeWithText("Done").performClick()
        assertThat(events).contains("project:Roadmap:Done")
    }

    @Test
    fun the_boards_can_t_be_moved_on_signed_out_and_none_shows_nothing() {
        val board = fr.arthurbrugiere.forgeline.core.model.ProjectItem("i1", "p1", "Roadmap", "Todo", "f1", listOf(fr.arthurbrugiere.forgeline.core.model.ProjectStatus("a", "Todo")), canChange = true)
        setContent(opened.copy(projects = listOf(board)), canComment = false)
        reach(hasText("Roadmap · Todo"))
        composeRule.onNodeWithText("Roadmap · Todo").assertHasNoClickAction()

        shown.value = opened.copy(projects = emptyList())
        composeRule.onNodeWithText("Projects").assertDoesNotExist()
    }

    @Test
    fun a_project_event_names_its_board_where_github_told_it() {
        val who = ForgeUser("bot", null, null)
        setContent(
            opened.copy(
                items = listOf(
                    TimelineItem.Event(ConversationEvent.ADDED_TO_PROJECT, who, null, at, place = "GAMA 2025"),
                    TimelineItem.Event(ConversationEvent.PROJECT_STATUS_CHANGED, who, "Todo", at, place = "GAMA 2025"),
                    TimelineItem.Event(ConversationEvent.PROJECT_STATUS_CHANGED, who, null, at, place = "GAMA 2025"),
                ),
            ),
        )

        reach(hasText("bot added this to the project GAMA 2025", substring = true))
        reach(hasText("bot moved this to Todo on GAMA 2025", substring = true))
        reach(hasText("bot changed its status on GAMA 2025", substring = true))
    }

    @Test
    fun an_issue_closed_as_a_duplicate_says_so() {
        setContent(opened.copy(items = listOf(TimelineItem.StateChanged(StateChange.CLOSED, ForgeUser("maintainer", null, null), "duplicate", at))))

        reach(hasText("maintainer closed this as a duplicate", substring = true))
    }

    @Test
    fun a_deleted_branch_is_named_even_where_the_forge_does_not_name_it() {
        fun event(event: ConversationEvent, subject: String? = null) = TimelineItem.Event(event, ForgeUser("maintainer", null, null), subject, at)
        val pull = issueDetails(ref, "Fix it").copy(pullRequest = PullRequestInfo(false, true, "main", "fix/it", 12, 3, 2, 1))
        setContent(
            opened.copy(
                issue = pull,
                // GitHub names no branch: the pull request's own is meant. Forgejo names it.
                items = listOf(event(ConversationEvent.BRANCH_DELETED), event(ConversationEvent.BRANCH_RESTORED), event(ConversationEvent.BRANCH_DELETED, "fix/old-name")),
            ),
        )

        listOf(
            "maintainer deleted the branch fix/it",
            "maintainer restored the branch fix/it",
            "maintainer deleted the branch fix/old-name",
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

        composeRule.onNodeWithContentDescription("Comment").assertExists()
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

    @Test
    fun a_conversation_is_shared_by_the_address_its_own_forge_gives_it() {
        val codeberg = IssueRef(RepoId("alice", "tool", fr.arthurbrugiere.forgeline.core.model.ForgeInstance.Codeberg), 7)
        val pr = issueDetails(codeberg, "Fix it").copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))
        setContent(IssueUiState(codeberg, pr))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()

        assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()?.first).isEqualTo("https://codeberg.org/alice/tool/pulls/7")
    }

    @Test
    fun a_gitlab_merge_request_and_issue_of_one_number_are_shared_apart() {
        val gitlab = IssueRef(RepoId("group/sub", "tool", fr.arthurbrugiere.forgeline.core.model.ForgeInstance.GitLab), 7)
        val mr = issueDetails(gitlab, "Fix it").copy(pullRequest = PullRequestInfo(false, false, "main", "fix/it", 12, 3, 2, 1))
        setContent(IssueUiState(gitlab, mr))

        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()
        assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()?.first).isEqualTo("https://gitlab.com/group/sub/tool/-/merge_requests/7")

        shown.value = IssueUiState(gitlab, issueDetails(gitlab, "Crash"))
        composeRule.onNode(androidx.compose.ui.test.hasContentDescription("Share link")).performClick()
        assertThat(fr.arthurbrugiere.forgeline.ui.sharedLink()?.first).isEqualTo("https://gitlab.com/group/sub/tool/-/issues/7")
    }

    @Test
    fun an_author_the_forge_no_longer_knows_is_said_to_be_a_deleted_account() {
        setContent(IssueUiState(ref, issueDetails(ref, "Orphaned").copy(author = null)))

        // Regression: it read "ghost", GitHub's own login for such accounts, untranslated and unexplained.
        composeRule.onNode(androidx.compose.ui.test.hasText("A deleted account opened this", substring = true)).assertIsDisplayed()
    }

    @Test
    fun a_hidden_comment_shows_folded_saying_why_and_opens_when_asked() {
        val hidden = TimelineItem.Comment(1, ForgeUser("spammer", null, null), "Buy now!", at, emptyMap(), nodeId = "IC_1", hidden = HideReason.SPAM)
        setContent(IssueUiState(ref, issueDetails(ref), listOf(hidden)))

        composeRule.onNodeWithText("Buy now!").assertDoesNotExist()
        composeRule.onNodeWithText("spammer’s comment was hidden as spam").assertIsDisplayed()
        composeRule.onNodeWithText("Show").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Buy now!").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun a_comment_s_words_open_to_be_selected_from_its_menu() {
        val comment = TimelineItem.Comment(1, ForgeUser("someone", null, null), "Copy this part", at, emptyMap(), nodeId = "IC_1", canHide = true)
        setContent(IssueUiState(ref, issueDetails(ref), listOf(comment)))

        composeRule.onAllNodes(hasContentDescription("Comment options")).onLast().performClick()
        composeRule.onNodeWithText("Select text").performClick()

        // The sheet shows the words again, where a long press selects them.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Copy this part").fetchSemanticsNodes().size == 2 }
    }

    @Test
    fun with_two_accounts_on_the_forge_the_comment_box_says_whose_name_it_goes_out_under() {
        setContent(IssueUiState(ref, issueDetails(ref)), commentingAs = "work")

        shown.value = shown.value.copy(scrollTo = ScrollTarget.End)
        letTimePass(1_500)

        composeRule.onNodeWithText("Comment as @work").assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("Add a comment").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun who_may_hide_a_comment_hides_it_for_a_reason() {
        val comment = TimelineItem.Comment(1, ForgeUser("someone", null, null), "Off on a tangent", at, emptyMap(), nodeId = "IC_1", canHide = true)
        setContent(IssueUiState(ref, issueDetails(ref), listOf(comment)))

        // The comment's, after the conversation's own text.
        composeRule.onAllNodes(hasContentDescription("Comment options")).onLast().performClick()
        composeRule.onNodeWithText("Hide").performClick()
        composeRule.onNodeWithText("Off-topic").performClick()

        assertThat(events).contains("hide:1:OFF_TOPIC")
    }

    @Test
    fun the_pull_requests_that_will_close_an_issue_are_listed_at_its_end_and_open() {
        val fix = fr.arthurbrugiere.forgeline.core.model.LinkedIssue(IssueRef(ref.repo, 12, isPullRequest = true), "Fix the crash", IssueState.OPEN)
        setContent(IssueUiState(ref, issueDetails(ref), closingPulls = listOf(fix)))

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Fix the crash", substring = true))
        composeRule.onNodeWithText("Closed by merging", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("#12 Fix the crash", substring = true).performClick()

        assertThat(events).contains("issue:octo/repo#12")
    }

    @Test
    fun an_issue_says_what_it_is_part_of_and_lists_its_sub_issues_with_how_many_are_done() {
        val subs = fr.arthurbrugiere.forgeline.core.model.SubIssues(
            fr.arthurbrugiere.forgeline.core.model.LinkedIssue(IssueRef(ref.repo, 1, isPullRequest = false), "Epic", IssueState.OPEN),
            listOf(
                fr.arthurbrugiere.forgeline.core.model.LinkedIssue(IssueRef(ref.repo, 12, isPullRequest = false), "First piece", IssueState.CLOSED),
                fr.arthurbrugiere.forgeline.core.model.LinkedIssue(IssueRef(ref.repo, 13, isPullRequest = false), "Second piece", IssueState.OPEN),
            ),
        )
        setContent(IssueUiState(ref, issueDetails(ref), subIssues = subs, access = RepoAccess.TRIAGE))

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Second piece", substring = true))
        composeRule.onNodeWithText("Part of #1 Epic", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sub-issues · 1 of 2 done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Take #13 out of the sub-issues").performClick()
        // The field waits behind its button: it isn't one more box under every issue.
        composeRule.onNode(hasText("#number") and hasSetTextAction()).assertDoesNotExist()
        composeRule.onNodeWithText("Add a sub-issue").performClick()
        composeRule.onNode(hasText("#number") and hasSetTextAction()).performTextInput("14")
        composeRule.onNodeWithText("Add").performClick()

        assertThat(events).containsAtLeast("sub-:13", "sub+:14").inOrder()
    }

    @Test
    fun a_ticket_on_a_forge_that_shows_nothing_signed_out_asks_to_sign_in() {
        val sourceHut = fr.arthurbrugiere.forgeline.core.model.ForgeInstance(fr.arthurbrugiere.forgeline.core.model.ForgeType.SOURCEHUT, "git.sr.ht")
        setContent(IssueUiState(IssueRef(RepoId("~sircmpwn", "aerc", sourceHut), 12), error = fr.arthurbrugiere.forgeline.core.forge.ForgeError.Unauthorized), canComment = false)

        composeRule.onNodeWithText("Sign in to read SourceHut").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").performClick()

        assertThat(events).contains("signin")
    }

    @Test
    fun a_sub_issue_is_found_by_its_title_as_it_is_typed() {
        // Regression: the field took a number alone, to be known by heart.
        val subs = fr.arthurbrugiere.forgeline.core.model.SubIssues(null, emptyList())
        setContent(IssueUiState(ref, issueDetails(ref), subIssues = subs, access = RepoAccess.TRIAGE))
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Add a sub-issue"))
        composeRule.onNodeWithText("Add a sub-issue").performClick()

        composeRule.onNode(hasText("#number") and hasSetTextAction()).performTextInput("crash")
        assertThat(typedReferences.last()).isEqualTo(TypedReference(0, '#', "crash", field = "sub-issue"))
        // Listed under the field it is typed in, not under the comment's too.
        offer.value = ReferenceOffer(listOf(fr.arthurbrugiere.forgeline.core.testing.issueSummary(42, "Crash on start")), field = "sub-issue")
        composeRule.onNodeWithContentDescription("Issue #42, Crash on start").performClick()
        composeRule.onNode(hasText("#42") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithText("Add").performClick()

        assertThat(events).contains("sub+:#42")
    }

    @Test
    fun an_issue_s_type_shows_in_its_header_without_labels_too() {
        setContent(IssueUiState(ref, issueDetails(ref).copy(labels = emptyList(), type = fr.arthurbrugiere.forgeline.core.model.IssueType("Bug", "RED"))))

        composeRule.onNodeWithText("Bug").assertIsDisplayed()
    }

    @Test
    fun a_saved_reply_is_picked_into_the_comment() {
        val replies = Loadable.Loaded(listOf(fr.arthurbrugiere.forgeline.core.model.SavedReply("Needs repro", "Could you share the steps?")))
        setContent(IssueUiState(ref, issueDetails(ref), offersSavedReplies = true, savedReplies = replies))

        // An icon beside the picture one, named by what it does.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Saved replies"))
        composeRule.onNode(hasContentDescription("Saved replies")).performClick()
        composeRule.onNodeWithText("Needs repro").performClick()

        assertThat(events).containsAtLeast("load-replies", "reply:Needs repro").inOrder()
    }

    private companion object {
        /** The events the tests above this one already read as sentences. */
        val OLDER_EVENTS = setOf(
            ConversationEvent.LOCKED, ConversationEvent.UNLOCKED, ConversationEvent.PINNED, ConversationEvent.UNPINNED,
            ConversationEvent.ASSIGNED, ConversationEvent.UNASSIGNED, ConversationEvent.MILESTONED, ConversationEvent.DEMILESTONED,
            ConversationEvent.TRANSFERRED, ConversationEvent.DEADLINE_SET, ConversationEvent.DEADLINE_REMOVED, ConversationEvent.TRACKING_STARTED,
            ConversationEvent.TRACKING_STOPPED, ConversationEvent.TIME_ADDED, ConversationEvent.DEPENDENCY_ADDED, ConversationEvent.DEPENDENCY_REMOVED,
            ConversationEvent.CONVERTED_TO_DISCUSSION, ConversationEvent.BRANCH_DELETED, ConversationEvent.BRANCH_RESTORED,
        )
    }
}
