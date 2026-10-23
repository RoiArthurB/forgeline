package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.testing.comment
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
import fr.arthurbrugiere.forgeline.issue.IssueScreen
import fr.arthurbrugiere.forgeline.issue.IssueUiState
import fr.arthurbrugiere.forgeline.ui.assertEveryTargetIsAtLeast48dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE)
class PullRequestPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val ref = IssueRef(RepoId("octo", "tools"), 88, isPullRequest = true)
    private val open = issueDetails(ref, "Retry uploads").copy(pullRequest = PullRequestInfo(false, false, "main", "retry", 12, 3, 2, 1))
    private val checks = listOf(
        Check("build", CheckState.SUCCESS, "Built in 2m", "https://github.com/octo/tools/actions/runs/777/job/1", runId = 777),
        Check("lint", CheckState.FAILURE, null, "https://github.com/octo/tools/actions/runs/778/job/3", runId = 778),
        Check("coverage", CheckState.PENDING, "Waiting", "https://cov.example/1"),
    )
    private val reported = listOf(
        Check("health", CheckState.FAILURE, "PR Check", "https://github.com/octo/tools/runs/4242", checkId = 4242),
        // A job of a run has an id of its own too: the run is what opens.
        Check("unit", CheckState.SUCCESS, null, "https://github.com/octo/tools/actions/runs/777/job/9", runId = 777, checkId = 9),
    )
    private val mergeable = MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.SQUASH, MergeMethod.MERGE))

    private val actions = PullActions(
        onOpenChanges = { events += "changes" },
        onOpenCommits = { events += "commits" },
        onOpenRun = { events += "run:$it" },
        onOpenCheck = { id, name -> events += "check:$id:$name" },
        onOpenUrl = { events += "url:$it" },
        onMerge = { events += "merge:${it.method}${if (it.deleteBranch) ":delete-branch" else ""}${if (it.whenChecksPass) ":when-checks-pass" else ""}" },
        onCancelMergeWhenChecksPass = { events += "unschedule" },
        onUpdateBranch = { events += "update" },
        onReview = { verdict, body -> events += "review:$verdict:$body" },
    )

    /** The decisions as the reader's turn shows them: on its row of actions. */
    private fun setContent(state: PullRequestUiState, issue: IssueDetails = open) = composeRule.setContent {
        ForgelineTheme {
            Row { PullRequestDecisions(issue, state, actions) }
        }
    }

    private fun setChecks(checks: List<Check>) = composeRule.setContent {
        ForgelineTheme { PullRequestChecks(checks, actions) }
    }

    private val signedIn = PullRequestUiState(checks = listOf(checks[0]), mergeInfo = mergeable, signedIn = true, verdicts = ReviewVerdict.entries.toSet())
    private val failing = signedIn.copy(checks = checks)

    @Test
    fun the_files_and_the_commits_behind_the_numbers_are_a_tap_away_and_nothing_else_is_up_there() {
        composeRule.setContent { ForgelineTheme { PullRequestPanel(open, actions) } }

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNodeWithText("2 files changed").performClick()
        composeRule.onNodeWithText("1 commit").performClick()

        assertThat(events).containsExactly("changes", "commits").inOrder()
    }

    @Test
    fun signed_out_nothing_is_offered_to_decide() {
        setContent(PullRequestUiState(mergeInfo = mergeable))

        composeRule.onNodeWithText("Review").assertDoesNotExist()
        composeRule.onNodeWithText("Merge").assertDoesNotExist()
    }

    @Test
    fun checks_say_where_they_stand_and_each_opens_its_run_or_its_page() {
        setChecks(checks)

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNodeWithText("1 of 3 checks failed").assertIsDisplayed()
        // Listed where they are, not behind a tap: what failed, what runs, and what passed while there are few.
        composeRule.onNodeWithText("Built in 2m", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("coverage").performClick()
        composeRule.onNodeWithText("lint").performClick()

        assertThat(events).containsExactly("url:https://cov.example/1", "run:778").inOrder()
    }

    @Test
    fun what_failed_is_listed_first() {
        setChecks(checks)

        val lint = composeRule.onNodeWithText("lint").getBoundsInRoot()
        val build = composeRule.onNodeWithText("build").getBoundsInRoot()
        assertThat(lint.top).isLessThan(build.top)
    }

    @Test
    fun a_check_that_left_a_report_opens_it_in_the_app_not_in_the_browser() {
        setChecks(reported)

        composeRule.onNodeWithText("health").performClick()
        composeRule.onNodeWithText("unit").performClick()

        assertThat(events).containsExactly("check:4242:health", "run:777").inOrder()
    }

    @Test
    fun many_passed_checks_wait_behind_one_row_so_the_turn_stays_near() {
        val many = listOf(checks[1]) + (1..6).map { Check("job $it", CheckState.SUCCESS, null, null) }
        setChecks(many)

        composeRule.onNodeWithText("lint").assertIsDisplayed()
        composeRule.onNodeWithText("job 1").assertDoesNotExist()
        composeRule.onNodeWithText("Show 6 more checks").performClick()

        composeRule.onNodeWithText("job 6").assertIsDisplayed()
        composeRule.onNodeWithText("Show 6 more checks").assertDoesNotExist()
    }

    @Test
    fun checks_all_passed_or_still_running_say_so() {
        setChecks(listOf(checks[0], checks[2]))
        composeRule.onNodeWithText("1 of 2 checks still running").assertIsDisplayed()
    }

    @Test
    fun checks_that_all_passed_are_counted() {
        setChecks(listOf(checks[0]))
        composeRule.onNodeWithText("1 check passed").assertIsDisplayed()
    }

    @Test
    fun someone_who_can_merge_chooses_how_among_the_repository_s_ways() {
        setContent(signedIn)

        composeRule.onNodeWithText("Merge").performClick()
        composeRule.onNodeWithText("Merge pull request").assertIsDisplayed()
        // The repository's own choice is the one picked; rebasing isn't allowed here, so it isn't offered.
        composeRule.onNodeWithText("Rebase and merge").assertDoesNotExist()
        composeRule.onNodeWithText("Merge commit").performClick()
        composeRule.onNodeWithText("Confirm merge").performClick()

        assertThat(events).containsExactly("merge:MERGE")
    }

    @Test
    fun a_reader_reviews_but_is_not_offered_to_merge() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(canMerge = false)))

        composeRule.onNodeWithText("Merge").assertDoesNotExist()
        composeRule.onNodeWithText("Review").performClick()
        composeRule.onNodeWithText("Approve").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Thanks!")
        composeRule.onNodeWithText("Send review").performClick()

        assertThat(events).containsExactly("review:APPROVE:Thanks!")
    }

    @Test
    fun what_the_forge_refuses_to_merge_is_said_and_not_offered() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(mergeable = false)))

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("can't be merged as it is", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Confirm merge").assertIsNotEnabled()
    }

    @Test
    fun what_the_forge_is_still_checking_can_be_tried() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(mergeable = null)))

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("still working out", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Confirm merge").assertIsEnabled()
    }

    @Test
    fun a_draft_is_not_merged_and_a_refused_merge_says_why() {
        setContent(
            signedIn.copy(mergeError = ForgeError.Http(405, "Required status check is failing")),
            open.copy(pullRequest = open.pullRequest!!.copy(isDraft = true)),
        )

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("A draft isn't merged", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("The forge refused the merge: Required status check is failing").assertIsDisplayed()
    }

    @Test
    fun a_draft_offers_to_be_marked_ready_in_merge_s_place_to_whoever_may() {
        val draft = open.copy(pullRequest = open.pullRequest!!.copy(isDraft = true))
        composeRule.setContent {
            ForgelineTheme { Row { PullRequestDecisions(draft, signedIn, actions, onReady = { events += "ready" }) } }
        }

        composeRule.onNodeWithText("Merge").assertDoesNotExist()
        composeRule.onNodeWithText("Ready for review").performClick()

        assertThat(events).containsExactly("ready")
    }

    @Test
    fun a_draft_being_marked_ready_can_t_be_pressed_twice() {
        val draft = open.copy(pullRequest = open.pullRequest!!.copy(isDraft = true))
        composeRule.setContent {
            ForgelineTheme { Row { PullRequestDecisions(draft, signedIn, actions, onReady = { events += "ready" }, isMarkingReady = true) } }
        }

        composeRule.onNodeWithText("Ready for review").assertIsNotEnabled()
    }

    @Test
    fun a_pull_request_that_is_merged_or_closed_offers_neither() {
        setContent(signedIn, open.copy(state = IssueState.MERGED))

        composeRule.onNodeWithText("Review").assertDoesNotExist()
        composeRule.onNodeWithText("Merge").assertDoesNotExist()
    }

    @Test
    fun merging_against_a_failed_check_is_said_where_it_is_decided_and_named_for_what_it_is() {
        setContent(failing)

        composeRule.onNodeWithText("Merge").performClick()
        // Where the checks stand is repeated in the sheet, and the way on no longer reads as the plain next step.
        composeRule.onNodeWithText("1 of 3 checks failed").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm merge").assertDoesNotExist()
        composeRule.onNodeWithText("Merge anyway").performClick()

        assertThat(events).containsExactly("merge:SQUASH")
    }

    @Test
    fun checks_still_running_are_said_in_the_merge_sheet_without_calling_it_a_merge_against_them() {
        setContent(signedIn.copy(checks = listOf(checks[0], checks[2])))

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("1 of 2 checks still running").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm merge").assertIsDisplayed()
        composeRule.onNodeWithText("Merge anyway").assertDoesNotExist()
    }

    @Test
    fun the_branch_goes_with_the_merge_as_the_repository_prefers_and_as_chosen() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(deleteBranch = true)))

        composeRule.onNodeWithText("Merge").performClick()
        composeRule.onNodeWithText("Delete retry").assertIsOn()
        composeRule.onNodeWithText("Confirm merge").performClick()
        composeRule.onNodeWithText("Delete retry").performClick()
        composeRule.onNodeWithText("Confirm merge").performClick()

        assertThat(events).containsExactly("merge:SQUASH:delete-branch", "merge:SQUASH").inOrder()
    }

    @Test
    fun a_branch_that_isn_t_the_repository_s_to_delete_is_not_offered() {
        setContent(signedIn)

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("Delete retry").assertDoesNotExist()
    }

    @Test
    fun checks_still_running_make_waiting_for_them_the_way_on_and_merging_now_the_other() {
        setContent(signedIn.copy(checks = listOf(checks[0], checks[2]), mergeInfo = mergeable.copy(canMergeWhenChecksPass = true)))

        composeRule.onNodeWithText("Merge").performClick()
        composeRule.onNodeWithText("Merge when checks pass").performClick()
        composeRule.onNodeWithText("Merge now").performClick()

        assertThat(events).containsExactly("merge:SQUASH:when-checks-pass", "merge:SQUASH").inOrder()
    }

    @Test
    fun a_forge_that_can_t_wait_for_checks_only_merges_now() {
        setContent(signedIn.copy(checks = listOf(checks[0], checks[2])))

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("Merge when checks pass").assertDoesNotExist()
        composeRule.onNodeWithText("Confirm merge").assertIsDisplayed()
    }

    @Test
    fun a_pull_request_set_to_merge_by_itself_says_so_and_can_be_taken_back() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(canMergeWhenChecksPass = true, mergesWhenChecksPass = true)))

        composeRule.onNodeWithText("Merge").assertDoesNotExist()
        composeRule.onNodeWithText("Merging when checks pass").performClick()
        composeRule.onNodeWithText("It will be merged once its checks pass.").assertIsDisplayed()
        composeRule.onNodeWithText("Don't merge automatically").performClick()

        assertThat(events).containsExactly("unschedule")
    }

    @Test
    fun a_branch_behind_the_one_it_goes_into_is_offered_to_be_brought_up_to_date() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(behind = true)))

        composeRule.onNodeWithText("Update branch").performClick()

        assertThat(events).containsExactly("update")
        // Merging stays offered: being behind doesn't block every forge.
        composeRule.onNodeWithText("Merge").assertIsDisplayed()
    }

    @Test
    fun a_branch_being_brought_up_to_date_can_t_be_pressed_twice() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(behind = true), isUpdating = true))

        composeRule.onNodeWithText("Updating…").assertIsNotEnabled()
    }

    @Test
    fun an_up_to_date_branch_or_a_reader_who_can_t_merge_is_offered_no_update() {
        setContent(signedIn.copy(mergeInfo = mergeable.copy(behind = true, canMerge = false)))

        composeRule.onNodeWithText("Update branch").assertDoesNotExist()
    }

    @Test
    fun the_merge_sheet_has_a_way_out_beside_the_way_on() {
        setContent(signedIn)

        composeRule.onNodeWithText("Merge").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Merge pull request")).fetchSemanticsNodes().isEmpty() }
        assertThat(events).isEmpty()
    }

    @Test
    fun checks_that_all_passed_say_nothing_more_in_the_merge_sheet() {
        setContent(signedIn)

        composeRule.onNodeWithText("Merge").performClick()

        composeRule.onNodeWithText("1 check passed").assertDoesNotExist()
    }

    /**
     * Regression (2026-10-23): Review and Merge sat at the top, under the pull request's numbers, while Close sat at the
     * end beside the comment; the owner looked for Merge at the end and took it for missing. Everything decided on a pull
     * request is now at its end, in reading order: the conversation, what ran on it, the comment, then every action.
     */
    @Test
    fun a_pull_request_is_decided_on_at_its_end_after_its_checks_and_beside_closing_it() {
        composeRule.setContent {
            ForgelineTheme {
                IssueScreen(
                    state = IssueUiState(ref, open, listOf(comment(1, "Looks good", login = "hubot")), canChangeState = true),
                    pull = failing,
                    pullActions = actions,
                    canComment = true, onDraftChange = {}, onSendComment = {}, onToggleOpen = { events += "close" }, onSignIn = {}, onCommentNoticeShown = {},
                    onBack = {}, onRefresh = {}, onLoadMore = {}, onOpenIssue = {}, onOpenRepo = {}, onOpenUser = {}, onOpenInBrowser = {},
                    onLinkClick = {}, onErrorShown = {},
                )
            }
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Looks good")).fetchSemanticsNodes().isNotEmpty() }
        val list = composeRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Close pull request"))

        val comment = composeRule.onNodeWithText("Looks good").getBoundsInRoot()
        val checksHeading = composeRule.onNodeWithText("1 of 3 checks failed").getBoundsInRoot()
        val field = composeRule.onNode(hasSetTextAction()).getBoundsInRoot()
        val review = composeRule.onNodeWithText("Review").getBoundsInRoot()
        val merge = composeRule.onNodeWithText("Merge").getBoundsInRoot()
        val close = composeRule.onNodeWithText("Close pull request").getBoundsInRoot()
        assertThat(checksHeading.top).isGreaterThan(comment.bottom)
        assertThat(field.top).isGreaterThan(checksHeading.bottom)
        listOf(review, merge, close).forEach { assertThat(it.top).isAtLeast(field.bottom) }
        // The header keeps only the way to the files and commits.
        composeRule.onAllNodes(hasText("Merge")).assertCountEquals(1)
        composeRule.onAllNodes(hasText("1 of 3 checks failed")).assertCountEquals(1)
        composeRule.onNodeWithText("Merge").performClick()
        composeRule.onNodeWithText("Merge anyway").assertIsDisplayed()
    }
}
