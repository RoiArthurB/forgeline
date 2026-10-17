package fr.arthurbrugiere.forgeline

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.MergeMethod
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.testing.FakePullRequestApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The whole way through a pull request, signed in: from the Inbox to its change, a review of it, and its merge. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = PHONE, application = HiltTestApplication::class)
class PullRequestFlowTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var accounts: AccountRepository

    @Inject lateinit var pulls: FakePullRequestApi

    @After
    fun signOut() = runBlocking { accounts.accounts.first().forEach { accounts.signOut(it.id) } }

    private fun waitFor(text: String) =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForDescription(text: String) =
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Signs in, then opens the pull request a review is asked for, from the Inbox's own-work filter. */
    private fun openPullRequest() {
        hiltRule.inject()
        // Its reviews and merges are answered late, from off the main thread, as a forge's are.
        pulls.writeLatencyMillis = WRITE_LATENCY_MILLIS
        // Regression: on a slow runner the app had settled on its signed-out start tab before the sign-in landed, and
        // the Inbox the test waited for never opened (2026-10-12 and 14). Signed in after it settles, as it was there.
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasContentDescription("Trending") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { accounts.signIn(ForgeInstance.GitHub, ForgeUser("octocat", "The Octocat", null), "tok") }
        // The start tab is chosen once, at launch: the Inbox is opened by hand, as someone signing in later would.
        composeRule.onNode(hasContentDescription("Inbox") and isSelectable()).performClick()
        waitFor("Yours")
        composeRule.onNodeWithText("Yours").performClick()
        waitFor("Retry uploads on slow links")
        composeRule.onNodeWithText("Retry uploads on slow links").performClick()
        waitFor("2 files changed")
    }

    @Test
    fun a_pull_request_leads_to_its_change_where_a_line_is_remarked_on_and_a_review_sent() {
        openPullRequest()
        // What ran on it is said beside its numbers.
        waitFor("1 of 2 checks failed")

        composeRule.onNodeWithText("2 files changed").performClick()
        waitFor("Files changed")
        composeRule.onNodeWithText("src/Upload.kt").assertIsDisplayed()
        waitForDescription("Line 2, added: retry { send(file) }")
        composeRule.onNodeWithContentDescription("Line 2, added: retry { send(file) }").performClick()
        waitFor("Add to the review")
        composeRule.onNode(hasSetTextAction()).performTextInput("Why not three times?")
        composeRule.onNodeWithText("Add to the review").performClick()
        waitFor("Finish review (1 remark)")

        composeRule.onNodeWithText("Finish review (1 remark)").performClick()
        waitFor("Send review")
        composeRule.onNodeWithText("Request changes").performClick()
        composeRule.onNodeWithText("Send review").performClick()
        composeRule.waitUntilAsked { pulls.reviews.isNotEmpty() }

        assertThat(pulls.reviews.single()).isEqualTo(
            FakePullRequestApi.SentReview(
                ReviewVerdict.REQUEST_CHANGES, "",
                listOf(LineComment("src/Upload.kt", oldLine = null, newLine = 2, body = "Why not three times?")),
            ),
        )
        // The remark went with the review: nothing is left waiting.
        waitFor("Review sent")
        composeRule.onNodeWithText("Why not three times?").assertDoesNotExist()
    }

    @Test
    fun the_review_button_sits_above_the_navigation_bar() {
        // Regression: it cleared only the system bar, so the app's bar covered it and a tap on it opened Trending.
        openPullRequest()
        composeRule.onNodeWithText("2 files changed").performClick()
        waitFor("Files changed")

        val review = composeRule.onNode(hasText("Review") and hasClickAction()).assertIsDisplayed().getBoundsInRoot()
        val bar = composeRule.onNodeWithContentDescription("Trending").getBoundsInRoot()
        assertThat(review.bottom.value).isAtMost(bar.top.value)
    }

    @Test
    fun a_check_another_service_ran_opens_on_its_report_without_leaving_the_app() {
        openPullRequest()
        waitFor("1 of 2 checks failed")

        composeRule.onNodeWithText("1 of 2 checks failed").performClick()
        waitFor("2 warnings")
        composeRule.onNodeWithText("lint").performClick()

        waitFor("Two lines are longer than they may be.")
        composeRule.onNodeWithText("Lint Bot").assertIsDisplayed()
        composeRule.onNodeWithText("Open on Lint Bot").assertIsDisplayed()
        assertThat(pulls.calls).contains("checkReport:octo/tools:4242")
        // Back is the conversation, its sheet put away.
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("2 files changed")
    }

    @Test
    fun a_pull_request_s_commits_each_open_on_what_they_changed() {
        openPullRequest()

        composeRule.onNodeWithText("2 commits").performClick()
        waitFor("Say so in the docs")
        composeRule.onNodeWithText("Say so in the docs").performClick()

        // The commit's own page: its one file, not the pull request's two.
        waitFor("docs/retry.md")
        waitForDescription("Line 1, added: Retries three times.")
        composeRule.onNodeWithText("src/Upload.kt").assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Navigate up")).performClick()
        waitFor("Retry the upload")
    }

    @Test
    fun someone_who_can_merge_merges_from_the_conversation() {
        openPullRequest()
        waitFor("Merge")

        composeRule.onNodeWithText("Merge").performClick()
        waitFor("Merge pull request")
        composeRule.onNodeWithText("Squash and merge").performClick()
        // One of its two checks failed: the sheet says so again, and names the merge for what it is.
        composeRule.onNodeWithText("Confirm merge").assertDoesNotExist()
        composeRule.onNodeWithText("Merge anyway").performClick()
        composeRule.waitUntilAsked { pulls.calls.any { it.startsWith("merge:") } }

        assertThat(pulls.calls).contains("merge:octo/tools#88:${MergeMethod.SQUASH}")
        // The sheet closes on what it did.
        composeRule.waitUntil(FLOW_WAIT_MILLIS) { composeRule.onAllNodes(hasText("Merge pull request")).fetchSemanticsNodes().isEmpty() }
    }
}
