package fr.arthurbrugiere.forgeline.pull

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.PHONE
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Check
import fr.arthurbrugiere.forgeline.core.model.CheckState
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.MergeInfo
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.PullRequestInfo
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.testing.issueDetails
import fr.arthurbrugiere.forgeline.core.ui.theme.ForgelineTheme
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
    private val mergeable = MergeInfo(mergeable = true, canMerge = true, methods = listOf(MergeMethod.SQUASH, MergeMethod.MERGE))

    private fun setContent(state: PullRequestUiState, issue: fr.arthurbrugiere.forgeline.core.model.IssueDetails = open) = composeRule.setContent {
        ForgelineTheme {
            PullRequestPanel(
                issue,
                state,
                PullActions(
                    onOpenChanges = { events += "changes" },
                    onOpenCommits = { events += "commits" },
                    onOpenRun = { events += "run:$it" },
                    onOpenUrl = { events += "url:$it" },
                    onMerge = { events += "merge:$it" },
                    onReview = { verdict, body -> events += "review:$verdict:$body" },
                ),
            )
        }
    }

    private val signedIn = PullRequestUiState(checks = checks, mergeInfo = mergeable, signedIn = true, verdicts = ReviewVerdict.entries.toSet())

    @Test
    fun the_files_and_the_commits_behind_the_numbers_are_a_tap_away() {
        setContent(PullRequestUiState())

        composeRule.assertEveryTargetIsAtLeast48dp()
        composeRule.onNodeWithText("2 files changed").performClick()
        composeRule.onNodeWithText("1 commit").performClick()

        assertThat(events).containsExactly("changes", "commits").inOrder()
        // Signed out, and nothing ran: nothing more is offered.
        composeRule.onNodeWithText("Review").assertDoesNotExist()
        composeRule.onNodeWithText("Merge").assertDoesNotExist()
    }

    @Test
    fun checks_say_where_they_stand_and_each_opens_its_run_or_its_page() {
        setContent(PullRequestUiState(checks = checks))

        composeRule.onNodeWithText("1 of 3 checks failed").performClick()
        // What failed and what it is, then where it leads.
        composeRule.onNodeWithText("Built in 2m", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("coverage").performClick()
        composeRule.onNodeWithText("lint").performClick()

        assertThat(events).containsExactly("url:https://cov.example/1", "run:778").inOrder()
    }

    @Test
    fun checks_all_passed_or_still_running_say_so() {
        setContent(PullRequestUiState(checks = listOf(checks[0], checks[2])))
        composeRule.onNodeWithText("1 of 2 checks still running").assertIsDisplayed()
    }

    @Test
    fun checks_that_all_passed_are_counted() {
        setContent(PullRequestUiState(checks = listOf(checks[0])))
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
    fun a_pull_request_that_is_merged_or_closed_offers_neither() {
        setContent(signedIn, open.copy(state = IssueState.MERGED))

        composeRule.onNodeWithText("2 files changed").assertIsDisplayed()
        composeRule.onNodeWithText("Review").assertDoesNotExist()
        composeRule.onNodeWithText("Merge").assertDoesNotExist()
    }
}
